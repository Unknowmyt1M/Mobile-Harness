package com.jarves.mh.runtime

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarves.mh.data.db.AgentTaskStore
import com.jarves.mh.gateway.LocalModelGateway
import com.jarves.mh.memory.ContextSelectionEngine
import com.jarves.mh.memory.ProjectMemoryStore
import com.jarves.mh.model.AgentTask
import com.jarves.mh.model.AgentTaskState
import com.jarves.mh.model.ChangeItem
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.ProviderProtocol
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.ToolRequest
import com.jarves.mh.security.SecretRedactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal object ProviderRuntimeErrorDetector {
    fun detect(line: String): String? {
        val json = runCatching { JSONObject(line) }.getOrNull()
        val combined = buildString {
            append(line)
            json?.let {
                append(' ')
                append(it.optString("error"))
                append(' ')
                append(it.optString("message"))
                append(' ')
                append(it.optString("result"))
            }
        }.lowercase()
        return when {
            "user not found" in combined -> "User not found. Check the API key and provider account."
            "authentication_failed" in combined ||
                "authentication failed" in combined ||
                "invalid api key" in combined ||
                "http 401" in combined ||
                (json?.optString("subtype") == "api_retry" && json.optInt("error_status") in listOf(401, 403)) ->
                "The provider rejected the saved API key."
            else -> null
        }
    }
}

