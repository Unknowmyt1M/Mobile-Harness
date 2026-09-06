package com.jarves.mh.runtime

import android.util.Log
import com.jarves.mh.data.db.AgentTaskStore
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.ToolRequest
import com.jarves.mh.policy.AgentPolicyEngine
import com.jarves.mh.policy.PolicyDecision
import com.jarves.mh.policy.ToolEvaluationRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

class AgentPermissionBroker(
    private val bridgeDir: File,
    private val taskStore: AgentTaskStore,
    private val onEvent: suspend (RuntimeEvent) -> Unit,
) {
    private val pendingResponses = ConcurrentHashMap<String, File>()

    suspend fun watchRequests(sessionId: String, taskId: String) {
        bridgeDir.mkdirs()
        while (coroutineContext.isActive) {
            val requests = bridgeDir.listFiles { file -> file.isFile && file.name.endsWith(".request") }.orEmpty()
            for (file in requests) {
                val approvalId = file.name.removeSuffix(".request")
                val responseFile = File(file.parentFile, "$approvalId.response")

                runCatching {
                    val content = file.readText()
                    if (content.isBlank()) return@runCatching

                    val json = JSONObject(content)
                    val toolName = json.optString("tool_name", "Tool")
                    val input = json.optJSONObject("tool_input") ?: JSONObject()
                    val command = input.optString("command").ifBlank { null }
                    val paths = listOf("file_path", "path", "notebook_path")
                        .mapNotNull { key -> input.optString(key).takeIf(String::isNotBlank) }
                    val explanation = input.optString("description")
                        .ifBlank { command.orEmpty() }
                        .ifBlank { "$toolName execution" }

                    val decision = AgentPolicyEngine.evaluate(
                        ToolEvaluationRequest(
                            toolName = toolName,
                            command = command,
                            paths = paths,
                        ),
                    )

                    when (decision) {
                        is PolicyDecision.Allow -> {
                            Log.d("AgentPermissionBroker", "SAFE tool $toolName allowed automatically ($approvalId)")
                            responseFile.writeText("allow")
                            file.delete()
                        }
                        is PolicyDecision.Block -> {
                            Log.w("AgentPermissionBroker", "BLOCKED tool $toolName: ${decision.reason} ($approvalId)")
                            responseFile.writeText("deny")
                            file.delete()
                            onEvent(RuntimeEvent.RuntimeLog(sessionId, "Security policy blocked action", decision.reason))
                        }
                        is PolicyDecision.RequireApproval -> {
                            Log.i("AgentPermissionBroker", "REVIEW/HIGH tool $toolName (${decision.level}) waiting for user approval ($approvalId)")
                            val request = ToolRequest(
                                approvalId = approvalId,
                                sessionId = sessionId,
                                toolName = toolName,
                                explanation = explanation,
                                affectedPaths = paths,
                                commandPreview = command,
                                risk = decision.level,
                            )
                            pendingResponses[approvalId] = responseFile
                            taskStore.saveApproval(request, taskId)
                            onEvent(RuntimeEvent.ToolRequested(sessionId, request))
                            file.delete()
                        }
                    }
                }.onFailure { error ->
                    Log.e("AgentPermissionBroker", "Error handling permission request $approvalId", error)
                    responseFile.writeText("deny")
                    file.delete()
                }
            }
            delay(60)
        }
    }

    suspend fun respond(approvalId: String, approved: Boolean, sessionId: String) {
        val responseFile = pendingResponses.remove(approvalId)
            ?: File(bridgeDir, "$approvalId.response")

        runCatching {
            responseFile.writeText(if (approved) "allow" else "deny")
            taskStore.removeApproval(approvalId)
            if (approved) {
                onEvent(RuntimeEvent.ToolApproved(sessionId, approvalId))
            } else {
                onEvent(RuntimeEvent.ToolRejected(sessionId, approvalId))
            }
        }
    }

    suspend fun cancelAllPending(sessionId: String) {
        pendingResponses.forEach { (approvalId, file) ->
            runCatching { file.writeText("deny") }
        }
        pendingResponses.clear()
        taskStore.clearApprovalsForSession(sessionId)
    }
}
