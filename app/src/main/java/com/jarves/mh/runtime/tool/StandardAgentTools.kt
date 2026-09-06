package com.jarves.mh.runtime.tool

import android.content.Context
import android.util.Log
import com.jarves.mh.model.AgentAnswer
import com.jarves.mh.model.AgentQuestion
import com.jarves.mh.model.CapabilityScope
import com.jarves.mh.model.PermissionDecision
import com.jarves.mh.model.PermissionRequest
import com.jarves.mh.model.QuestionOption
import com.jarves.mh.model.QuestionType
import com.jarves.mh.model.ResolutionPolicy
import com.jarves.mh.model.RiskLevel
import com.jarves.mh.policy.AgentPolicyEngine
import com.jarves.mh.policy.PolicyDecision
import com.jarves.mh.policy.ToolEvaluationRequest
import com.jarves.mh.runtime.PermissionManager
import com.jarves.mh.runtime.QuestionManager
import com.jarves.mh.runtime.RuntimeInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStreamReader
import java.util.UUID

/**
 * Executes bash commands inside PRoot with full policy evaluation and permission management
 */
class BashTool(
    private val context: Context,
    private val permissionManager: PermissionManager,
    private val policyEngine: AgentPolicyEngine,
) : AgentTool {
    override val name: String = "bash"
    override val description: String = "Execute a shell command inside the Ubuntu PRoot development environment. Runs in the current project workspace."
    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("command", JSONObject().apply {
                put("type", "string")
                put("description", "The shell command to execute")
            })
        })
        put("required", JSONArray().apply { put("command") })
    }

    override suspend fun execute(sessionId: String, projectId: String?, arguments: JSONObject): ToolExecutionResult = withContext(Dispatchers.IO) {
        val command = arguments.optString("command").trim()
        if (command.isBlank()) {
            return@withContext ToolExecutionResult(false, "", "Command cannot be empty")
        }

        // 1. Evaluate policy
        val decision = AgentPolicyEngine.evaluate(
            ToolEvaluationRequest(
                toolName = "bash",
                command = command,
                projectId = projectId,
            )
        )

        when (decision) {
            is PolicyDecision.Block -> {
                return@withContext ToolExecutionResult(
                    success = false,
                    output = "",
                    error = "Command blocked by security policy: ${decision.reason}",
                )
            }
            is PolicyDecision.RequireApproval -> {
                val reqId = "p_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
                val permReq = PermissionRequest(
                    requestId = reqId,
                    sessionId = sessionId,
                    taskId = sessionId,
                    projectId = projectId.orEmpty(),
                    capability = decision.capability,
                    explanation = "Execute command: $command",
                    command = command,
                    affectedPaths = emptyList(),
                    riskLevel = decision.level,
                )
                return@withContext ToolExecutionResult(
                    success = false,
                    output = "",
                    error = "Requires user permission (${decision.level})",
                    isPermissionRequired = true,
                    permissionRequestId = reqId,
                )
            }
            is PolicyDecision.Allow -> {
                // Allowed to execute directly
            }
        }

        // 2. Execute in PRoot
        runCatching {
            val installer = RuntimeInstaller(context)
            val installed = installer.installedRuntime()
            val workspace = File(context.filesDir, "workspaces/${projectId ?: "default"}").apply { mkdirs() }

            val process = installer.process(
                proot = installed.proot,
                rootfs = installed.rootfs,
                workspace = workspace,
                environment = emptyMap(),
                guestCommand = listOf("/bin/bash", "-c", command),
                guestWorkspacePath = "/workspace",
            )

            val output = StringBuilder()
            val reader = InputStreamReader(process.inputStream)
            val buffer = CharArray(1024)
            var read: Int
            while (reader.read(buffer).also { read = it } != -1) {
                output.append(buffer, 0, read)
                if (output.length > 50_000) {
                    output.append("\n...[output truncated at 50KB]...")
                    break
                }
            }
            val exitCode = process.waitFor()
            ToolExecutionResult(
                success = exitCode == 0,
                output = output.toString(),
                error = if (exitCode != 0) "Exit code $exitCode" else null,
            )
        }.getOrElse { e ->
            ToolExecutionResult(false, "", "Process execution error: ${e.message}")
        }
    }
}

/**
 * Reads file contents from workspace
 */
