package com.jarves.mh.runtime

import android.content.Context
import android.util.Log
import com.jarves.mh.data.ApiKeyVault
import com.jarves.mh.data.db.AgentTaskStore
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.ProviderProtocol
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.policy.AgentPolicyEngine
import com.jarves.mh.runtime.provider.ModelProtocolAdapter
import com.jarves.mh.runtime.provider.OpenAIChatAdapter
import com.jarves.mh.runtime.provider.OpenAIResponsesAdapter
import com.jarves.mh.runtime.provider.OpenAIStreamingParser
import com.jarves.mh.runtime.provider.ParsedStreamChunk
import com.jarves.mh.runtime.tool.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Direct native model streaming runtime that executes autonomous tool loops in Kotlin
 * without passing through Claude Code CLI or fake gateways.
 */
class NativeModelAgentRuntime(
    private val context: Context,
    private val vault: ApiKeyVault,
    private val taskStore: AgentTaskStore,
    private val permissionManager: PermissionManager,
    private val questionManager: QuestionManager,
    private val checkpointManager: CheckpointManager,
) : AgentRuntime {

    private val scope = CoroutineScope(Dispatchers.IO)
    private val _events = MutableSharedFlow<RuntimeEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<RuntimeEvent> = _events.asSharedFlow()

    @Volatile
    override var activeSessionId: String? = null
        private set

    private var activeJob: Job? = null
    private val toolRegistry = ToolRegistry(
        context = context,
        permissionManager = permissionManager,
        questionManager = questionManager,
        policyEngine = AgentPolicyEngine,
    )

    private val chatAdapter = OpenAIChatAdapter()
    private val responsesAdapter = OpenAIResponsesAdapter()

    override suspend fun startSession(
        projectId: String,
        projectSlug: String,
        projectKind: ProjectKind,
        prompt: String,
        conversationHistory: List<ChatMessage>,
        provider: ProviderProfile,
    ): String {
        val sessionId = UUID.randomUUID().toString()
        activeSessionId = sessionId

        val secret = vault.get(provider.kind.name) ?: ""
        if (provider.activeProtocol != ProviderProtocol.CLAUDE_LOGIN && secret.isBlank()) {
            throw IllegalArgumentException("API key required for ${provider.kind.title}")
        }

        activeJob?.cancel()
        activeJob = scope.launch {
            runSessionLoop(
                sessionId = sessionId,
                projectId = projectId,
                projectSlug = projectSlug,
                prompt = prompt,
                conversationHistory = conversationHistory,
                provider = provider,
                apiKey = secret,
            )
        }

        return sessionId
    }

    private suspend fun runSessionLoop(
        sessionId: String,
        projectId: String,
        projectSlug: String,
        prompt: String,
        conversationHistory: List<ChatMessage>,
        provider: ProviderProfile,
        apiKey: String,
    ) = withContext(Dispatchers.IO) {
        _events.emit(RuntimeEvent.SessionStarted(sessionId))
        val workspace = File(context.filesDir, "workspaces/$projectId").apply { mkdirs() }

        // Capture initial checkpoint before executing actions
        checkpointManager.createCheckpoint(
            projectId = projectId,
            workspace = workspace,
        )

        val messages = JSONArray()
        // 1. System instruction
        val systemPrompt = """
            You are Mobile Harness, an expert autonomous mobile software engineering agent.
            You are working in an Ubuntu 20.04 ARM64 environment inside the project directory: /workspace/$projectSlug
            You have access to tools to inspect files, edit code, execute bash commands, ask the user questions, and request permissions.
            Be concise, direct, and verify your changes carefully.
        """.trimIndent()
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", systemPrompt)
        })

        // 2. Add history (bounded to last 10 messages for context budget)
        conversationHistory.takeLast(10).forEach { msg ->
            messages.put(JSONObject().apply {
                put("role", if (msg.fromUser) "user" else "assistant")
                put("content", msg.text)
            })
        }

        // 3. Add current user prompt
        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", prompt)
        })

        val adapter: ModelProtocolAdapter = when (provider.activeProtocol) {
            ProviderProtocol.OPENAI_RESPONSES -> responsesAdapter
            else -> chatAdapter
        }

        var turnCount = 0
        val maxTurns = 20
        var continueLoop = true

        while (continueLoop && turnCount < maxTurns && activeSessionId == sessionId) {
            turnCount++
            var assistantReplyText = StringBuilder()
            val toolCallsMap = mutableMapOf<Int, OpenAIStreamingParser.ToolCallAccumulator>()

            runCatching {
                adapter.streamTurn(
                    provider = provider,
                    apiKey = apiKey,
                    messages = messages,
                    toolRegistry = toolRegistry,
                ).collect { chunk ->
                    when (chunk) {
                        is ParsedStreamChunk.TextDelta -> {
                            assistantReplyText.append(chunk.text)
                            _events.emit(RuntimeEvent.AssistantDelta(sessionId, chunk.text))
                        }
                        is ParsedStreamChunk.ReasoningDelta -> {
                            _events.emit(RuntimeEvent.ReasoningProgress(sessionId, chunk.text.length / 4))
                        }
                        is ParsedStreamChunk.ToolCallDelta -> {
                            val accum = toolCallsMap.getOrPut(chunk.index) {
                                OpenAIStreamingParser.ToolCallAccumulator()
                            }
                            chunk.id?.let { accum.id = it }
                            chunk.name?.let { accum.name = it }
                            chunk.argumentsDelta?.let { accum.arguments.append(it) }
                        }
                        is ParsedStreamChunk.Completed -> {
                            // Turn finished streaming
                        }
                    }
                }
            }.onFailure { error ->
                Log.e("NativeAgentRuntime", "Streaming error during turn $turnCount", error)
                _events.emit(RuntimeEvent.SessionFailed(sessionId, error.message ?: "Unknown error"))
                continueLoop = false
                return@withContext
            }

            val assistantMsg = JSONObject().apply {
                put("role", "assistant")
                if (assistantReplyText.isNotEmpty()) {
                    put("content", assistantReplyText.toString())
                }
                if (toolCallsMap.isNotEmpty()) {
                    val tcArray = JSONArray()
                    toolCallsMap.toSortedMap().values.forEach { tc ->
                        tcArray.put(JSONObject().apply {
                            put("id", tc.id.ifBlank { "call_${UUID.randomUUID()}" })
                            put("type", "function")
                            put("function", JSONObject().apply {
                                put("name", tc.name)
                                put("arguments", tc.arguments.toString())
                            })
                        })
                    }
                    put("tool_calls", tcArray)
                }
            }
            messages.put(assistantMsg)

            // If no tools were called, the agent concluded its answer
            if (toolCallsMap.isEmpty()) {
                continueLoop = false
                break
            }

            // Execute called tools sequentially
            for ((_, tc) in toolCallsMap.toSortedMap()) {
                val toolName = tc.name
                val rawArgs = tc.arguments.toString()
                _events.emit(RuntimeEvent.ToolStarted(sessionId, toolName, rawArgs.take(80)))

                val tool = toolRegistry.getTool(toolName)
                val toolOutput: String = if (tool == null) {
                    "Error: Unknown tool '$toolName'"
                } else {
                    val argsJson = runCatching { JSONObject(rawArgs) }.getOrElse { JSONObject() }
                    val result = tool.execute(sessionId, projectId, argsJson)
                    if (result.isPermissionRequired) {
                        _events.emit(
                            RuntimeEvent.RuntimeLog(
                                sessionId,
                                "Permission required",
                                "Action paused awaiting user decision",
                            )
                        )
                    }
                    if (result.success) {
                        result.output
                    } else {
                        result.error ?: result.output
                    }
                }

                _events.emit(RuntimeEvent.ToolCompleted(sessionId, toolName, toolOutput.take(120)))

                // Add tool result message
                messages.put(JSONObject().apply {
                    put("role", "tool")
                    put("tool_call_id", tc.id)
                    put("name", toolName)
                    put("content", toolOutput)
                })
            }
        }

        val pending = checkpointManager.loadPendingChanges(projectId, workspace)
        if (pending.isNotEmpty()) {
            _events.emit(RuntimeEvent.FilesChanged(sessionId, pending))
        }

        _events.emit(RuntimeEvent.SessionCompleted(sessionId))
        activeSessionId = null
    }

    override suspend fun stopSession(sessionId: String) {
        if (activeSessionId == sessionId) {
            stopActiveSession()
        }
    }

    override suspend fun stopActiveSession() {
        activeJob?.cancel()
        activeJob = null
        val id = activeSessionId
        activeSessionId = null
        if (id != null) {
            _events.emit(RuntimeEvent.SessionCompleted(id))
        }
    }
}
