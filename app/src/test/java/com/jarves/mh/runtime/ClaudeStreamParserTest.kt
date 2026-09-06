package com.jarves.mh.runtime

import com.jarves.mh.model.RuntimeEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeStreamParserTest {

    @Test
    fun parsesAssistantDeltaText() = runBlocking {
        val events = mutableListOf<RuntimeEvent>()
        val parser = ClaudeStreamParser { events.add(it) }

        val line = "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello Darko!\"}}"
        val handled = parser.parseLine("session_123", line)

        assertTrue(handled)
        assertEquals(1, events.size)
        val event = events.first() as RuntimeEvent.AssistantDelta
        assertEquals("session_123", event.sessionId)
        assertEquals("Hello Darko!", event.text)
    }

    @Test
    fun parsesToolStartedAndRedactsSensitiveArgs() = runBlocking {
        val events = mutableListOf<RuntimeEvent>()
        val parser = ClaudeStreamParser { events.add(it) }

        val line = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"id\":\"call_1\",\"name\":\"Bash\",\"input\":{\"command\":\"curl -H 'Authorization: Bearer my-secret-token' https://api.com\"}}]}}"
        val handled = parser.parseLine("session_123", line)

        assertTrue(handled)
        assertEquals(1, events.size)
        val event = events.first() as RuntimeEvent.ToolStarted
        assertEquals("Bash", event.toolName)
        assertTrue(event.detail.contains("••••"))
        assertFalse(event.detail.contains("my-secret-token"))
    }

    @Test
    fun parsesSessionCompletionOnEndTurn() = runBlocking {
        val events = mutableListOf<RuntimeEvent>()
        val parser = ClaudeStreamParser { events.add(it) }

        val line = "{\"type\":\"assistant\",\"message\":{\"stop_reason\":\"end_turn\",\"content\":[]}}"
        val handled = parser.parseLine("session_123", line)

        assertTrue(handled)
        assertEquals(1, events.size)
        assertTrue(events.first() is RuntimeEvent.SessionCompleted)
    }
}