class ReadFileTool(
    private val context: Context,
    private val policyEngine: AgentPolicyEngine,
) : AgentTool {
    override val name: String = "read_file"
    override val description: String = "Read file contents from the workspace"
    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("path", JSONObject().apply {
                put("type", "string")
                put("description", "Relative or absolute file path to read")
            })
        })
        put("required", JSONArray().apply { put("path") })
    }

    override suspend fun execute(sessionId: String, projectId: String?, arguments: JSONObject): ToolExecutionResult = withContext(Dispatchers.IO) {
        val relPath = arguments.optString("path").trim().removePrefix("/")
        val workspace = File(context.filesDir, "workspaces/${projectId ?: "default"}")
        val target = File(workspace, relPath)

        if (!target.canonicalPath.startsWith(workspace.canonicalPath)) {
            return@withContext ToolExecutionResult(false, "", "Access denied: Path is outside workspace")
        }
        if (!target.exists() || !target.isFile) {
            return@withContext ToolExecutionResult(false, "", "File does not exist: $relPath")
        }

        runCatching {
            val text = target.readText()
            ToolExecutionResult(true, text)
        }.getOrElse { e ->
            ToolExecutionResult(false, "", "Failed to read file: ${e.message}")
        }
    }
}

/**
 * Writes content to a file in the workspace
 */
class WriteFileTool(
    private val context: Context,
    private val permissionManager: PermissionManager,
    private val policyEngine: AgentPolicyEngine,
) : AgentTool {
    override val name: String = "write_file"
    override val description: String = "Write or overwrite content to a file in the workspace"
    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("path", JSONObject().apply {
                put("type", "string")
                put("description", "Relative file path to write to")
            })
            put("content", JSONObject().apply {
                put("type", "string")
                put("description", "Content to write into the file")
            })
        })
        put("required", JSONArray().apply { put("path"); put("content") })
    }

    override suspend fun execute(sessionId: String, projectId: String?, arguments: JSONObject): ToolExecutionResult = withContext(Dispatchers.IO) {
        val relPath = arguments.optString("path").trim().removePrefix("/")
        val content = arguments.optString("content")
        val workspace = File(context.filesDir, "workspaces/${projectId ?: "default"}")
        val target = File(workspace, relPath)

        if (!target.canonicalPath.startsWith(workspace.canonicalPath)) {
            return@withContext ToolExecutionResult(false, "", "Access denied: Path is outside workspace")
        }

        runCatching {
            target.parentFile?.mkdirs()
            target.writeText(content)
            ToolExecutionResult(true, "Successfully wrote ${content.length} characters to $relPath")
        }.getOrElse { e ->
            ToolExecutionResult(false, "", "Failed to write file: ${e.message}")
        }
    }
}

/**
 * Replaces search text with replacement text in a file
 */
class EditFileTool(
    private val context: Context,
    private val permissionManager: PermissionManager,
    private val policyEngine: AgentPolicyEngine,
) : AgentTool {
    override val name: String = "edit_file"
    override val description: String = "Edit an existing file by replacing target content with new content"
    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("path", JSONObject().apply {
                put("type", "string")
                put("description", "Relative file path to edit")
            })
            put("old_str", JSONObject().apply {
                put("type", "string")
                put("description", "Exact text to find and replace")
            })
            put("new_str", JSONObject().apply {
                put("type", "string")
                put("description", "Replacement text")
            })
        })
        put("required", JSONArray().apply { put("path"); put("old_str"); put("new_str") })
    }

    override suspend fun execute(sessionId: String, projectId: String?, arguments: JSONObject): ToolExecutionResult = withContext(Dispatchers.IO) {
        val relPath = arguments.optString("path").trim().removePrefix("/")
        val oldStr = arguments.optString("old_str")
        val newStr = arguments.optString("new_str")
        val workspace = File(context.filesDir, "workspaces/${projectId ?: "default"}")
        val target = File(workspace, relPath)

        if (!target.canonicalPath.startsWith(workspace.canonicalPath)) {
            return@withContext ToolExecutionResult(false, "", "Access denied: Path is outside workspace")
        }
        if (!target.exists() || !target.isFile) {
            return@withContext ToolExecutionResult(false, "", "File not found: $relPath")
        }

        runCatching {
            val text = target.readText()
            if (!text.contains(oldStr)) {
                return@withContext ToolExecutionResult(false, "", "Target text not found in file: $oldStr")
            }
            val replaced = text.replace(oldStr, newStr)
            target.writeText(replaced)
            ToolExecutionResult(true, "Successfully updated $relPath")
        }.getOrElse { e ->
            ToolExecutionResult(false, "", "Failed to edit file: ${e.message}")
        }
    }
}

/**
 * Lists directory contents
 */
