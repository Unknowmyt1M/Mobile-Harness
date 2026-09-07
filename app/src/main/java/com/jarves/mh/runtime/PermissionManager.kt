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
import kotlinx.coroutines.CompletableDeferred
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
    private val taskStore: AgentTaskStore? = null,
    private val onEvent: suspend (RuntimeEvent) -> Unit = {},
) {
    private val pendingResponses = ConcurrentHashMap<String, File>()
    private val pendingRequests = ConcurrentHashMap<String, PermissionRequest>()
    private val pendingDeferred = ConcurrentHashMap<String, CompletableDeferred<PermissionDecision>>()
    private val sessionGrants = ConcurrentHashMap<String, MutableSet<CapabilityScope>>()
    private val sessionCommandGrants = ConcurrentHashMap<String, MutableSet<String>>()

    fun grantSessionCapability(sessionId: String, capability: CapabilityScope, command: String? = null, forSession: Boolean = false) {
        if (sessionId.isBlank()) return
        if (forSession || command == null) {
            sessionGrants.getOrPut(sessionId) { ConcurrentHashMap.newKeySet() }.add(capability)
        }
        command?.trim()?.takeIf { it.isNotBlank() }?.let { cmd ->
            sessionCommandGrants.getOrPut(sessionId) { ConcurrentHashMap.newKeySet() }.add(cmd)
        }
    }

    fun hasSessionGrant(sessionId: String, capability: CapabilityScope, command: String? = null): Boolean {
        if (sessionId.isBlank()) return false
        val caps = sessionGrants[sessionId]
        if (caps != null && capability in caps) return true
        val cmds = sessionCommandGrants[sessionId]
        if (cmds != null && command != null && command.trim() in cmds) return true
        return false
    }

    fun clearSessionGrants(sessionId: String) {
        sessionGrants.remove(sessionId)
        sessionCommandGrants.remove(sessionId)
    }

    suspend fun requestPermission(
        sessionId: String,
        taskId: String,
        projectId: String,
        capability: CapabilityScope,
        explanation: String,
        command: String? = null,
        affectedPaths: List<String> = emptyList(),
        riskLevel: RiskLevel = RiskLevel.REVIEW,
    ): PermissionDecision = withContext(Dispatchers.IO) {
        // 1. Check if already granted via durable grants or active session grants
        val activeGrants = taskStore?.getPermissionGrants() ?: emptyList()
        val hasDurable = activeGrants.any { it.matches(capability, projectId, affectedPaths.firstOrNull()) }
        if (hasDurable || hasSessionGrant(sessionId, capability, command)) {
            Log.d("PermissionManager", "Permission for $capability already granted for session $sessionId")
            return@withContext PermissionDecision.ALLOW_ONCE
        }

        // 2. Prevent duplicate pending requests for the exact same action in this session
        val existingPending = pendingRequests.values.firstOrNull { req ->
            req.sessionId == sessionId && req.capability == capability && req.command == command
        }
        if (existingPending != null) {
            val deferred = pendingDeferred[existingPending.requestId]
            if (deferred != null) {
                Log.d("PermissionManager", "Reusing existing pending permission request ${existingPending.requestId}")
                return@withContext deferred.await()
            }
        }

        // 3. Create fresh correlated permission request
        val approvalId = "p_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(6)}"
        val permReq = PermissionRequest(
            requestId = approvalId,
            sessionId = sessionId,
            taskId = taskId,
            projectId = projectId,
            capability = capability,
            explanation = explanation,
            command = command,
            affectedPaths = affectedPaths,
            riskLevel = riskLevel,
        )
        val legacyToolRequest = ToolRequest(
            approvalId = approvalId,
            sessionId = sessionId,
            toolName = if (capability == CapabilityScope.PROCESS_EXECUTE) "Bash" else capability.label,
            explanation = explanation,
            affectedPaths = affectedPaths,
            commandPreview = command,
            risk = riskLevel,
            capability = capability,
            projectId = projectId,
        )

        val deferred = CompletableDeferred<PermissionDecision>()
        pendingRequests[approvalId] = permReq
        pendingDeferred[approvalId] = deferred
        taskStore?.saveApproval(legacyToolRequest, taskId)

        onEvent(RuntimeEvent.PermissionRequested(sessionId, permReq))
        onEvent(RuntimeEvent.ToolRequested(sessionId, legacyToolRequest))

        try {
            deferred.await()
        } catch (e: Exception) {
            Log.w("PermissionManager", "Permission request $approvalId cancelled: ${e.message}")
            PermissionDecision.DENY_ONCE
        } finally {
            pendingDeferred.remove(approvalId)
            pendingRequests.remove(approvalId)
        }
    }

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

                    val activeGrants = taskStore?.getPermissionGrants() ?: emptyList()

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
                            if (hasSessionGrant(sessionId, decision.capability, command)) {
                                Log.d("PermissionManager", "Action $toolName ($approvalId) covered by session grant")
                                responseFile.writeText("allow")
                                file.delete()
                                return@runCatching
                            }

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
                            taskStore?.saveApproval(legacyToolRequest, taskId)
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
        val deferred = pendingDeferred.remove(approvalId)

        val approved = decision != PermissionDecision.DENY_ONCE

        if (approved) {
            val cap = req?.capability ?: CapabilityScope.PROCESS_EXECUTE
            grantSessionCapability(
                sessionId = sessionId,
                capability = cap,
                command = req?.command,
                forSession = decision == PermissionDecision.ALLOW_TASK || decision == PermissionDecision.ALLOW_PROJECT || decision == PermissionDecision.ALLOW_ALWAYS,
            )
        }

        // Durable grant persistence for ALLOW_PROJECT or ALLOW_ALWAYS
        if (req != null) {
            when (decision) {
                PermissionDecision.ALLOW_PROJECT -> {
                    taskStore?.savePermissionGrant(
                        PermissionGrant(
                            capability = req.capability,
                            projectId = req.projectId,
                            pathPattern = req.affectedPaths.firstOrNull(),
                        ),
                    )
                }
                PermissionDecision.ALLOW_ALWAYS -> {
                    taskStore?.savePermissionGrant(
                        PermissionGrant(
                            capability = req.capability,
                            projectId = null, // global grant
                            pathPattern = null,
                        ),
                    )
                }
                PermissionDecision.ALLOW_ONCE,
                PermissionDecision.ALLOW_TASK,
                PermissionDecision.DENY_ONCE -> {
                    // Handled in memory
                }
            }
        }

        deferred?.complete(decision)

        runCatching {
            responseFile.writeText(if (approved) "allow" else "deny")
            taskStore?.removeApproval(approvalId)
            onEvent(RuntimeEvent.PermissionResolved(sessionId, approvalId, decision))
            if (approved) {
                onEvent(RuntimeEvent.ToolApproved(sessionId, approvalId))
            } else {
                onEvent(RuntimeEvent.ToolRejected(sessionId, approvalId))
            }
        }
    }

    suspend fun cancelAllPending(sessionId: String = "") = withContext(Dispatchers.IO) {
        pendingResponses.forEach { (approvalId, file) ->
            runCatching { file.writeText("deny") }
        }
        pendingDeferred.forEach { (_, deferred) ->
            deferred.complete(PermissionDecision.DENY_ONCE)
        }
        pendingResponses.clear()
        pendingDeferred.clear()
        pendingRequests.clear()
        if (sessionId.isNotBlank()) {
            clearSessionGrants(sessionId)
            taskStore?.clearApprovalsForSession(sessionId)
        } else {
            sessionGrants.clear()
            sessionCommandGrants.clear()
        }
    }
}
