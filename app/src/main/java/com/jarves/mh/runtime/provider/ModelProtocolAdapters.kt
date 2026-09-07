package com.jarves.mh.runtime.provider

import android.util.Log
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.runtime.tool.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

interface ModelProtocolAdapter {
    fun streamTurn(
        provider: ProviderProfile,
        apiKey: String,
        messages: JSONArray,
        toolRegistry: ToolRegistry?,
    ): Flow<ParsedStreamChunk>
}

/**
 * Reads an SSE stream safely checking for strict completion signals.
 *
 * Rules:
 * 1. An SSE stream is ONLY considered successfully completed when an explicit
 *    completion signal from the protocol has been parsed (e.g. [DONE], finish_reason != null,
 *    or response.completed / response.done).
 * 2. If an IOException occurs AFTER the explicit completion signal has been emitted,
 *    it is treated as a clean/normal stream termination.
 * 3. If an IOException occurs BEFORE any explicit completion signal:
 *    - If NO chunks have been emitted yet (cold socket drop / handshake failure), it can be retried safely.
 *    - If content or tool chunks HAVE already been emitted, it must fail as an incomplete stream
 *      to prevent acting on truncated or corrupt output.
 */
internal suspend fun streamSseWithCompletionCheck(
    inputStream: InputStream,
    parser: OpenAIStreamingParser,
    onChunk: suspend (ParsedStreamChunk) -> Unit,
) {
    val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
    var completedSignalReceived = false
    var chunksEmittedCount = 0

    try {
        var line: String?
        while (true) {
            val readResult = runCatching { reader.readLine() }
            if (readResult.isFailure) {
                val error = readResult.exceptionOrNull()
                if (error is IOException) {
                    if (completedSignalReceived) {
                        // Socket closed or abrupt EOF AFTER receiving protocol completion signal
                        Log.d("ModelProtocolAdapter", "Socket terminated after receiving terminal completion signal: ${error.message}")
                        break
                    } else if (chunksEmittedCount == 0) {
                        // Cold connection failure before any chunks arrived; rethrow original error so retry handler can catch it
                        throw error
                    } else {
                        // Connection dropped after partial stream but before terminal completion signal
                        throw IOException(
                            "Stream disconnected prematurely before terminal completion signal ([DONE]/finish_reason). Emitted $chunksEmittedCount chunks. Underlying error: ${error.message}",
                            error,
                        )
                    }
                } else {
                    throw error ?: RuntimeException("Unknown stream read error")
                }
            }

            line = readResult.getOrNull()
            if (line == null) {
                // Natural EOF reached
                break
            }

            val chunks = parser.parseLine(line.orEmpty())
            for (chunk in chunks) {
                chunksEmittedCount++
                if (chunk is ParsedStreamChunk.Completed) {
                    completedSignalReceived = true
                }
                onChunk(chunk)
            }
        }
    } finally {
        runCatching { reader.close() }
    }

    if (!completedSignalReceived && chunksEmittedCount > 0) {
        throw IOException("Stream ended prematurely: received $chunksEmittedCount chunk(s) but no explicit protocol completion signal was received.")
    }
}

