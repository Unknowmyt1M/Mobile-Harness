package com.jarves.mh.runtime.provider

import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.runtime.tool.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
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

        conn.outputStream.use { os ->
            os.write(payload.toString().toByteArray(Charsets.UTF_8))
            os.flush()
        }

        val responseCode = conn.responseCode
        if (responseCode !in 200..299) {
            val errorBody = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw RuntimeException("HTTP $responseCode from model gateway: $errorBody")
        }

        val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val chunks = parser.parseLine(line.orEmpty())
                for (chunk in chunks) {
                    emit(chunk)
                }
            }
        } finally {
            reader.close()
            conn.disconnect()
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

        // Convert messages format if needed or pass as input
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

        conn.outputStream.use { os ->
            os.write(payload.toString().toByteArray(Charsets.UTF_8))
            os.flush()
        }

        val responseCode = conn.responseCode
        if (responseCode !in 200..299) {
            val errorBody = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw RuntimeException("HTTP $responseCode from responses endpoint: $errorBody")
        }

        val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val chunks = parser.parseLine(line.orEmpty())
                for (chunk in chunks) {
                    emit(chunk)
                }
            }
        } finally {
            reader.close()
            conn.disconnect()
        }
    }.flowOn(Dispatchers.IO)
}
