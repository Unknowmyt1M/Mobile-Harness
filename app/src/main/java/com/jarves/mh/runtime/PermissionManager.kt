package com.jarves.mh.runtime

import android.util.Log
import com.jarves.mh.data.db.AgentTaskStore
import com.jarves.mh.model.CapabilityScope
import com.jarves.mh.model.PermissionDecision
import com.jarves.mh.model.PermissionGrant
import com.jarves.mh.model.PermissionRequest
import com.jarves.mh.model.RiskLevel
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.ToolRequest
import com.jarves.mh.policy.AgentPolicyEngine
import com.jarves.mh.policy.PolicyDecision
import com.jarves.mh.policy.ToolEvaluationRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

class PermissionManager(
    val bridgeDir: File,
    private val taskStore: AgentTaskStore,
    private val onEvent: suspend (RuntimeEvent) -> Unit,
) {
    private val pendingResponses = ConcurrentHashMap<String, File>()
    private val pendingRequests = ConcurrentHashMap<String, PermissionRequest>()

    suspend fun watchRequests(sessionId: String, taskId: String, projectId: String = "") {
        bridgeDir.mkdirs()
        while (coroutineContext.isActive) {
            val requests = bridgeDir.listFiles { file ->
                file.isFile && file.name.endsWith(".request") && !file.name.startsWith("q_")
            }.orEmpty()

            for (file in requests) {
                val approvalId = file.name.removeSuffix(".request")
                val responseFile = File(file.parentFile, "$approvalId.response")

                runCatching {
                    val content = file.readText()
                    if (content.isBlank()) return@runCatching

                    val json = JSONObject(content)
                    val toolName = json.optString("tool_name", json.optString("tool", "Tool"))
                    val input = json.optJSONObject("tool_input") ?: json.optJSONObject("input") ?: JSONObject()
                    val command = input.optString("command").ifBlank { json.optString("command").ifBlank { null } }
                    val paths = listOf("file_path", "path", "notebook_path")
                        .mapNotNull { key -> input.optString(key).takeIf(String::isNotBlank) }
                        .ifEmpty {
                            val p = json.optString("path")
                            if (p.isNotBlank()) listOf(p) else emptyList()
                        }
                    val explanation = input.optString("description")
                        .ifBlank { json.optString("explanation") }
                        .ifBlank { command.orEmpty() }
                        .ifBlank { "$toolName execution" }

                    val explicitCap = json.optString("capability").takeIf(String::isNotBlank)?.let {
                        CapabilityScope.fromIdentifier(it)
                    }

                    val activeGrants = taskStore.getPermissionGrants()

                    val decision = AgentPolicyEngine.evaluate(
                        ToolEvaluationRequest(
                            toolName = toolName,
                            command = command,
                            paths = paths,
                            projectId = projectId,
                            explicitCapability = explicitCap,
                            activeGrants = activeGrants,
                        ),
                    )

                    when (decision) {
                        is PolicyDecision.Allow -> {
                            Log.d("PermissionManager", "SAFE action $toolName allowed automatically ($approvalId)")
                            responseFile.writeText("allow")
                            file.delete()
                        }
                        is PolicyDecision.Block -> {
                            Log.w("PermissionManager", "BLOCKED action $toolName: ${decision.reason} ($approvalId)")
                            responseFile.writeText("deny")
                            file.delete()
                            onEvent(RuntimeEvent.RuntimeLog(sessionId, "Security policy blocked action", decision.reason))
                        }
                        is PolicyDecision.RequireApproval -> {
                            Log.i("PermissionManager", "REVIEW/HIGH action $toolName (${decision.level}) waiting for user decision ($approvalId)")
                            val permReq = PermissionRequest(
                                requestId = approvalId,
                                sessionId = sessionId,
                                taskId = taskId,
                                projectId = projectId,
                                capability = decision.capability,
                                explanation = explanation,
                                command = command,
                                affectedPaths = paths,
                                riskLevel = decision.level,
                            )
                            val legacyToolRequest = ToolRequest(
                                approvalId = approvalId,
                                sessionId = sessionId,
                                toolName = toolName,
                                explanation = explanation,
                                affectedPaths = paths,
                                commandPreview = command,
                                risk = decision.level,
                                capability = decision.capability,
                                projectId = projectId,
                            )
                            pendingResponses[approvalId] = responseFile
                            pendingRequests[approvalId] = permReq
                            taskStore.saveApproval(legacyToolRequest, taskId)
                            onEvent(RuntimeEvent.PermissionRequested(sessionId, permReq))
                            onEvent(RuntimeEvent.ToolRequested(sessionId, legacyToolRequest))
                            file.delete()
                        }
                    }
                }.onFailure { error ->
                    Log.e("PermissionManager", "Error handling permission request $approvalId", error)
                    responseFile.writeText("deny")
                    file.delete()
                }
            }
            delay(60)
        }
    }

    suspend fun respond(approvalId: String, decision: PermissionDecision, sessionId: String) = withContext(Dispatchers.IO) {
        val responseFile = pendingResponses.remove(approvalId)
            ?: File(bridgeDir, "$approvalId.response")
        val req = pendingRequests.remove(approvalId)

        val approved = decision != PermissionDecision.DENY_ONCE

        // Durable grant persistence for ALLOW_PROJECT or ALLOW_ALWAYS
        if (req != null) {
            when (decision) {
                PermissionDecision.ALLOW_PROJECT -> {
                    taskStore.savePermissionGrant(
                        PermissionGrant(
                            capability = req.capability,
                            projectId = req.projectId,
                            pathPattern = req.affectedPaths.firstOrNull(),
                        ),
                    )
                }
                PermissionDecision.ALLOW_ALWAYS -> {
                    taskStore.savePermissionGrant(
                        PermissionGrant(
                            capability = req.capability,
                            projectId = null, // global grant
                            pathPattern = null,
                        ),
                    )
                }
                PermissionDecision.ALLOW_ONCE,
                PermissionDecision.DENY_ONCE -> {
                    // No persistent grant created
                }
            }
        }

        runCatching {
            responseFile.writeText(if (approved) "allow" else "deny")
            taskStore.removeApproval(approvalId)
            onEvent(RuntimeEvent.PermissionResolved(sessionId, approvalId, decision))
            if (approved) {
                onEvent(RuntimeEvent.ToolApproved(sessionId, approvalId))
            } else {
                onEvent(RuntimeEvent.ToolRejected(sessionId, approvalId))
            }
        }
    }

    suspend fun cancelAllPending(sessionId: String) = withContext(Dispatchers.IO) {
        pendingResponses.forEach { (approvalId, file) ->
            runCatching { file.writeText("deny") }
        }
        pendingResponses.clear()
        pendingRequests.clear()
        taskStore.clearApprovalsForSession(sessionId)
    }
}
