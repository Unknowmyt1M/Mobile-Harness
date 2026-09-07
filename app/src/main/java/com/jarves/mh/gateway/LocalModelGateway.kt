package com.jarves.mh.gateway

import android.util.Log
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.ProviderProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL

class LocalModelGateway(
    private val profile: ProviderProfile,
    private val apiKey: String,
) {
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    var port: Int = 0
        private set

    val usage = GatewayUsage()

    val url: String
        get() = "http://127.0.0.1:$port"

    fun start(): String {
        val socket = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        port = socket.localPort
        Log.i("LocalModelGateway", "Local gateway server listening on $url for ${profile.kind}")

        serverJob = scope.launch {
            while (serverSocket?.isClosed == false) {
                try {
                    val client = socket.accept()
                    launch { handleClient(client) }
                } catch (_: Exception) {
                    break
                }
            }
        }
        return url
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverJob?.cancel()
        serverSocket = null
    }

    private fun handleClient(client: Socket) {
        client.use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer = socket.getOutputStream()

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1]

            // Read headers
            var contentLength = 0
            var line: String?
            while (reader.readLine().also { line = it } != null && !line.isNullOrEmpty()) {
                val header = line!!.lowercase()
                if (header.startsWith("content-length:")) {
                    contentLength = header.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            // Read body
            val body = if (contentLength > 0) {
                val charBuffer = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val count = reader.read(charBuffer, read, contentLength - read)
                    if (count < 0) break
                    read += count
                }
                String(charBuffer, 0, read)
            } else ""

            if (method == "POST" && (path.startsWith("/v1/messages") || path.startsWith("/messages"))) {
                proxyMessagesRequest(body, writer)
            } else if (method == "GET" && path.contains("models")) {
                sendJsonResponse(writer, 200, "{\"data\":[{\"id\":\"${profile.model}\"}]}")
            } else {
                sendJsonResponse(writer, 200, "{\"status\":\"ok\"}")
            }
        }
    }

    private fun proxyMessagesRequest(anthropicBody: String, clientOut: OutputStream) {
        runCatching {
            val json = JSONObject(anthropicBody)
            val model = json.optString("model", profile.model).ifBlank { profile.model }
            val anthropicMessages = json.optJSONArray("messages") ?: JSONArray()
            val systemPrompt = json.optString("system")

            // Convert to OpenAI messages
            val openAiMessages = JSONArray()
            if (systemPrompt.isNotBlank()) {
                openAiMessages.put(JSONObject().put("role", "system").put("content", systemPrompt))
            }
            for (i in 0 until anthropicMessages.length()) {
                val msg = anthropicMessages.getJSONObject(i)
                val role = msg.optString("role", "user")
                val content = msg.opt("content")
                val textContent = when (content) {
                    is String -> content
                    is JSONArray -> {
                        buildString {
                            for (j in 0 until content.length()) {
                                val block = content.optJSONObject(j)
                                if (block?.optString("type") == "text") {
                                    append(block.optString("text"))
                                }
                            }
                        }
                    }
                    else -> content?.toString().orEmpty()
                }
                openAiMessages.put(JSONObject().put("role", role).put("content", textContent))
            }

            val normalizedBase = profile.baseUrl.trimEnd('/')
            val targetUrl = if (normalizedBase.endsWith("/chat/completions")) {
                normalizedBase
            } else {
                "$normalizedBase/chat/completions"
            }

            val targetPayload = JSONObject()
                .put("model", model)
                .put("messages", openAiMessages)
                .put("stream", true)
                .put("stream_options", JSONObject().put("include_usage", true))

            val payloadBytes = targetPayload.toString().toByteArray(Charsets.UTF_8)

            // Connect with retry logic for transient errors (429, 502, 503, 504)
            var lastResponseCode = 500
            var lastErrorText = ""
            var activeConn: HttpURLConnection? = null

            for (attempt in 1..3) {
                val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    profile.customHeaders.forEach { (k, v) ->
                        setRequestProperty(k, v)
                    }
                }

                conn.outputStream.use { it.write(payloadBytes) }
                lastResponseCode = conn.responseCode

                if (lastResponseCode in 200..299) {
                    activeConn = conn
                    break
                }

                lastErrorText = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                conn.disconnect()

                if (lastResponseCode in listOf(429, 502, 503, 504) && attempt < 3) {
                    Thread.sleep(300L * attempt)
                } else {
                    break
                }
            }

            val conn = activeConn
            if (conn == null) {
                val formattedError = "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":${JSONObject.quote(lastErrorText.ifBlank { "Upstream returned HTTP $lastResponseCode" })}}}"
                sendJsonResponse(clientOut, lastResponseCode, formattedError)
                return
            }

            // Stream response in Anthropic SSE format back to client
            clientOut.write("HTTP/1.1 200 OK\r\n".toByteArray())
            clientOut.write("Content-Type: text/event-stream\r\n".toByteArray())
            clientOut.write("Cache-Control: no-cache\r\n".toByteArray())
            clientOut.write("Connection: keep-alive\r\n\r\n".toByteArray())

            // Initial message_start
            writeSse(clientOut, "message_start", "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_proxy\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"model\":\"$model\"}}")
            writeSse(clientOut, "content_block_start", "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}")

            val streamReader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            var streamLine: String?
            var doneReceived = false
            try {
                while (true) {
                    val readResult = runCatching { streamReader.readLine() }
                    if (readResult.isFailure) {
                        val error = readResult.exceptionOrNull()
                        if (doneReceived) {
                            Log.d("LocalModelGateway", "Upstream socket closed after [DONE]: ${error?.message}")
                            break
                        } else {
                            throw error ?: java.io.IOException("Stream ended prematurely before [DONE]")
                        }
                    }
                    streamLine = readResult.getOrNull()
                    if (streamLine == null) break

                    val line = streamLine.trim()
                    if (line.startsWith("data: ")) {
                        val data = line.removePrefix("data: ").trim()
                        if (data == "[DONE]") {
                            doneReceived = true
                            break
                        }
                        val deltaJson = runCatching { JSONObject(data) }.getOrNull()

                        // Check for token usage stats
                        val usageObj = deltaJson?.optJSONObject("usage")
                        if (usageObj != null) {
                            val promptTokens = usageObj.optLong("prompt_tokens", 0)
                            val completionTokens = usageObj.optLong("completion_tokens", 0)
                            val totalTokens = usageObj.optLong("total_tokens", 0)
                            synchronized(usage) {
                                usage.promptTokens += promptTokens
                                usage.completionTokens += completionTokens
                                usage.totalTokens += totalTokens
                                usage.requestCount++
                                usage.estimatedCostUsd += GatewayCostCalculator.calculateCost(model, promptTokens, completionTokens)
                            }
                        }

                        val choices = deltaJson?.optJSONArray("choices")
                        val delta = choices?.optJSONObject(0)?.optJSONObject("delta")
                        val contentPiece = delta?.optString("content").orEmpty()
                        if (contentPiece.isNotEmpty()) {
                            val anthropicDelta = JSONObject()
                                .put("type", "content_block_delta")
                                .put("index", 0)
                                .put("delta", JSONObject().put("type", "text_delta").put("text", contentPiece))
                            writeSse(clientOut, "content_block_delta", anthropicDelta.toString())
                        }
                    }
                }
            } finally {
                runCatching { streamReader.close() }
            }

            if (!doneReceived) {
                throw java.io.IOException("Stream from upstream ended prematurely without [DONE]")
            }

            writeSse(clientOut, "content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}")
            writeSse(clientOut, "message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}")
            writeSse(clientOut, "message_stop", "{\"type\":\"message_stop\"}")
            clientOut.flush()
            conn.disconnect()
        }.onFailure { error ->
            Log.e("LocalModelGateway", "Proxy request failed", error)
            val errJson = "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":${JSONObject.quote(error.message.orEmpty())}}}"
            sendJsonResponse(clientOut, 500, errJson)
        }
    }

    private fun writeSse(out: OutputStream, event: String, data: String) {
        val payload = "event: $event\ndata: $data\n\n"
        out.write(payload.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun sendJsonResponse(out: OutputStream, statusCode: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $statusCode OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }
}