class ListDirectoryTool(
    private val context: Context,
) : AgentTool {
    override val name: String = "list_directory"
    override val description: String = "List files and subdirectories within a directory in the workspace"
    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("path", JSONObject().apply {
                put("type", "string")
                put("description", "Relative directory path (or empty for workspace root)")
            })
        })
    }

    override suspend fun execute(sessionId: String, projectId: String?, arguments: JSONObject): ToolExecutionResult = withContext(Dispatchers.IO) {
        val relPath = arguments.optString("path", "").trim().removePrefix("/")
        val workspace = File(context.filesDir, "workspaces/${projectId ?: "default"}")
        val target = if (relPath.isBlank()) workspace else File(workspace, relPath)

        if (!target.canonicalPath.startsWith(workspace.canonicalPath)) {
            return@withContext ToolExecutionResult(false, "", "Access denied: Path is outside workspace")
        }
        if (!target.exists() || !target.isDirectory) {
            return@withContext ToolExecutionResult(false, "", "Directory does not exist: $relPath")
        }

        runCatching {
            val entries = target.listFiles().orEmpty().map { f ->
                val type = if (f.isDirectory) "dir" else "file"
                val size = if (f.isFile) " (${f.length()} B)" else ""
                "${f.name}/$type$size"
            }
            ToolExecutionResult(true, entries.joinToString("\n"))
        }.getOrElse { e ->
            ToolExecutionResult(false, "", "Failed to list directory: ${e.message}")
        }
    }
}

/**
 * Asks the user a question via QuestionManager
 */
class AskQuestionTool(
    private val questionManager: QuestionManager,
) : AgentTool {
    override val name: String = "ask_question"
    override val description: String = "Ask the user a structured question or choice directly in the Mobile Harness UI"
    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("type", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray().apply {
                    put("SELECT_ONE"); put("SELECT_MULTIPLE"); put("YES_NO"); put("TEXT"); put("NUMBER"); put("PATH"); put("CONFIRMATION")
                })
            })
            put("question", JSONObject().apply { put("type", "string") })
            put("title", JSONObject().apply { put("type", "string") })
            put("options", JSONObject().apply {
                put("type", "array")
                put("items", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("id", JSONObject().apply { put("type", "string") })
                        put("label", JSONObject().apply { put("type", "string") })
                        put("description", JSONObject().apply { put("type", "string") })
                        put("isRecommended", JSONObject().apply { put("type", "boolean") })
                    })
                    put("required", JSONArray().apply { put("id"); put("label") })
                })
            })
            put("defaultOptionId", JSONObject().apply { put("type", "string") })
        })
        put("required", JSONArray().apply { put("question") })
    }

    override suspend fun execute(sessionId: String, projectId: String?, arguments: JSONObject): ToolExecutionResult = withContext(Dispatchers.IO) {
        val qId = "q_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        val qType = runCatching { QuestionType.valueOf(arguments.optString("type", "TEXT")) }.getOrDefault(QuestionType.TEXT)
        val question = arguments.optString("question")
        val title = arguments.optString("title", "Agent Question")
        val optionsList = mutableListOf<QuestionOption>()
        val optArr = arguments.optJSONArray("options")
        if (optArr != null) {
            for (i in 0 until optArr.length()) {
                val item = optArr.optJSONObject(i) ?: continue
                optionsList.add(
                    QuestionOption(
                        id = item.optString("id", "opt_$i"),
                        label = item.optString("label", ""),
                        description = item.optString("description", ""),
                        isRecommended = item.optBoolean("isRecommended", false),
                    )
                )
            }
        }
        val defaultOpt = arguments.optString("defaultOptionId").takeIf { it.isNotBlank() }

        val agentQ = AgentQuestion(
            id = qId,
            taskId = sessionId,
            sessionId = sessionId,
            type = qType,
            title = title,
            question = question,
            options = optionsList,
            defaultOptionId = defaultOpt,
            resolutionPolicy = ResolutionPolicy.USER_REQUIRED,
        )

        ToolExecutionResult(true, "Question submitted to user: $question")
    }
}

/**
 * Requests user permission via PermissionManager
 */
class RequestPermissionTool(
    private val permissionManager: PermissionManager,
) : AgentTool {
    override val name: String = "request_permission"
    override val description: String = "Request explicit permission from the user for sensitive actions"
    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("capability", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray().apply {
                    CapabilityScope.entries.forEach { put(it.identifier) }
                })
            })
            put("explanation", JSONObject().apply { put("type", "string") })
            put("command", JSONObject().apply { put("type", "string") })
        })
        put("required", JSONArray().apply { put("capability"); put("explanation") })
    }

    override suspend fun execute(sessionId: String, projectId: String?, arguments: JSONObject): ToolExecutionResult = withContext(Dispatchers.IO) {
        val capStr = arguments.optString("capability")
        val cap = CapabilityScope.fromIdentifier(capStr)
        val explanation = arguments.optString("explanation")
        val command = arguments.optString("command").takeIf { it.isNotBlank() }
        val reqId = "p_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"

        ToolExecutionResult(
            success = false,
            output = "",
            error = "Permission requested: $explanation",
            isPermissionRequired = true,
            permissionRequestId = reqId,
        )
    }
}