class ClaudeRuntimeBridge(
    private val context: Context,
    private val secretFor: (ProviderProfile) -> String?,
) : RuntimeBridge {
    private val installer = RuntimeInstaller(context)
    private val eventBus = MutableSharedFlow<RuntimeEvent>(extraBufferCapacity = 64)
    override val events: Flow<RuntimeEvent> = eventBus

    val taskStore = AgentTaskStore(context)
    private val checkpointManager = CheckpointManager(File(context.filesDir, "checkpoints"))
    private val processRunner = ClaudeProcessRunner(context, installer)
    val interactionManager = AgentInteractionManager(
        context = context,
        taskStore = taskStore,
        onEvent = { event ->
            eventBus.emit(event)
            if (event is RuntimeEvent.QuestionRequested) {
                notifyQuestionForeground(
                    activeProjectSlug ?: "your project",
                    event.question.question.take(120),
                )
            }
        },
    )
    private val permissionBroker get() = interactionManager.permissionManager
    private val questionBroker get() = interactionManager.questionManager
    private val streamParser = ClaudeStreamParser(onEvent = { eventBus.emit(it) })
    private val projectRoots = ConcurrentHashMap<String, String>()
    private val finishedSessions = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var activeSessionId: String? = null
    @Volatile private var activeTaskId: String? = null
    @Volatile private var activeProjectSlug: String? = null
    @Volatile private var taskStartedAtElapsedRealtime: Long = 0L
    @Volatile private var lastForegroundProgressAt: Long = 0L
    @Volatile private var foregroundResultPosted: Boolean = false
    @Volatile private var activeGateway: LocalModelGateway? = null

    init {
        // Clean up any stale process groups left from abnormal app termination
        OrphanProcessCleaner.cleanupOrphans(context)
        BoundedOutputLogger.cleanupStaleLogs(context.cacheDir)
    }

    override suspend fun startSession(
        projectId: String,
        projectSlug: String,
        projectKind: ProjectKind,
        prompt: String,
        conversationHistory: List<ChatMessage>,
        provider: ProviderProfile,
    ): String = withContext(Dispatchers.IO + NonCancellable) {
        val sessionId = UUID.randomUUID().toString()
        val taskId = UUID.randomUUID().toString()

        finishedSessions.remove(sessionId)
        activeSessionId = sessionId
        activeTaskId = taskId
        activeProjectSlug = projectSlug
        taskStartedAtElapsedRealtime = android.os.SystemClock.elapsedRealtime()
        lastForegroundProgressAt = 0L
        foregroundResultPosted = false
        streamParser.reset()

        // Create and persist task state
        val task = AgentTask(
            id = taskId,
            projectId = projectId,
            sessionId = sessionId,
            state = AgentTaskState.RUNNING,
            prompt = prompt,
            startedAtMillis = System.currentTimeMillis(),
        )
        taskStore.saveTask(task)

        eventBus.emit(RuntimeEvent.SessionStarted(sessionId))
        pushForegroundProgress("Starting Claude Code…")

        val secret = secretFor(provider).orEmpty()
        if (provider.kind != ProviderKind.CLAUDE && secret.isBlank()) {
            val errorMsg = "No API key is saved for ${provider.kind.title}."
            taskStore.updateTaskState(taskId, AgentTaskState.FAILED, errorMsg)
            eventBus.emit(RuntimeEvent.SessionFailed(sessionId, errorMsg))
            return@withContext sessionId
        }

        runCatching {
            RuntimeTaskController.stopAction = {
                processRunner.stop()
            }
            startForegroundRuntime(projectSlug)

            val workspace = ensureWorkspace(projectId)
            checkpointManager.createCheckpoint(projectId, workspace)
            val beforeSnapshot = checkpointManager.snapshot(workspace)

            // Initialize and load project memory
            ProjectMemoryStore.initializeIfMissing(workspace, projectSlug)
            ProjectMemoryStore.updateState(
                workspace,
                ProjectMemoryStore.readState(workspace).copy(
                    lastTaskId = taskId,
                    lastSessionId = sessionId,
                    lastUpdated = System.currentTimeMillis(),
                )
            )
            val projectMemory = ProjectMemoryStore.readMemory(workspace)
            val memoryContext = ContextSelectionEngine.selectContext(
                memory = projectMemory,
                currentPrompt = prompt,
                recentFiles = ContextSelectionEngine.summarizeWorkspaceFiles(workspace, 15),
            )

            // Setup local gateway if OpenAI or Kimi is selected
            var gatewayUrl: String? = null
            if (provider.kind.protocol == ProviderProtocol.OPENAI_RESPONSES ||
                provider.kind.protocol == ProviderProtocol.OPENAI_CHAT
            ) {
                val gateway = LocalModelGateway(provider, secret)
                activeGateway = gateway
                gatewayUrl = gateway.start()
            }

            val guestWorkspacePath = "/workspace/$projectSlug"
            val contextPrompt = buildContextPrompt(
                currentPrompt = prompt,
                history = conversationHistory,
                guestWorkspacePath = guestWorkspacePath,
                projectKind = projectKind,
                memoryContext = memoryContext,
            )

            val process = processRunner.launch(
                projectId = projectId,
                projectSlug = projectSlug,
                projectKind = projectKind,
                contextPrompt = contextPrompt,
                provider = provider,
                authToken = secret,
                localGatewayUrl = gatewayUrl,
                workspace = workspace,
            )

            coroutineScope {
                val permissionWatcher = launch { permissionBroker.watchRequests(sessionId, taskId) }
                val questionWatcher = launch { questionBroker.watchQuestions(sessionId, taskId) }
                var lastDiagnostic = ""
                val pendingOutput = StringBuilder()
                val nativeProcess = process as? NativeSpawnProcess
                    ?: error("Unsupported Android runtime process")
                var outputOffset = 0L

                while (process.isAlive || nativeProcess.outputFile.length() > outputOffset) {
                    val available = nativeProcess.outputFile.length() - outputOffset
                    if (available <= 0) {
                        delay(50)
                        continue
                    }
                    val bytesToRead = minOf(available, 16L * 1024).toInt()
                    val bytes = ByteArray(bytesToRead)
                    val count = RandomAccessFile(nativeProcess.outputFile, "r").use { file ->
                        file.seek(outputOffset)
                        file.read(bytes)
                    }
                    if (count > 0) {
                        outputOffset += count
                        pendingOutput.append(bytes.decodeToString(0, count))
                        var newline = pendingOutput.indexOf("\n")
                        while (newline >= 0) {
                            val line = pendingOutput.substring(0, newline).trimEnd('\r')
                            pendingOutput.delete(0, newline + 1)
                            if (line.isNotBlank()) {
                                // Redact secrets before logging to logcat
                                Log.d("ClaudeBridge", "OUTPUT: ${SecretRedactor.redact(line)}")
                                ProviderRuntimeErrorDetector.detect(line)?.let { reason ->
                                    process.destroyForcibly()
                                    throw IllegalStateException(reason)
                                }
                                if (!streamParser.parseLine(sessionId, line)) {
                                    lastDiagnostic = line.takeLast(500)
                                }
                            }
                            newline = pendingOutput.indexOf("\n")
                        }
                    }
                }

                pendingOutput.toString().trim().takeIf(String::isNotBlank)?.let { line ->
                    if (!streamParser.parseLine(sessionId, line)) lastDiagnostic = line.takeLast(500)
                }

                val exit = process.waitFor()
                Log.d("ClaudeBridge", "Process exited with code $exit")
                permissionWatcher.cancelAndJoin()
                questionWatcher.cancelAndJoin()
                permissionBroker.cancelAllPending(sessionId)
                questionBroker.cancelAllPending(sessionId)

                val changed = checkpointManager.detectChangedFiles(workspace, beforeSnapshot)
                if (changed.isNotEmpty()) {
                    Log.d("ClaudeBridge", "Changed files: $changed")
                    checkpointManager.saveChangedPaths(projectId, changed)
                    val details = checkpointManager.loadPendingChanges(projectId, workspace)
                    eventBus.emit(RuntimeEvent.FilesChanged(sessionId, details))
                } else if (!File(checkpointManager.checkpointDir(projectId), "changes.json").isFile) {
                    checkpointManager.acceptLastChanges(projectId)
                }

                if (exit == 0) {
                    taskStore.updateTaskState(taskId, AgentTaskState.COMPLETED)
                    emitCompletedOnce(sessionId)
                    finishForegroundRuntime(
                        completed = true,
                        projectName = projectSlug,
                        detail = "Claude Code finished the task in $projectSlug.",
                    )
                } else {
                    if (processRunner.userStopRequested) {
                        taskStore.updateTaskState(taskId, AgentTaskState.CANCELLED, "Stopped by user")
                        throw IllegalStateException("Stopped by user")
                    }
                    val err = lastDiagnostic.ifBlank { "Claude Code stopped with exit code $exit" }
                    taskStore.updateTaskState(taskId, AgentTaskState.FAILED, err)
                    error(err)
                }
            }
        }.onFailure { error ->
            Log.e("ClaudeBridge", "Session failed", error)
            val message = friendlyError(error)
            taskStore.updateTaskState(taskId, AgentTaskState.FAILED, message)
            emitFailureOnce(sessionId, message)
            if (processRunner.userStopRequested) {
                cancelForegroundRuntime()
            } else {
                finishForegroundRuntime(
                    completed = false,
                    projectName = projectSlug,
                    detail = message,
                )
            }
        }

        activeGateway?.stop()
        activeGateway = null
        processRunner.cleanup()
        activeSessionId = null
        activeTaskId = null
        RuntimeTaskController.stopAction = null
        BoundedOutputLogger.cleanupStaleLogs(context.cacheDir)
        sessionId
    }

    override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) {
        withContext(Dispatchers.IO) {
            val decision = if (approved) com.jarves.mh.model.PermissionDecision.ALLOW_ONCE else com.jarves.mh.model.PermissionDecision.DENY_ONCE
            permissionBroker.respond(request.approvalId, decision, request.sessionId)
        }
    }

    override suspend fun respondToPermission(requestId: String, decision: com.jarves.mh.model.PermissionDecision, sessionId: String) {
        withContext(Dispatchers.IO) {
            permissionBroker.respond(requestId, decision, sessionId)
        }
    }

    override suspend fun respondToQuestion(answer: com.jarves.mh.model.AgentAnswer) = withContext(Dispatchers.IO) {
        questionBroker.submitAnswer(answer)
    }

    override suspend fun stopSession(sessionId: String) = withContext(Dispatchers.IO) {
        if (activeSessionId == sessionId) {
            processRunner.stop()
            activeTaskId?.let { taskStore.updateTaskState(it, AgentTaskState.CANCELLED, "Stopped by user") }
            emitFailureOnce(sessionId, "Stopped by user")
        }
    }

    override suspend fun stopActiveSession() {
        activeSessionId?.let { stopSession(it) }
    }

    override suspend fun undoLastChanges(projectId: String): Boolean = withContext(Dispatchers.IO) {
        val workspace = ensureWorkspace(projectId)
        checkpointManager.undoLastChanges(projectId, workspace)
    }

    override suspend fun acceptLastChanges(projectId: String) {
        withContext(Dispatchers.IO) {
            checkpointManager.acceptLastChanges(projectId)
        }
    }

    override suspend fun loadPendingChanges(projectId: String): List<ChangeItem> = withContext(Dispatchers.IO) {
        val workspace = ensureWorkspace(projectId)
        checkpointManager.loadPendingChanges(projectId, workspace)
    }

    override suspend fun undoFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        val workspace = ensureWorkspace(projectId)
        checkpointManager.undoFileChange(projectId, workspace, path)
    }

    override suspend fun acceptFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        val workspace = ensureWorkspace(projectId)
        checkpointManager.acceptFileChange(projectId, workspace, path)
    }

    fun configureProjectRoot(projectId: String, rootPath: String) {
        val normalized = rootPath.trim().trim('/')
        require(normalized.isBlank() || (!normalized.contains("..") && !normalized.startsWith('/'))) {
            "Unsafe project root"
        }
        val previous = projectRoots.put(projectId, normalized).orEmpty()
        if (previous != normalized) checkpointManager.acceptLastChanges(projectId)
    }

    private fun ensureWorkspace(projectId: String): File {
        val base = File(context.filesDir, "workspaces/$projectId").apply { mkdirs() }.canonicalFile
        val rootPath = projectRoots[projectId].orEmpty()
        if (rootPath.isBlank()) return base
        val selected = File(base, rootPath).canonicalFile
        require(selected.toPath().startsWith(base.toPath())) { "Unsafe project root" }
        return selected.apply { mkdirs() }
    }

    private fun buildContextPrompt(
        currentPrompt: String,
        history: List<ChatMessage>,
        guestWorkspacePath: String,
        projectKind: ProjectKind,
        memoryContext: String = "",
    ): String {
        val priorMessages = history
            .filter { msg ->
                (msg.fromUser || !msg.text.startsWith("Hi! Tell me")) &&
                    !msg.text.startsWith("Failed to") &&
                    !msg.text.startsWith("Error:") &&
                    !msg.text.contains("API Error")
            }
            .dropLast(1)

        val sb = StringBuilder()
        sb.appendLine("<project_workspace>")
        if (projectKind == ProjectKind.QUICK_PROJECT) {
            sb.appendLine("This is a lightweight project workspace at $guestWorkspacePath.")
            sb.appendLine("Respond conversationally, and use terminal or file tools whenever they are useful for the request.")
            sb.appendLine("Keep every file and command inside this project workspace.")
        } else {
            sb.appendLine("The current working directory $guestWorkspacePath is the project root.")
            sb.appendLine("Create and edit project files directly in this directory. Do not create another outer project folder unless the user explicitly asks for one.")
            sb.appendLine("When giving commands to the user, make them runnable from this project root.")
        }
        sb.appendLine("For local servers, give a clear start command and never use a kill command that searches its own command text with pgrep, because it can terminate the terminal itself.")
        sb.appendLine("</project_workspace>")
        sb.appendLine("<available_mobile_tools>")
        sb.appendLine("You have access to the 'ask_question' and 'request_permission' MCP tools exposed by the host mobile environment:")
        sb.appendLine("- Use 'ask_question' whenever you need user input, selection between options, confirmation, or clarification.")
        sb.appendLine("- Use 'request_permission' whenever you plan to run dangerous commands, delete files, or install system packages.")
        sb.appendLine("- Fallback CLI: If you need to ask a question from a bash subshell, run: ask-question '<json>'")
        sb.appendLine("</available_mobile_tools>")
        if (memoryContext.isNotBlank()) {
            sb.appendLine()
            sb.appendLine(memoryContext)
        }
        sb.appendLine()
        if (priorMessages.isEmpty()) {
            sb.appendLine(currentPrompt)
            return sb.toString()
        }
        sb.appendLine("<conversation_history>")
        sb.appendLine("The following is our prior conversation in this project. Continue naturally from where we left off.")
        sb.appendLine()
        for (msg in priorMessages) {
            val role = if (msg.fromUser) "User" else "Assistant"
            sb.appendLine("$role: ${msg.text}")
            if (msg.attachments.isNotEmpty()) {
                sb.appendLine("Attached files:")
                msg.attachments.forEach { attachment ->
                    sb.appendLine("- ${attachment.displayName}: $guestWorkspacePath/${attachment.relativePath} (${attachment.mimeType})")
                }
            }
            sb.appendLine()
        }
        sb.appendLine("</conversation_history>")
        sb.appendLine()
        sb.appendLine("Now, respond to this new message from the user:")
        sb.appendLine(currentPrompt)
        return sb.toString()
    }

    private suspend fun emitCompletedOnce(sessionId: String) {
        if (finishedSessions.add(sessionId)) {
            eventBus.emit(RuntimeEvent.SessionCompleted(sessionId))
            finishForegroundRuntime(
                completed = true,
                projectName = activeProjectSlug ?: "your project",
                detail = "Claude Code finished the task.",
            )
        }
    }

    private suspend fun emitFailureOnce(sessionId: String, reason: String) {
        if (finishedSessions.add(sessionId)) {
            eventBus.emit(RuntimeEvent.SessionFailed(sessionId, reason))
            if (processRunner.userStopRequested) {
                cancelForegroundRuntime()
            } else {
                finishForegroundRuntime(
                    completed = false,
                    projectName = activeProjectSlug ?: "your project",
                    detail = reason,
                )
            }
        }
    }

    private fun friendlyError(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            message.contains("user not found", true) -> "User not found. Check the API key and provider account."
            message.contains("checksum", true) -> "Runtime verification failed. Nothing unverified was executed."
            message.contains("HTTP 401", true) || message.contains("authentication", true) -> "The provider rejected the saved API key."
            message.isBlank() -> "The real Claude Code runtime could not start."
            else -> message.take(500)
        }
    }

    private fun pushForegroundProgress(detailRaw: String) {
        if (activeSessionId == null) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastForegroundProgressAt < FOREGROUND_PROGRESS_MIN_INTERVAL_MS) return
        lastForegroundProgressAt = now
        val detail = detailRaw.replace(Regex("\\s+"), " ").trim().take(110)
        val elapsedMs = taskStartedAtElapsedRealtime.takeIf { it > 0 }?.let { now - it } ?: 0L
        val text = if (elapsedMs > 0L) "$detail · ${formatElapsedShort(elapsedMs)}" else detail
        runCatching {
            context.startService(
                android.content.Intent(context, RuntimeExecutionService::class.java)
                    .setAction(RuntimeExecutionService.ACTION_PROGRESS)
                    .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, activeProjectSlug)
                    .putExtra(RuntimeExecutionService.EXTRA_DETAIL, text),
            )
        }
    }

    private fun formatElapsedShort(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
    }

    private fun startForegroundRuntime(projectName: String) {
        ContextCompat.startForegroundService(
            context,
            android.content.Intent(context, RuntimeExecutionService::class.java)
                .setAction(RuntimeExecutionService.ACTION_START)
                .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, projectName),
        )
    }

    private fun notifyQuestionForeground(projectName: String, detail: String) {
        runCatching {
            context.startService(
                android.content.Intent(context, RuntimeExecutionService::class.java)
                    .setAction(RuntimeExecutionService.ACTION_QUESTION)
                    .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, projectName)
                    .putExtra(RuntimeExecutionService.EXTRA_DETAIL, detail),
            )
        }
    }

    private fun finishForegroundRuntime(completed: Boolean, projectName: String, detail: String) {
        if (foregroundResultPosted) return
        foregroundResultPosted = true
        runCatching {
            context.startService(
                android.content.Intent(context, RuntimeExecutionService::class.java)
                    .setAction(
                        if (completed) RuntimeExecutionService.ACTION_COMPLETE
                        else RuntimeExecutionService.ACTION_FAILED,
                    )
                    .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, projectName)
                    .putExtra(RuntimeExecutionService.EXTRA_DETAIL, detail),
            )
        }.onFailure { error ->
            Log.w("ClaudeBridge", "Could not post task result notification", error)
            context.stopService(android.content.Intent(context, RuntimeExecutionService::class.java))
        }
    }

    private fun cancelForegroundRuntime() {
        if (foregroundResultPosted) return
        foregroundResultPosted = true
        runCatching {
            context.startService(
                android.content.Intent(context, RuntimeExecutionService::class.java)
                    .setAction(RuntimeExecutionService.ACTION_CANCELLED),
            )
        }.onFailure {
            context.stopService(android.content.Intent(context, RuntimeExecutionService::class.java))
        }
    }

    companion object {
        private const val FOREGROUND_PROGRESS_MIN_INTERVAL_MS = 750L
    }
}
