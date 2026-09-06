package com.jarves.mh.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class CapabilityScope(val identifier: String, val label: String) {
    FILESYSTEM_READ("filesystem.read", "Read Files"),
    FILESYSTEM_WRITE("filesystem.write", "Write Files"),
    FILESYSTEM_DELETE("filesystem.delete", "Delete Files"),
    PROCESS_EXECUTE("process.execute", "Run Commands"),
    NETWORK_ACCESS("network.access", "Network Access"),
    PACKAGE_INSTALL("package.install", "Install Packages"),
    PROJECT_MODIFY("project.modify", "Modify Project Configuration"),
    SECRETS_USE("secrets.use", "Use Saved Secrets/Keys"),
    EXTERNAL_TOOL_EXECUTE("external_tool.execute", "Execute External Tool");

    companion object {
        fun fromIdentifier(identifier: String): CapabilityScope? =
            entries.firstOrNull { it.identifier.equals(identifier, ignoreCase = true) }
    }
}

enum class PermissionDecision(val identifier: String, val label: String) {
    ALLOW_ONCE("allow_once", "Allow once"),
    ALLOW_PROJECT("allow_project", "Allow for this project"),
    ALLOW_ALWAYS("allow_always", "Always allow"),
    DENY_ONCE("deny_once", "Deny once");

    companion object {
        fun fromIdentifier(identifier: String): PermissionDecision? =
            entries.firstOrNull { it.identifier.equals(identifier, ignoreCase = true) }
    }
}

data class PermissionRequest(
    val requestId: String = "p_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}",
    val sessionId: String,
    val taskId: String,
    val projectId: String,
    val capability: CapabilityScope,
    val explanation: String,
    val command: String? = null,
    val affectedPaths: List<String> = emptyList(),
    val riskLevel: RiskLevel = RiskLevel.REVIEW,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("requestId", requestId)
        put("sessionId", sessionId)
        put("taskId", taskId)
        put("projectId", projectId)
        put("capability", capability.identifier)
        put("explanation", explanation)
        command?.let { put("command", it) }
        val pathsArr = JSONArray()
        affectedPaths.forEach { pathsArr.put(it) }
        put("affectedPaths", pathsArr)
        put("riskLevel", riskLevel.name)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(json: JSONObject): PermissionRequest {
            val paths = mutableListOf<String>()
            json.optJSONArray("affectedPaths")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val p = arr.optString(i)
                    if (p.isNotBlank()) paths.add(p)
                }
            }
            return PermissionRequest(
                requestId = json.optString("requestId", "p_${System.currentTimeMillis()}"),
                sessionId = json.optString("sessionId", ""),
                taskId = json.optString("taskId", ""),
                projectId = json.optString("projectId", ""),
                capability = CapabilityScope.fromIdentifier(json.optString("capability")) ?: CapabilityScope.PROCESS_EXECUTE,
                explanation = json.optString("explanation", ""),
                command = json.optString("command").takeIf { it.isNotBlank() },
                affectedPaths = paths,
                riskLevel = runCatching { RiskLevel.valueOf(json.optString("riskLevel")) }.getOrDefault(RiskLevel.REVIEW),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
            )
        }
    }
}

data class PermissionGrant(
    val id: String = UUID.randomUUID().toString(),
    val capability: CapabilityScope,
    val projectId: String? = null, // null means global
    val pathPattern: String? = null, // prefix/glob restriction
    val grantedAt: Long = System.currentTimeMillis(),
) {
    fun matches(scope: CapabilityScope, targetProjectId: String?, path: String?): Boolean {
        if (this.capability != scope) return false
        if (this.projectId != null && this.projectId != targetProjectId) return false
        if (this.pathPattern != null && path != null) {
            val normalizedPath = path.replace('\\', '/')
            val normalizedPattern = this.pathPattern.replace('\\', '/')
            if (!normalizedPath.startsWith(normalizedPattern) && !normalizedPath.contains(normalizedPattern)) {
                return false
            }
        }
        return true
    }
}
