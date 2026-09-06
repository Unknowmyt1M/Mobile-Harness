package com.jarves.mh.runtime

import android.content.Context
import com.jarves.mh.data.db.AgentTaskStore
import com.jarves.mh.model.AgentAnswer
import com.jarves.mh.model.PermissionDecision
import com.jarves.mh.model.RuntimeEvent
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.File

class AgentInteractionManager(
    context: Context,
    val taskStore: AgentTaskStore,
    val onEvent: suspend (RuntimeEvent) -> Unit,
) {
    private val bridgeDir = File(context.filesDir, "runtime-bridge").apply { mkdirs() }

    val permissionManager = PermissionManager(
        bridgeDir = bridgeDir,
        taskStore = taskStore,
        onEvent = onEvent,
    )

    val questionManager = QuestionManager(
        context = context,
        taskStore = taskStore,
        onEvent = onEvent,
    )

    suspend fun watchAll(sessionId: String, taskId: String, projectId: String = "") = coroutineScope {
        launch { permissionManager.watchRequests(sessionId, taskId, projectId) }
        launch { questionManager.watchQuestions(sessionId, taskId) }
    }

    suspend fun respondPermission(approvalId: String, decision: PermissionDecision, sessionId: String) {
        permissionManager.respond(approvalId, decision, sessionId)
    }

    suspend fun respondQuestion(answer: AgentAnswer) {
        questionManager.submitAnswer(answer)
    }

    suspend fun cancelAll(sessionId: String) {
        permissionManager.cancelAllPending(sessionId)
        questionManager.cancelAllPending(sessionId)
    }
}
