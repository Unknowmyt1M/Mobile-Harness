package com.jarves.mh.runtime.provider

import com.jarves.mh.model.ChatMessage
import com.jarves.mh.runtime.NativeModelAgentRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

class ModelProtocolAdaptersTest {

    /**
     * An InputStream that serves bytes from an initial payload,
     * and then either throws an IOException (simulating abrupt socket closure)
     * or returns -1 (EOF).
     */
    private class FaultyInputStream(
        payload: String,
        private val throwAfterBytes: Boolean = false,
        private val exceptionMessage: String = "unexpected end of stream on com.android.okhttp.Address@b2b0904",
    ) : InputStream() {
        private val bytes = payload.toByteArray(StandardCharsets.UTF_8)
        private var index = 0

        override fun read(): Int {
            if (index < bytes.size) {
                return bytes[index++].toInt() and 0xFF
            }
            if (throwAfterBytes) {
                throw IOException(exceptionMessage)
            }
            return -1
        }
    }

    @Test
    fun `regression test A - valid stream with DONE then unexpected end of stream succeeds`() = runBlocking {
        val payload = """
            data: {"choices":[{"delta":{"content":"Hello"}}]}
            data: {"choices":[{"delta":{"content":" world!"}}]}
            data: [DONE]
        """.trimIndent() + "\n"

        val inputStream = FaultyInputStream(payload, throwAfterBytes = true)
        val parser = OpenAIStreamingParser()
        val received = mutableListOf<ParsedStreamChunk>()

        streamSseWithCompletionCheck(
            inputStream = inputStream,
            parser = parser,
            onChunk = { received.add(it) },
        )

        // Verifications:
        // Text chunks were received
        assertEquals(3, received.size)
        assertTrue(received[0] is ParsedStreamChunk.TextDelta)
        assertEquals("Hello", (received[0] as ParsedStreamChunk.TextDelta).text)
        assertTrue(received[1] is ParsedStreamChunk.TextDelta)
        assertEquals(" world!", (received[1] as ParsedStreamChunk.TextDelta).text)
        // Terminal [DONE] chunk was received
        assertTrue(received[2] is ParsedStreamChunk.Completed)
        assertEquals("stop", (received[2] as ParsedStreamChunk.Completed).finishReason)
    }

    @Test
    fun `regression test B - content received then connection closes BEFORE DONE fails with incomplete stream error`() = runBlocking {
        val payload = """
            data: {"choices":[{"delta":{"content":"Incomplete message..."}}]}
        """.trimIndent() + "\n"

        // Socket closes abruptly BEFORE [DONE] or finish_reason
        val inputStream = FaultyInputStream(payload, throwAfterBytes = true)
        val parser = OpenAIStreamingParser()
        val received = mutableListOf<ParsedStreamChunk>()

        try {
            streamSseWithCompletionCheck(
                inputStream = inputStream,
                parser = parser,
                onChunk = { received.add(it) },
            )
            fail("Expected streamSseWithCompletionCheck to throw IOException on incomplete stream")
        } catch (e: IOException) {
            assertTrue(
                "Error message should clearly state premature disconnect: ${e.message}",
                e.message?.contains("Stream disconnected prematurely before terminal completion signal") == true ||
                    e.message?.contains("unexpected end of stream") == true,
            )
        }

        // Even though 1 chunk was received before the drop, the turn is not considered complete
        assertEquals(1, received.size)
    }

    @Test
    fun `regression test B2 - stream ends with clean EOF but without DONE fails as incomplete`() = runBlocking {
        val payload = """
            data: {"choices":[{"delta":{"content":"Truncated response."}}]}
        """.trimIndent() + "\n"

        // Socket ends with clean EOF (throwAfterBytes = false) without [DONE]
        val inputStream = FaultyInputStream(payload, throwAfterBytes = false)
        val parser = OpenAIStreamingParser()
        val received = mutableListOf<ParsedStreamChunk>()

        try {
            streamSseWithCompletionCheck(
                inputStream = inputStream,
                parser = parser,
                onChunk = { received.add(it) },
            )
            fail("Expected streamSseWithCompletionCheck to fail because no completion signal was received")
        } catch (e: IOException) {
            assertTrue(
                "Error message should mention missing explicit completion signal",
                e.message?.contains("no explicit protocol completion signal was received") == true,
            )
        }
    }

    @Test
    fun `regression test C - zero content with immediate connection failure throws IOException allowing retry`() = runBlocking {
        val inputStream = FaultyInputStream("", throwAfterBytes = true)
        val parser = OpenAIStreamingParser()
        val received = mutableListOf<ParsedStreamChunk>()

        try {
            streamSseWithCompletionCheck(
                inputStream = inputStream,
                parser = parser,
                onChunk = { received.add(it) },
            )
            fail("Expected streamSseWithCompletionCheck to fail on cold connection drop")
        } catch (e: IOException) {
            assertEquals("unexpected end of stream on com.android.okhttp.Address@b2b0904", e.message)
        }

        assertEquals(0, received.size)
    }

    @Test
    fun `regression test D - normal SSE stream with clean EOF succeeds`() = runBlocking {
        val payload = """
            data: {"choices":[{"delta":{"content":"Clean response"}}]}
            data: [DONE]
        """.trimIndent() + "\n"

        val inputStream = ByteArrayInputStream(payload.toByteArray(StandardCharsets.UTF_8))
        val parser = OpenAIStreamingParser()
        val received = mutableListOf<ParsedStreamChunk>()

        streamSseWithCompletionCheck(
            inputStream = inputStream,
            parser = parser,
            onChunk = { received.add(it) },
        )

        assertEquals(2, received.size)
        assertEquals("Clean response", (received[0] as ParsedStreamChunk.TextDelta).text)
        assertTrue(received[1] is ParsedStreamChunk.Completed)
    }

