package com.jarves.mh.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.jarves.mh.model.AgentAnswer
import com.jarves.mh.model.AgentQuestion
import com.jarves.mh.model.AgentTask
import com.jarves.mh.model.AgentTaskState
import com.jarves.mh.model.QuestionOption
import com.jarves.mh.model.QuestionStatus
import com.jarves.mh.model.QuestionType
import com.jarves.mh.model.RiskLevel
import com.jarves.mh.model.ToolRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray

class AgentTaskStore(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    private val _activeTaskFlow = MutableStateFlow<AgentTask?>(null)
    val activeTaskFlow: StateFlow<AgentTask?> = _activeTaskFlow.asStateFlow()

    private val _pendingApprovalsFlow = MutableStateFlow<List<ToolRequest>>(emptyList())
    val pendingApprovalsFlow: StateFlow<List<ToolRequest>> = _pendingApprovalsFlow.asStateFlow()

    private val _pendingQuestionsFlow = MutableStateFlow<List<AgentQuestion>>(emptyList())
    val pendingQuestionsFlow: StateFlow<List<AgentQuestion>> = _pendingQuestionsFlow.asStateFlow()

    init {
        refreshActiveFlows()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE agent_tasks (
                id TEXT PRIMARY KEY,
                project_id TEXT NOT NULL,
                session_id TEXT,
                state TEXT NOT NULL,
                prompt TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                finished_at INTEGER,
                error_reason TEXT,
                turn_count INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE pending_approvals (
                approval_id TEXT PRIMARY KEY,
                task_id TEXT NOT NULL,
                session_id TEXT NOT NULL,
                tool_name TEXT NOT NULL,
                explanation TEXT NOT NULL,
                command_preview TEXT,
                affected_paths TEXT,
                risk TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE agent_questions (
                id TEXT PRIMARY KEY,
                task_id TEXT NOT NULL,
                session_id TEXT NOT NULL,
                type TEXT NOT NULL,
                title TEXT NOT NULL,
                question TEXT NOT NULL,
                options_json TEXT NOT NULL,
                required INTEGER NOT NULL,
                default_option_id TEXT,
                allow_custom INTEGER NOT NULL,
                status TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE agent_answers (
                question_id TEXT PRIMARY KEY,
                task_id TEXT NOT NULL,
                selected_options_json TEXT NOT NULL,
                text_value TEXT,
                is_custom INTEGER NOT NULL,
                answered_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE permission_grants (
                id TEXT PRIMARY KEY,
                capability TEXT NOT NULL,
                project_id TEXT,
                path_pattern TEXT,
                granted_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS agent_questions (
                    id TEXT PRIMARY KEY,
                    task_id TEXT NOT NULL,
                    session_id TEXT NOT NULL,
                    type TEXT NOT NULL,
                    title TEXT NOT NULL,
                    question TEXT NOT NULL,
                    options_json TEXT NOT NULL,
                    required INTEGER NOT NULL,
                    default_option_id TEXT,
                    allow_custom INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS agent_answers (
                    question_id TEXT PRIMARY KEY,
                    task_id TEXT NOT NULL,
                    selected_options_json TEXT NOT NULL,
                    text_value TEXT,
                    is_custom INTEGER NOT NULL,
                    answered_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
        }
        if (oldVersion < 3) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS permission_grants (
                    id TEXT PRIMARY KEY,
                    capability TEXT NOT NULL,
                    project_id TEXT,
                    path_pattern TEXT,
                    granted_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
        }
    }

    suspend fun saveTask(task: AgentTask) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("id", task.id)
            put("project_id", task.projectId)
            put("session_id", task.sessionId)
            put("state", task.state.name)
            put("prompt", task.prompt)
            put("started_at", task.startedAtMillis)
            put("finished_at", task.finishedAtMillis)
            put("error_reason", task.errorReason)
            put("turn_count", task.turnCount)
        }
        writableDatabase.insertWithOnConflict("agent_tasks", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        refreshActiveFlows()
    }

    suspend fun updateTaskState(taskId: String, state: AgentTaskState, errorReason: String? = null) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("state", state.name)
            if (errorReason != null) put("error_reason", errorReason)
            if (state == AgentTaskState.COMPLETED || state == AgentTaskState.FAILED || state == AgentTaskState.CANCELLED) {
                put("finished_at", System.currentTimeMillis())
            }
        }
        writableDatabase.update("agent_tasks", values, "id = ?", arrayOf(taskId))
        refreshActiveFlows()
    }

    suspend fun getTask(taskId: String): AgentTask? = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT * FROM agent_tasks WHERE id = ?", arrayOf(taskId)).use { cursor ->
            if (cursor.moveToFirst()) {
                AgentTask(
                    id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                    projectId = cursor.getString(cursor.getColumnIndexOrThrow("project_id")),
                    sessionId = cursor.getString(cursor.getColumnIndexOrThrow("session_id")),
                    state = AgentTaskState.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("state"))),
                    prompt = cursor.getString(cursor.getColumnIndexOrThrow("prompt")),
                    startedAtMillis = cursor.getLong(cursor.getColumnIndexOrThrow("started_at")),
                    finishedAtMillis = if (cursor.isNull(cursor.getColumnIndexOrThrow("finished_at"))) null else cursor.getLong(cursor.getColumnIndexOrThrow("finished_at")),
                    errorReason = cursor.getString(cursor.getColumnIndexOrThrow("error_reason")),
                    turnCount = cursor.getInt(cursor.getColumnIndexOrThrow("turn_count")),
                )
            } else null
        }
    }

    suspend fun saveApproval(request: ToolRequest, taskId: String) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("approval_id", request.approvalId)
            put("task_id", taskId)
            put("session_id", request.sessionId)
            put("tool_name", request.toolName)
            put("explanation", request.explanation)
            put("command_preview", request.commandPreview)
            put("affected_paths", JSONArray(request.affectedPaths).toString())
            put("risk", request.risk.name)
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("pending_approvals", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        refreshActiveFlows()
    }

    suspend fun removeApproval(approvalId: String) = withContext(Dispatchers.IO) {
        writableDatabase.delete("pending_approvals", "approval_id = ?", arrayOf(approvalId))
        refreshActiveFlows()
    }

    suspend fun clearApprovalsForSession(sessionId: String) = withContext(Dispatchers.IO) {
        writableDatabase.delete("pending_approvals", "session_id = ?", arrayOf(sessionId))
        refreshActiveFlows()
    }

    suspend fun getPendingApprovals(): List<ToolRequest> = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT * FROM pending_approvals ORDER BY created_at ASC", null).use { cursor ->
            val list = mutableListOf<ToolRequest>()
            while (cursor.moveToNext()) {
                val pathsRaw = cursor.getString(cursor.getColumnIndexOrThrow("affected_paths")).orEmpty()
                val paths = runCatching {
                    val arr = JSONArray(pathsRaw)
                    (0 until arr.length()).map { arr.getString(it) }
                }.getOrDefault(emptyList())

                list += ToolRequest(
                    approvalId = cursor.getString(cursor.getColumnIndexOrThrow("approval_id")),
                    sessionId = cursor.getString(cursor.getColumnIndexOrThrow("session_id")),
                    toolName = cursor.getString(cursor.getColumnIndexOrThrow("tool_name")),
                    explanation = cursor.getString(cursor.getColumnIndexOrThrow("explanation")),
                    commandPreview = cursor.getString(cursor.getColumnIndexOrThrow("command_preview")),
                    affectedPaths = paths,
                    risk = runCatching { RiskLevel.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("risk"))) }
                        .getOrDefault(RiskLevel.REVIEW),
                )
            }
            list
        }
    }

    suspend fun saveQuestion(question: AgentQuestion) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("id", question.id)
            put("task_id", question.taskId)
            put("session_id", question.sessionId)
            put("type", question.type.name)
            put("title", question.title)
            put("question", question.question)
            val arr = JSONArray()
            question.options.forEach { arr.put(it.toJson()) }
            put("options_json", arr.toString())
            put("required", if (question.required) 1 else 0)
            put("default_option_id", question.defaultOptionId)
            put("allow_custom", if (question.allowCustomAnswer) 1 else 0)
            put("status", question.status.name)
            put("created_at", question.createdAt)
        }
        writableDatabase.insertWithOnConflict("agent_questions", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        updateTaskState(question.taskId, AgentTaskState.WAITING_FOR_USER)
        refreshActiveFlows()
    }

    suspend fun getPendingQuestions(): List<AgentQuestion> = withContext(Dispatchers.IO) {
        val list = mutableListOf<AgentQuestion>()
        readableDatabase.rawQuery("SELECT * FROM agent_questions WHERE status = 'PENDING' ORDER BY created_at ASC", null).use { cursor ->
            while (cursor.moveToNext()) {
                val optsRaw = cursor.getString(cursor.getColumnIndexOrThrow("options_json")).orEmpty()
                val options = runCatching {
                    val arr = JSONArray(optsRaw)
                    val out = mutableListOf<com.jarves.mh.model.QuestionOption>()
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { out.add(com.jarves.mh.model.QuestionOption.fromJson(it)) }
                    }
                    out
                }.getOrDefault(emptyList())

                list += AgentQuestion(
                    id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                    taskId = cursor.getString(cursor.getColumnIndexOrThrow("task_id")),
                    sessionId = cursor.getString(cursor.getColumnIndexOrThrow("session_id")),
                    type = runCatching { com.jarves.mh.model.QuestionType.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("type"))) }
                        .getOrDefault(com.jarves.mh.model.QuestionType.SELECT_ONE),
                    title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                    question = cursor.getString(cursor.getColumnIndexOrThrow("question")),
                    options = options,
                    required = cursor.getInt(cursor.getColumnIndexOrThrow("required")) == 1,
                    defaultOptionId = cursor.getString(cursor.getColumnIndexOrThrow("default_option_id")),
                    allowCustomAnswer = cursor.getInt(cursor.getColumnIndexOrThrow("allow_custom")) == 1,
                    status = runCatching { com.jarves.mh.model.QuestionStatus.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("status"))) }
                        .getOrDefault(com.jarves.mh.model.QuestionStatus.PENDING),
                    createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                )
            }
        }
        list
    }

    suspend fun saveAnswer(answer: com.jarves.mh.model.AgentAnswer) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("question_id", answer.questionId)
            put("task_id", answer.taskId)
            val arr = JSONArray()
            answer.selectedOptionIds.forEach { arr.put(it) }
            put("selected_options_json", arr.toString())
            put("text_value", answer.textValue)
            put("is_custom", if (answer.isCustom) 1 else 0)
            put("answered_at", answer.answeredAt)
        }
        writableDatabase.insertWithOnConflict("agent_answers", null, values, SQLiteDatabase.CONFLICT_REPLACE)

        // Mark question as answered
        val qVal = ContentValues().apply {
            put("status", com.jarves.mh.model.QuestionStatus.ANSWERED.name)
        }
        writableDatabase.update("agent_questions", qVal, "id = ?", arrayOf(answer.questionId))

        // Return task to RUNNING if no more questions or approvals pending
        updateTaskState(answer.taskId, AgentTaskState.RUNNING)
        refreshActiveFlows()
    }

    suspend fun cancelQuestion(questionId: String) = withContext(Dispatchers.IO) {
        val qVal = ContentValues().apply {
            put("status", com.jarves.mh.model.QuestionStatus.CANCELLED.name)
        }
        writableDatabase.update("agent_questions", qVal, "id = ?", arrayOf(questionId))
        refreshActiveFlows()
    }

    private fun refreshActiveFlows() {
        val active = runCatching {
            readableDatabase.rawQuery(
                "SELECT * FROM agent_tasks WHERE state IN ('RUNNING', 'WAITING_FOR_USER', 'QUEUED') ORDER BY started_at DESC LIMIT 1",
                null,
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    AgentTask(
                        id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                        projectId = cursor.getString(cursor.getColumnIndexOrThrow("project_id")),
                        sessionId = cursor.getString(cursor.getColumnIndexOrThrow("session_id")),
                        state = AgentTaskState.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("state"))),
                        prompt = cursor.getString(cursor.getColumnIndexOrThrow("prompt")),
                        startedAtMillis = cursor.getLong(cursor.getColumnIndexOrThrow("started_at")),
                        finishedAtMillis = if (cursor.isNull(cursor.getColumnIndexOrThrow("finished_at"))) null else cursor.getLong(cursor.getColumnIndexOrThrow("finished_at")),
                        errorReason = cursor.getString(cursor.getColumnIndexOrThrow("error_reason")),
                        turnCount = cursor.getInt(cursor.getColumnIndexOrThrow("turn_count")),
                    )
                } else null
            }
        }.getOrNull()
        _activeTaskFlow.value = active

        val approvals = runCatching {
            readableDatabase.rawQuery("SELECT * FROM pending_approvals ORDER BY created_at ASC", null).use { cursor ->
                val list = mutableListOf<ToolRequest>()
                while (cursor.moveToNext()) {
                    val pathsRaw = cursor.getString(cursor.getColumnIndexOrThrow("affected_paths")).orEmpty()
                    val paths = runCatching {
                        val arr = JSONArray(pathsRaw)
                        (0 until arr.length()).map { arr.getString(it) }
                    }.getOrDefault(emptyList())

                    list += ToolRequest(
                        approvalId = cursor.getString(cursor.getColumnIndexOrThrow("approval_id")),
                        sessionId = cursor.getString(cursor.getColumnIndexOrThrow("session_id")),
                        toolName = cursor.getString(cursor.getColumnIndexOrThrow("tool_name")),
                        explanation = cursor.getString(cursor.getColumnIndexOrThrow("explanation")),
                        commandPreview = cursor.getString(cursor.getColumnIndexOrThrow("command_preview")),
                        affectedPaths = paths,
                        risk = runCatching { RiskLevel.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("risk"))) }
                            .getOrDefault(RiskLevel.REVIEW),
                    )
                }
                list
            }
        }.getOrDefault(emptyList())
        _pendingApprovalsFlow.value = approvals

        val questions = runCatching {
            readableDatabase.rawQuery("SELECT * FROM agent_questions WHERE status = 'PENDING' ORDER BY created_at ASC", null).use { cursor ->
                val list = mutableListOf<AgentQuestion>()
                while (cursor.moveToNext()) {
                    val optsRaw = cursor.getString(cursor.getColumnIndexOrThrow("options_json")).orEmpty()
                    val options = runCatching {
                        val arr = JSONArray(optsRaw)
                        val out = mutableListOf<com.jarves.mh.model.QuestionOption>()
                        for (i in 0 until arr.length()) {
                            arr.optJSONObject(i)?.let { out.add(com.jarves.mh.model.QuestionOption.fromJson(it)) }
                        }
                        out
                    }.getOrDefault(emptyList())

                    list += AgentQuestion(
                        id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                        taskId = cursor.getString(cursor.getColumnIndexOrThrow("task_id")),
                        sessionId = cursor.getString(cursor.getColumnIndexOrThrow("session_id")),
                        type = runCatching { com.jarves.mh.model.QuestionType.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("type"))) }
                            .getOrDefault(com.jarves.mh.model.QuestionType.SELECT_ONE),
                        title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                        question = cursor.getString(cursor.getColumnIndexOrThrow("question")),
                        options = options,
                        required = cursor.getInt(cursor.getColumnIndexOrThrow("required")) == 1,
                        defaultOptionId = cursor.getString(cursor.getColumnIndexOrThrow("default_option_id")),
                        allowCustomAnswer = cursor.getInt(cursor.getColumnIndexOrThrow("allow_custom")) == 1,
                        status = runCatching { com.jarves.mh.model.QuestionStatus.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("status"))) }
                            .getOrDefault(com.jarves.mh.model.QuestionStatus.PENDING),
                        createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                    )
                }
                list
            }
        }.getOrDefault(emptyList())
        _pendingQuestionsFlow.value = questions
    }

    suspend fun savePermissionGrant(grant: com.jarves.mh.model.PermissionGrant) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("id", grant.id)
            put("capability", grant.capability.identifier)
            put("project_id", grant.projectId)
            put("path_pattern", grant.pathPattern)
            put("granted_at", grant.grantedAt)
        }
        writableDatabase.insertWithOnConflict("permission_grants", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    suspend fun getPermissionGrants(): List<com.jarves.mh.model.PermissionGrant> = withContext(Dispatchers.IO) {
        val list = mutableListOf<com.jarves.mh.model.PermissionGrant>()
        readableDatabase.rawQuery("SELECT * FROM permission_grants", null).use { cursor ->
            while (cursor.moveToNext()) {
                val capStr = cursor.getString(cursor.getColumnIndexOrThrow("capability"))
                val cap = com.jarves.mh.model.CapabilityScope.fromIdentifier(capStr) ?: continue
                list += com.jarves.mh.model.PermissionGrant(
                    id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                    capability = cap,
                    projectId = cursor.getString(cursor.getColumnIndexOrThrow("project_id")),
                    pathPattern = cursor.getString(cursor.getColumnIndexOrThrow("path_pattern")),
                    grantedAt = cursor.getLong(cursor.getColumnIndexOrThrow("granted_at")),
                )
            }
        }
        list
    }

    suspend fun deletePermissionGrant(grantId: String) = withContext(Dispatchers.IO) {
        writableDatabase.delete("permission_grants", "id = ?", arrayOf(grantId))
    }

    companion object {
        private const val DATABASE_NAME = "mobile_harness.db"
        private const val DATABASE_VERSION = 3
    }
}