class OpenAIChatAdapter : ModelProtocolAdapter {
    override fun streamTurn(
        provider: ProviderProfile,
        apiKey: String,
        messages: JSONArray,
        toolRegistry: ToolRegistry?,
    ): Flow<ParsedStreamChunk> = flow {
        val parser = OpenAIStreamingParser()
        parser.reset()

        val rawBase = provider.baseUrl.trim().removeSuffix("/")
        val endpoint = if (rawBase.endsWith("/chat/completions")) {
            rawBase
        } else if (rawBase.endsWith("/v1")) {
            "$rawBase/chat/completions"
        } else {
            "$rawBase/v1/chat/completions"
        }

        val payload = JSONObject().apply {
            // Crucial: Pass model ID completely untouched/opaque!
            put("model", provider.model)
            put("messages", messages)
            put("stream", true)
            if (toolRegistry != null) {
                val toolsJson = toolRegistry.toOpenAIToolsJson()
                if (toolsJson.length() > 0) {
                    put("tools", toolsJson)
                    put("tool_choice", "auto")
                }
            }
        }
        val payloadBytes = payload.toString().toByteArray(Charsets.UTF_8)

        // Safe retry loop: max 2 attempts. ONLY retry if ZERO chunks were emitted.
        val maxAttempts = 2
        var chunksEmittedTotal = 0

        for (attempt in 1..maxAttempts) {
            val url = URL(endpoint)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                doInput = true
                connectTimeout = 30_000
                readTimeout = 120_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "text/event-stream")
                if (apiKey.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
                // Add custom headers
                provider.customHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            }

            try {
                conn.outputStream.use { os ->
                    os.write(payloadBytes)
                    os.flush()
                }

                val responseCode = conn.responseCode
                if (responseCode !in 200..299) {
                    val errorBody = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    throw RuntimeException("HTTP $responseCode from model gateway: $errorBody")
                }

                streamSseWithCompletionCheck(
                    inputStream = conn.inputStream,
                    parser = parser,
                    onChunk = { chunk ->
                        chunksEmittedTotal++
                        emit(chunk)
                    },
                )
                // If we reach here successfully, break out of retry loop
                break
            } catch (e: Exception) {
                // If chunks were already emitted, DO NOT RETRY to prevent duplicate/split output
                if (chunksEmittedTotal > 0 || attempt >= maxAttempts) {
                    throw e
                }
                Log.w("OpenAIChatAdapter", "Transient connection failure before receiving data on attempt $attempt, retrying: ${e.message}")
            } finally {
                conn.disconnect()
            }
        }
    }.flowOn(Dispatchers.IO)
}

class OpenAIResponsesAdapter : ModelProtocolAdapter {
    override fun streamTurn(
        provider: ProviderProfile,
        apiKey: String,
        messages: JSONArray,
        toolRegistry: ToolRegistry?,
    ): Flow<ParsedStreamChunk> = flow {
        val parser = OpenAIStreamingParser()
        parser.reset()

        val rawBase = provider.baseUrl.trim().removeSuffix("/")
        val endpoint = if (rawBase.endsWith("/responses")) {
            rawBase
        } else if (rawBase.endsWith("/v1")) {
            "$rawBase/responses"
        } else {
            "$rawBase/v1/responses"
        }

        val payload = JSONObject().apply {
            put("model", provider.model)
            put("stream", true)
            put("input", messages)
            if (toolRegistry != null) {
                val tools = toolRegistry.toOpenAIToolsJson()
                if (tools.length() > 0) {
                    put("tools", tools)
                }
            }
        }
        val payloadBytes = payload.toString().toByteArray(Charsets.UTF_8)

        val maxAttempts = 2
        var chunksEmittedTotal = 0

        for (attempt in 1..maxAttempts) {
            val url = URL(endpoint)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                doInput = true
                connectTimeout = 30_000
                readTimeout = 120_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "text/event-stream")
                if (apiKey.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
                provider.customHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            }

            try {
                conn.outputStream.use { os ->
                    os.write(payloadBytes)
                    os.flush()
                }

                val responseCode = conn.responseCode
                if (responseCode !in 200..299) {
                    val errorBody = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    throw RuntimeException("HTTP $responseCode from responses endpoint: $errorBody")
                }

                streamSseWithCompletionCheck(
                    inputStream = conn.inputStream,
                    parser = parser,
                    onChunk = { chunk ->
                        chunksEmittedTotal++
                        emit(chunk)
                    },
                )
                break
            } catch (e: Exception) {
                if (chunksEmittedTotal > 0 || attempt >= maxAttempts) {
                    throw e
                }
                Log.w("OpenAIResponsesAdapter", "Transient connection failure before receiving data on attempt $attempt, retrying: ${e.message}")
            } finally {
                conn.disconnect()
            }
        }
    }.flowOn(Dispatchers.IO)
}
