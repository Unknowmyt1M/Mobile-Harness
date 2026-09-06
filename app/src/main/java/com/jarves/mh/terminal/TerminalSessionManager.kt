package com.jarves.mh.terminal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger

class TerminalSessionManager {
    private val counter = AtomicInteger(1)

    private val _sessions = MutableStateFlow<List<TerminalSession>>(emptyList())
    val sessions: StateFlow<List<TerminalSession>> = _sessions.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    init {
        createSession()
    }

    fun createSession(initialCwd: String = "/workspace"): TerminalSession {
        val num = counter.getAndIncrement()
        val session = TerminalSession(
            title = "T$num",
            initialCwd = initialCwd,
        )
        _sessions.update { it + session }
        if (_activeSessionId.value == null) {
            _activeSessionId.value = session.id
        }
        return session
    }

    fun selectSession(sessionId: String) {
        if (_sessions.value.any { it.id == sessionId }) {
            _activeSessionId.value = sessionId
        }
    }

    fun closeSession(sessionId: String) {
        val target = _sessions.value.firstOrNull { it.id == sessionId } ?: return
        target.close()
        _sessions.update { list -> list.filterNot { it.id == sessionId } }

        if (_activeSessionId.value == sessionId) {
            _activeSessionId.value = _sessions.value.lastOrNull()?.id
        }

        // If all sessions closed, create fresh T1
        if (_sessions.value.isEmpty()) {
            createSession()
        }
    }

    fun getActiveSession(): TerminalSession? {
        val currentId = _activeSessionId.value ?: return null
        return _sessions.value.firstOrNull { it.id == currentId }
    }

    fun closeAll() {
        _sessions.value.forEach { it.close() }
        _sessions.value = emptyList()
        _activeSessionId.value = null
    }
}
