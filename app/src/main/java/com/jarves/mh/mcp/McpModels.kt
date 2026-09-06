package com.jarves.mh.mcp

import java.util.UUID

enum class McpTransport {
    STDIO,
    SSE,
}

data class McpServerConfig(
    val id: String,
    val name: String,
    val transport: McpTransport = McpTransport.STDIO,
    val command: String = "",
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val allowedTools: List<String> = emptyList(),
)

data class McpToolDefinition(
    val name: String,
    val description: String = "",
    val inputSchema: String = "{}",
)

data class McpToolCallRequest(
    val serverId: String,
    val toolName: String,
    val arguments: Map<String, Any?> = emptyMap(),
    val requestId: String = UUID.randomUUID().toString(),
)

data class McpToolCallResult(
    val isError: Boolean = false,
    val content: String = "",
    val diagnostic: String? = null,
)
