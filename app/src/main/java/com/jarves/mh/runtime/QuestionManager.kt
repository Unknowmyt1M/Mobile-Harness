package com.jarves.mh.runtime

import android.content.Context
import android.util.Log
import com.jarves.mh.data.db.AgentTaskStore
import com.jarves.mh.model.AgentAnswer
import com.jarves.mh.model.AgentQuestion
import com.jarves.mh.model.QuestionStatus
import com.jarves.mh.model.ResolutionPolicy
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.policy.QuestionPolicyEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

class QuestionManager(
    private val context: Context,
    private val taskStore: AgentTaskStore,
    private val onEvent: suspend (RuntimeEvent) -> Unit,
) {
    val bridgeDir: File by lazy { File(context.filesDir, "runtime-bridge").apply { mkdirs() } }
    private val activeQuestionFiles = ConcurrentHashMap<String, File>() // questionId -> reqFile
    private val autoResolveJobs = ConcurrentHashMap<String, Job>()
    private val scope = CoroutineScope(Dispatchers.IO)

    suspend fun watchQuestions(sessionId: String, taskId: String) = withContext(Dispatchers.IO) {
        bridgeDir.mkdirs()
        while (coroutineContext.isActive) {
            val reqFiles = bridgeDir.listFiles { _, name -> name.startsWith("q_") && name.endsWith(".request") }
            reqFiles?.forEach { file ->
                val questionId = file.name.removeSuffix(".request")
                if (!activeQuestionFiles.containsKey(questionId)) {
                    activeQuestionFiles[questionId] = file
                    handleQuestionRequest(sessionId, taskId, questionId, file)
                }
            }
            delay(100)
        }
    }

    private suspend fun handleQuestionRequest(sessionId: String, taskId: String, questionId: String, file: File) {
        val raw = runCatching { file.readText().trim() }.getOrNull() ?: return
        Log.d("QuestionManager", "Received question request $questionId: $raw")
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: JSONObject()

        val rawQuestion = AgentQuestion.fromJson(json).copy(
            id = questionId,
            taskId = taskId,
            sessionId = sessionId,
        )

        // Enforce QuestionPolicyEngine as final authority on AUTO_RESOLVE vs USER_REQUIRED
        val finalResolutionPolicy = QuestionPolicyEngine.evaluate(rawQuestion)
        val question = rawQuestion.copy(resolutionPolicy = finalResolutionPolicy)

        taskStore.saveQuestion(question)
        onEvent(RuntimeEvent.QuestionRequested(sessionId, question))

        // If AUTO_RESOLVE is granted by PolicyEngine and a default exists, start 5-minute timer
        if (question.resolutionPolicy == ResolutionPolicy.AUTO_RESOLVE) {
            val defaultOptId = question.defaultOptionId
                ?: question.options.firstOrNull { it.isRecommended }?.id
                ?: question.options.firstOrNull()?.id

            if (defaultOptId != null) {
                val timeoutSeconds = question.autoResolveTimeoutSeconds.coerceAtLeast(30)
                Log.i("QuestionManager", "Scheduling AUTO_RESOLVE countdown (${timeoutSeconds}s) for question $questionId")

                val timerJob = scope.launch {
                    delay(timeoutSeconds * 1000L)
                    if (activeQuestionFiles.containsKey(questionId)) {
                        Log.i("QuestionManager", "AUTO_RESOLVE timer expired for $questionId, auto-submitting default answer: $defaultOptId")
                        val autoAnswer = AgentAnswer(
                            questionId = questionId,
                            taskId = taskId,
                            selectedOptionIds = listOf(defaultOptId),
                            textValue = "Auto-resolved with default after timeout",
                            isCustom = false,
                        )
                        submitAnswer(autoAnswer)
                    }
                }
                autoResolveJobs[questionId] = timerJob
            }
        }
    }

    suspend fun submitAnswer(answer: AgentAnswer) = withContext(Dispatchers.IO) {
        val questionId = answer.questionId
        autoResolveJobs.remove(questionId)?.cancel()
        val reqFile = activeQuestionFiles.remove(questionId) ?: File(bridgeDir, "$questionId.request")
        val respFile = File(bridgeDir, "$questionId.response")

        // Persist answer to SQLite
        taskStore.saveAnswer(answer)

        // Write response file for waiting PRoot guest process
        val payload = answer.toJson().toString()
        runCatching {
            respFile.writeText(payload)
            Log.d("QuestionManager", "Wrote question response to ${respFile.name}: $payload")
        }.onFailure { Log.e("QuestionManager", "Failed to write response for $questionId", it) }

        onEvent(RuntimeEvent.QuestionAnswered(answer.taskId, answer))
    }

    suspend fun cancelAllPending(sessionId: String) = withContext(Dispatchers.IO) {
        autoResolveJobs.values.forEach { it.cancel() }
        autoResolveJobs.clear()

        activeQuestionFiles.forEach { (id, file) ->
            val respFile = File(bridgeDir, "$id.response")
            runCatching {
                respFile.writeText(JSONObject().put("cancelled", true).toString())
            }
            taskStore.cancelQuestion(id)
        }
        activeQuestionFiles.clear()
    }
}
