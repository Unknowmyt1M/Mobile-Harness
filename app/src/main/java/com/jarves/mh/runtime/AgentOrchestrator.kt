package com.jarves.mh.runtime

import android.content.Context
import com.jarves.mh.data.ApiKeyVault
import com.jarves.mh.data.db.AgentTaskStore
import com.jarves.mh.model.AgentAnswer
import com.jarves.mh.model.ChangeItem
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.PermissionDecision
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.ProviderProtocol
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.ToolRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.File

class AgentOrchestrator(
    private val context: Context,
    private val secretFor: (ProviderProfile) -> String?,
    private val vault: ApiKeyVault,
) : RuntimeBridge {

    private val scope = CoroutineScope(Dispatchers.IO)
    private val eventBus = MutableSharedFlow<RuntimeEvent>(extraBufferCapacity = 128)
    override val events: Flow<RuntimeEvent> = eventBus.asSharedFlow()

    private val claudeBridge = ClaudeRuntimeBridge(context, secretFor)
    val taskStore: AgentTaskStore get() = claudeBridge.taskStore
    val interactionManager: AgentInteractionManager get() = claudeBridge.interactionManager
    private val checkpointManager = CheckpointManager(File(context.filesDir, "checkpoints"))

    private val nativeRuntime = NativeModelAgentRuntime(
        context = context,
        vault = vault,
        taskStore = taskStore,
        permissionManager = interactionManager.permissionManager,
        questionManager = interactionManager.questionManager,
        checkpointManager = checkpointManager,
    )

    @Volatile
    private var isUsingNativeRuntime: Boolean = false

    init {
        scope.launch {
            claudeBridge.events.collect { event ->
                if (!isUsingNativeRuntime) {
                    eventBus.emit(event)
                }
            }
        }
        scope.launch {
            nativeRuntime.events.collect { event ->
                if (isUsingNativeRuntime) {
                    eventBus.emit(event)
                }
            }
        }
    }

    fun configureProjectRoot(projectId: String, rootPath: String) {
        claudeBridge.configureProjectRoot(projectId, rootPath)
    }

    override suspend fun startSession(
        projectId: String,
        projectSlug: String,
        projectKind: ProjectKind,
        prompt: String,
        conversationHistory: List<ChatMessage>,
        provider: ProviderProfile,
    ): String {
        isUsingNativeRuntime = when (provider.activeProtocol) {
            ProviderProtocol.CLAUDE_LOGIN,
            ProviderProtocol.ANTHROPIC -> false
            else -> true
        }

        return if (isUsingNativeRuntime) {
            nativeRuntime.startSession(
                projectId = projectId,
                projectSlug = projectSlug,
                projectKind = projectKind,
                prompt = prompt,
                conversationHistory = conversationHistory,
                provider = provider,
            )
        } else {
            claudeBridge.startSession(
                projectId = projectId,
                projectSlug = projectSlug,
                projectKind = projectKind,
                prompt = prompt,
                conversationHistory = conversationHistory,
                provider = provider,
            )
        }
    }

    override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) {
        claudeBridge.respondToApproval(request, approved)
    }

    override suspend fun respondToPermission(
        requestId: String,
        decision: PermissionDecision,
        sessionId: String,
    ) {
        interactionManager.permissionManager.respond(requestId, decision, sessionId)
    }

    override suspend fun respondToQuestion(answer: AgentAnswer) {
        interactionManager.questionManager.submitAnswer(answer)
    }

    override suspend fun stopSession(sessionId: String) {
        if (isUsingNativeRuntime) {
            nativeRuntime.stopSession(sessionId)
        } else {
            claudeBridge.stopSession(sessionId)
        }
    }

    override suspend fun stopActiveSession() {
        if (isUsingNativeRuntime) {
            nativeRuntime.stopActiveSession()
        } else {
            claudeBridge.stopActiveSession()
        }
    }

    override suspend fun undoLastChanges(projectId: String): Boolean {
        return claudeBridge.undoLastChanges(projectId)
    }

    override suspend fun acceptLastChanges(projectId: String) {
        claudeBridge.acceptLastChanges(projectId)
    }

    override suspend fun loadPendingChanges(projectId: String): List<ChangeItem> {
        return claudeBridge.loadPendingChanges(projectId)
    }

    override suspend fun undoFileChange(projectId: String, path: String): Boolean {
        return claudeBridge.undoFileChange(projectId, path)
    }

    override suspend fun acceptFileChange(projectId: String, path: String): Boolean {
        return claudeBridge.acceptFileChange(projectId, path)
    }
}
