package com.jarves.mh.runtime

import android.content.Context
import android.util.Log
import com.jarves.mh.data.db.AgentTaskStore
import com.jarves.mh.model.AgentAnswer
import com.jarves.mh.model.AgentQuestion
import com.jarves.mh.model.RuntimeEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class AgentQuestionBroker(
    private val context: Context,
    private val taskStore: AgentTaskStore,
    private val onEvent: suspend (RuntimeEvent) -> Unit,
) {
    private val bridgeDir: File by lazy { File(context.filesDir, "runtime-bridge").apply { mkdirs() } }
    private val activeQuestionFiles = ConcurrentHashMap<String, File>() // questionId -> reqFile

    suspend fun watchQuestions(sessionId: String, taskId: String) = withContext(Dispatchers.IO) {
        while (true) {
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
        Log.d("AgentQuestionBroker", "Received question request $questionId: $raw")
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: JSONObject()

        val question = AgentQuestion.fromJson(json).copy(
            id = questionId,
            taskId = taskId,
            sessionId = sessionId,
        )

        taskStore.saveQuestion(question)
        onEvent(RuntimeEvent.QuestionRequested(sessionId, question))
    }

    suspend fun submitAnswer(answer: AgentAnswer) = withContext(Dispatchers.IO) {
        val questionId = answer.questionId
        val reqFile = activeQuestionFiles.remove(questionId) ?: File(bridgeDir, "$questionId.request")
        val respFile = File(bridgeDir, "$questionId.response")

        // Persist answer to SQLite
        taskStore.saveAnswer(answer)

        // Write response file for waiting PRoot guest process
        val payload = answer.toJson().toString()
        runCatching {
            respFile.writeText(payload)
            Log.d("AgentQuestionBroker", "Wrote question response to ${respFile.name}: $payload")
        }.onFailure { Log.e("AgentQuestionBroker", "Failed to write response for $questionId", it) }

        onEvent(RuntimeEvent.QuestionAnswered(answer.taskId, answer))
    }

    suspend fun cancelAllPending(sessionId: String) = withContext(Dispatchers.IO) {
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