    @Test
    fun `regression test E - tool call stream with finish_reason tool_calls succeeds even if socket closes after`() = runBlocking {
        val payload = """
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_99","function":{"name":"bash","arguments":"{\"cmd\":\"ls\"}"}}]}}]}
            data: {"choices":[{"finish_reason":"tool_calls"}]}
        """.trimIndent() + "\n"

        val inputStream = FaultyInputStream(payload, throwAfterBytes = true)
        val parser = OpenAIStreamingParser()
        val received = mutableListOf<ParsedStreamChunk>()

        streamSseWithCompletionCheck(
            inputStream = inputStream,
            parser = parser,
            onChunk = { received.add(it) },
        )

        val toolCalls = parser.getAccumulatedToolCalls()
        assertEquals(1, toolCalls.size)
        assertEquals("call_99", toolCalls[0].id)
        assertEquals("bash", toolCalls[0].name)
        assertEquals("{\"cmd\":\"ls\"}", toolCalls[0].arguments.toString())

        // Completion was recognized via finish_reason: "tool_calls"
        val completion = received.filterIsInstance<ParsedStreamChunk.Completed>()
        assertEquals(1, completion.size)
        assertEquals("tool_calls", completion[0].finishReason)
    }

    @Test
    fun `test buildSanitizedMessages on new chat drops leading assistant greeting and avoids duplicate prompt`() {
        val greeting = ChatMessage(fromUser = false, text = "Hi! Tell me what you want to build or change.")
        val prompt = "Create a counter app"
        // MainViewModel adds the user prompt to state.messages before calling startSession
        val userMsgInHistory = ChatMessage(fromUser = true, text = prompt)
        val history = listOf(greeting, userMsgInHistory)

        val messages = NativeModelAgentRuntime.buildSanitizedMessages(
            systemPrompt = "You are an agent",
            conversationHistory = history,
            currentPrompt = prompt,
        )

        // Must have exactly:
        // index 0: system
        // index 1: user ("Create a counter app")
        // NO leading assistant greeting, NO duplicate user prompt!
        assertEquals(2, messages.length())
        val sysObj = messages.getJSONObject(0)
        assertEquals("system", sysObj.getString("role"))
        assertEquals("You are an agent", sysObj.getString("content"))

        val userObj = messages.getJSONObject(1)
        assertEquals("user", userObj.getString("role"))
        assertEquals("Create a counter app", userObj.getString("content"))
    }

    @Test
    fun `test buildSanitizedMessages on second message filters blank work segments and merges correctly`() {
        val prompt1 = "Create a counter"
        val history = listOf(
            ChatMessage(fromUser = false, text = "Hi! Tell me what you want to build or change."),
            ChatMessage(fromUser = true, text = prompt1),
            ChatMessage(fromUser = false, text = "", workItems = listOf()), // dummy work segment marker
            ChatMessage(fromUser = false, text = "I created the counter for you."),
            ChatMessage(fromUser = true, text = "Now add a reset button"), // current prompt in history
        )

        val currentPrompt = "Now add a reset button"
        val messages = NativeModelAgentRuntime.buildSanitizedMessages(
            systemPrompt = "System instruction",
            conversationHistory = history,
            currentPrompt = currentPrompt,
        )

        // Expected sequence:
        // 0: system
        // 1: user ("Create a counter")
        // 2: assistant ("I created the counter for you.")
        // 3: user ("Now add a reset button")
        assertEquals(4, messages.length())

        assertEquals("system", messages.getJSONObject(0).getString("role"))

        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertEquals("Create a counter", messages.getJSONObject(1).getString("content"))

        assertEquals("assistant", messages.getJSONObject(2).getString("role"))
        assertEquals("I created the counter for you.", messages.getJSONObject(2).getString("content"))

        assertEquals("user", messages.getJSONObject(3).getString("role"))
        assertEquals("Now add a reset button", messages.getJSONObject(3).getString("content"))
    }

    @Test
    fun `test buildSanitizedMessages merges consecutive same-role messages for strict alternating roles`() {
        val history = listOf(
            ChatMessage(fromUser = true, text = "Part 1 of my question"),
            ChatMessage(fromUser = true, text = "Part 2 of my question"),
            ChatMessage(fromUser = false, text = "Answer part 1"),
            ChatMessage(fromUser = false, text = "Answer part 2"),
        )

        val messages = NativeModelAgentRuntime.buildSanitizedMessages(
            systemPrompt = "System",
            conversationHistory = history,
            currentPrompt = "Final question",
        )

        // Expected sequence:
        // 0: system
        // 1: user ("Part 1 of my question\n\nPart 2 of my question")
        // 2: assistant ("Answer part 1\n\nAnswer part 2")
        // 3: user ("Final question")
        assertEquals(4, messages.length())

        assertEquals("system", messages.getJSONObject(0).getString("role"))

        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertEquals("Part 1 of my question\n\nPart 2 of my question", messages.getJSONObject(1).getString("content"))

        assertEquals("assistant", messages.getJSONObject(2).getString("role"))
        assertEquals("Answer part 1\n\nAnswer part 2", messages.getJSONObject(2).getString("content"))

        assertEquals("user", messages.getJSONObject(3).getString("role"))
        assertEquals("Final question", messages.getJSONObject(3).getString("content"))
    }
}
