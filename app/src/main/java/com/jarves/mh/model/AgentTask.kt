package com.jarves.mh.model

import java.util.UUID

enum class AgentTaskState {
    CREATED,
    QUEUED,
    RUNNING,
    WAITING_FOR_USER,
    WAITING_FOR_PROCESS,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELLED,
    RECOVERABLE,
}

data class AgentTask(
    val id: String = UUID.randomUUID().toString(),
    val projectId: String,
    val sessionId: String? = null,
    val state: AgentTaskState = AgentTaskState.CREATED,
    val prompt: String,
    val startedAtMillis: Long = System.currentTimeMillis(),
    val finishedAtMillis: Long? = null,
    val errorReason: String? = null,
    val turnCount: Int = 0,
)
