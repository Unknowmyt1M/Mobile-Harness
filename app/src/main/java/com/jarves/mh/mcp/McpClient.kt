package com.jarves.mh.mcp

import com.jarves.mh.security.SecretRedactor
import org.json.JSONArray
import org.json.JSONObject

object McpClient {

    fun createInitializeRequest(id: Int = 1): String {
        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "initialize")
            put(
                "params",
                JSONObject().apply {
                    put("protocolVersion", "2024-11-05")
                    put(
                        "clientInfo",
                        JSONObject().apply {
                            put("name", "Mobile-Harness")
                            put("version", "1.0.0")
                        }
                    )
                    put("capabilities", JSONObject().apply { put("tools", JSONObject()) })
                }
            )
        }.toString()
    }

    fun createListToolsRequest(id: Int = 2): String {
        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "tools/list")
            put("params", JSONObject())
        }.toString()
    }

    fun createCallToolRequest(id: Int, toolName: String, arguments: JSONObject): String {
        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "tools/call")
            put(
                "params",
                JSONObject().apply {
                    put("name", toolName)
                    put("arguments", arguments)
                }
            )
        }.toString()
    }

    fun parseListToolsResponse(responseJson: String): List<McpToolDefinition> {
        return runCatching {
            val json = JSONObject(responseJson)
            val result = json.optJSONObject("result") ?: return emptyList()
            val toolsArray = result.optJSONArray("tools") ?: return emptyList()
            val list = mutableListOf<McpToolDefinition>()
            for (i in 0 until toolsArray.length()) {
                val toolObj = toolsArray.getJSONObject(i)
                list.add(
                    McpToolDefinition(
                        name = toolObj.getString("name"),
                        description = toolObj.optString("description", ""),
                        inputSchema = toolObj.optJSONObject("inputSchema")?.toString() ?: "{}",
                    )
                )
            }
            list
        }.getOrDefault(emptyList())
    }

    fun parseCallToolResponse(responseJson: String): McpToolCallResult {
        return runCatching {
            val json = JSONObject(responseJson)
            val error = json.optJSONObject("error")
            if (error != null) {
                val message = error.optString("message", "Unknown MCP error")
                return McpToolCallResult(
                    isError = true,
                    content = "",
                    diagnostic = SecretRedactor.redact(message),
                )
            }

            val result = json.optJSONObject("result") ?: return McpToolCallResult(isError = true, diagnostic = "Empty result")
            val isError = result.optBoolean("isError", false)
            val contentArray = result.optJSONArray("content") ?: JSONArray()
            val sb = StringBuilder()
            for (i in 0 until contentArray.length()) {
                val item = contentArray.optJSONObject(i)
                if (item?.optString("type") == "text") {
                    sb.append(item.optString("text"))
                }
            }
            val contentText = SecretRedactor.redact(sb.toString())
            McpToolCallResult(
                isError = isError,
                content = contentText,
                diagnostic = if (isError) contentText else null,
            )
        }.getOrElse { ex ->
            McpToolCallResult(
                isError = true,
                diagnostic = "Failed to parse MCP response: ${ex.message}",
            )
        }
    }
}
