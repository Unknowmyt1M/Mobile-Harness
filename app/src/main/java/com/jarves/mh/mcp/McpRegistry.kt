package com.jarves.mh.mcp

import com.jarves.mh.model.RiskLevel
import com.jarves.mh.security.SecretRedactor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class McpRegistry {
    private val servers = ConcurrentHashMap<String, McpServerConfig>()

    fun registerServer(server: McpServerConfig) {
        val sanitizedEnv = server.env.mapValues { (_, v) -> SecretRedactor.redact(v) }
        val sanitizedArgs = server.args.map { SecretRedactor.redact(it) }
        val sanitized = server.copy(env = sanitizedEnv, args = sanitizedArgs)
        servers[server.id] = sanitized
    }

    fun unregisterServer(serverId: String): McpServerConfig? {
        return servers.remove(serverId)
    }

    fun getServer(serverId: String): McpServerConfig? {
        return servers[serverId]
    }

    fun listServers(): List<McpServerConfig> {
        return servers.values.toList()
    }

    fun evaluateToolSafety(toolName: String): RiskLevel {
        val lower = toolName.lowercase()
        return when {
            lower.contains("drop_database") ||
                lower.contains("rm_rf") ||
                lower.contains("format_disk") ||
                lower.contains("exec_root") -> RiskLevel.BLOCKED

            lower.startsWith("write_") ||
                lower.startsWith("edit_") ||
                lower.startsWith("create_") ||
                lower.startsWith("delete_") ||
                lower.startsWith("update_") ||
                lower.startsWith("execute_") ||
                lower.startsWith("run_") -> RiskLevel.REVIEW

            lower.startsWith("list_") ||
                lower.startsWith("get_") ||
                lower.startsWith("read_") ||
                lower.startsWith("search_") ||
                lower.startsWith("find_") ||
                lower.startsWith("inspect_") ||
                lower.startsWith("query_") -> RiskLevel.SAFE

            else -> RiskLevel.REVIEW
        }
    }

    fun saveToFile(file: File) {
        file.parentFile?.mkdirs()
        val array = JSONArray()
        servers.values.forEach { server ->
            val obj = JSONObject().apply {
                put("id", server.id)
                put("name", server.name)
                put("transport", server.transport.name)
                put("command", server.command)
                put("args", JSONArray(server.args))
                val envObj = JSONObject()
                server.env.forEach { (k, v) -> envObj.put(k, v) }
                put("env", envObj)
                put("enabled", server.enabled)
                put("allowedTools", JSONArray(server.allowedTools))
            }
            array.put(obj)
        }
        file.writeText(array.toString(2))
    }

    fun loadFromFile(file: File) {
        if (!file.isFile) return
        runCatching {
            val array = JSONArray(file.readText())
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val argsList = mutableListOf<String>()
                val argsArr = obj.optJSONArray("args")
                if (argsArr != null) {
                    for (j in 0 until argsArr.length()) argsList.add(argsArr.getString(j))
                }
                val envMap = mutableMapOf<String, String>()
                val envObj = obj.optJSONObject("env")
                envObj?.keys()?.forEach { k -> envMap[k] = envObj.getString(k) }

                val allowedList = mutableListOf<String>()
                val allowedArr = obj.optJSONArray("allowedTools")
                if (allowedArr != null) {
                    for (j in 0 until allowedArr.length()) allowedList.add(allowedArr.getString(j))
                }

                val config = McpServerConfig(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    transport = McpTransport.valueOf(obj.optString("transport", "STDIO")),
                    command = obj.optString("command", ""),
                    args = argsList,
                    env = envMap,
                    enabled = obj.optBoolean("enabled", true),
                    allowedTools = allowedList,
                )
                registerServer(config)
            }
        }
    }
}
