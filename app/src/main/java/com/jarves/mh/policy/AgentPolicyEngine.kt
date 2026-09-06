package com.jarves.mh.policy

import com.jarves.mh.model.CapabilityScope
import com.jarves.mh.model.PermissionGrant
import com.jarves.mh.model.RiskLevel

sealed interface PolicyDecision {
    data object Allow : PolicyDecision
    data class RequireApproval(
        val level: RiskLevel,
        val capability: CapabilityScope,
        val reason: String,
    ) : PolicyDecision
    data class Block(val reason: String) : PolicyDecision
}

data class ToolEvaluationRequest(
    val toolName: String,
    val command: String? = null,
    val paths: List<String> = emptyList(),
    val workspaceRoot: String = "/workspace",
    val projectId: String? = null,
    val explicitCapability: CapabilityScope? = null,
    val activeGrants: List<PermissionGrant> = emptyList(),
)

object AgentPolicyEngine {

    private val HIGH_RISK_COMMANDS = listOf(
        "rm -rf", "rm -r", "rm ", "unlink", "git reset --hard", "git clean",
        "git push --force", "git push -f", "sudo", "mkfs", "dd if=",
        "chmod -R 777", "chmod 777", "chown", "reboot", "shutdown",
    )

    private val PACKAGE_INSTALL_PREFIXES = listOf(
        "npm install", "npm i ", "yarn add", "pnpm add", "pip install", "pip3 install", "apt-get install", "apt install", "cargo install"
    )

    private val BLOCKED_PATTERNS = listOf(
        ":(){ :|:& };:", // fork bomb
        "/data/data/com.jarves.mh/shared_prefs",
        "/data/data/com.jarves.mh/databases",
        "pocket_secrets",
    )

    private val SAFE_COMMAND_PREFIXES = listOf(
        "ls", "pwd", "cat ", "head ", "tail ", "echo ", "grep ", "find ",
        "which ", "whereis ", "file ", "stat ", "wc ", "git status",
        "git diff", "git log", "git show", "git branch", "node -v", "node --version",
        "npm -v", "npm --version", "python3 --version", "python --version",
        "gcc --version", "g++ --version", "java -version", "java --version",
    )

    fun evaluate(request: ToolEvaluationRequest): PolicyDecision {
        val tool = request.toolName
        val command = request.command?.trim().orEmpty()
        val normalizedCmd = command.lowercase()

        // 1. Check for blocked malicious patterns
        for (blocked in BLOCKED_PATTERNS) {
            if (normalizedCmd.contains(blocked)) {
                return PolicyDecision.Block("Operation contains prohibited pattern: $blocked")
            }
        }

        // 2. Check path escaping workspace
        for (path in request.paths) {
            val normalizedPath = path.replace('\\', '/')
            if (normalizedPath.contains("..") || normalizedPath.startsWith("/data/data/com.jarves.mh/shared_prefs")) {
                return PolicyDecision.Block("Path escapes workspace boundary: $path")
            }
        }

        // 3. Determine capability scope
        val capability = request.explicitCapability ?: inferCapability(tool, command)

        // 4. Read-only tools are SAFE (unless targeting sensitive files)
        if (capability == CapabilityScope.FILESYSTEM_READ) {
            return PolicyDecision.Allow
        }

        // 5. Check against active durable grants (Multi-dimensional: scope + project + path)
        val matchingGrant = request.activeGrants.firstOrNull { grant ->
            grant.matches(
                scope = capability,
                targetProjectId = request.projectId,
                path = request.paths.firstOrNull(),
            )
        }
        if (matchingGrant != null) {
            // Even with a grant, deleting files with rm -rf / or root commands remains HIGH risk / blocked
            if (capability != CapabilityScope.FILESYSTEM_DELETE && !normalizedCmd.contains("rm -rf /")) {
                return PolicyDecision.Allow
            }
        }

        // 6. Check HIGH risk destructive commands
        if (tool == "Bash" || capability == CapabilityScope.PROCESS_EXECUTE || capability == CapabilityScope.FILESYSTEM_DELETE) {
            for (danger in HIGH_RISK_COMMANDS) {
                if (normalizedCmd.contains(danger)) {
                    val finalScope = if (danger.contains("rm")) CapabilityScope.FILESYSTEM_DELETE else capability
                    return PolicyDecision.RequireApproval(
                        level = RiskLevel.HIGH,
                        capability = finalScope,
                        reason = "Command contains potentially destructive operation: $danger",
                    )
                }
            }

            // Check package manager commands
            if (PACKAGE_INSTALL_PREFIXES.any { normalizedCmd.startsWith(it) }) {
                return PolicyDecision.RequireApproval(
                    level = RiskLevel.REVIEW,
                    capability = CapabilityScope.PACKAGE_INSTALL,
                    reason = "Package installation: $command",
                )
            }

            // Check safe read/inspection commands
            val isSafe = SAFE_COMMAND_PREFIXES.any { prefix ->
                normalizedCmd == prefix.trim() || normalizedCmd.startsWith(prefix)
            }
            if (isSafe && !command.contains('>') && !command.contains('|') && !command.contains('&') && !command.contains(';')) {
                return PolicyDecision.Allow
            }

            return PolicyDecision.RequireApproval(
                level = RiskLevel.REVIEW,
                capability = CapabilityScope.PROCESS_EXECUTE,
                reason = "Bash command execution: $command",
            )
        }

        // 7. File write / modifications
        if (capability == CapabilityScope.FILESYSTEM_WRITE || tool in listOf("Write", "Edit", "NotebookEdit")) {
            return PolicyDecision.RequireApproval(
                level = RiskLevel.REVIEW,
                capability = CapabilityScope.FILESYSTEM_WRITE,
                reason = "File modification across ${request.paths.joinToString(", ").ifBlank { "project files" }}",
            )
        }

        return PolicyDecision.RequireApproval(
            level = RiskLevel.REVIEW,
            capability = capability,
            reason = "Running $tool",
        )
    }

    private fun inferCapability(tool: String, command: String): CapabilityScope {
        val normalized = command.lowercase().trim()
        return when {
            tool in listOf("Read", "Glob", "Grep") -> CapabilityScope.FILESYSTEM_READ
            tool in listOf("Write", "Edit", "NotebookEdit") -> CapabilityScope.FILESYSTEM_WRITE
            normalized.startsWith("rm ") || normalized.startsWith("unlink ") || normalized.contains("rm -rf") -> CapabilityScope.FILESYSTEM_DELETE
            PACKAGE_INSTALL_PREFIXES.any { normalized.startsWith(it) } -> CapabilityScope.PACKAGE_INSTALL
            tool == "Bash" -> CapabilityScope.PROCESS_EXECUTE
            else -> CapabilityScope.EXTERNAL_TOOL_EXECUTE
        }
    }

    fun classifyRisk(
        toolName: String,
        command: String? = null,
        paths: List<String> = emptyList(),
        projectId: String? = null,
        activeGrants: List<PermissionGrant> = emptyList(),
    ): RiskLevel {
        return when (val decision = evaluate(ToolEvaluationRequest(toolName, command, paths, projectId = projectId, activeGrants = activeGrants))) {
            is PolicyDecision.Allow -> RiskLevel.SAFE
            is PolicyDecision.RequireApproval -> decision.level
            is PolicyDecision.Block -> RiskLevel.BLOCKED
        }
    }
}
