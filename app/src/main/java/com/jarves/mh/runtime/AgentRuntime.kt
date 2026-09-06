package com.jarves.mh.runtime

import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.RuntimeEvent
import kotlinx.coroutines.flow.SharedFlow

interface AgentRuntime {
    val events: SharedFlow<RuntimeEvent>
    val activeSessionId: String?

    suspend fun startSession(
        projectId: String,
        projectSlug: String,
        projectKind: ProjectKind,
        prompt: String,
        conversationHistory: List<ChatMessage>,
        provider: ProviderProfile,
    ): String

    suspend fun stopSession(sessionId: String)
    suspend fun stopActiveSession()
}
