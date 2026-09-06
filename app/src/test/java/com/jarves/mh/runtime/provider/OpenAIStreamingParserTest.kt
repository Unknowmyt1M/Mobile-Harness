package com.jarves.mh.runtime.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenAIStreamingParserTest {

    private lateinit var parser: OpenAIStreamingParser

    @Before
    fun setUp() {
        parser = OpenAIStreamingParser()
    }

    @Test
    fun testParseTextDelta() {
        val line = """data: {"choices":[{"delta":{"content":"Hello, Darko!"}}]}"""
        val chunks = parser.parseLine(line)
        assertEquals(1, chunks.size)
        val textDelta = chunks[0] as ParsedStreamChunk.TextDelta
        assertEquals("Hello, Darko!", textDelta.text)
    }

    @Test
    fun testParseReasoningDelta() {
        val line = """data: {"choices":[{"delta":{"reasoning_content":"Let's analyze the codebase."}}]}"""
        val chunks = parser.parseLine(line)
        assertEquals(1, chunks.size)
        val reasoning = chunks[0] as ParsedStreamChunk.ReasoningDelta
        assertEquals("Let's analyze the codebase.", reasoning.text)
    }

    @Test
    fun testParseIncrementalToolCall() {
        // Chunk 1: Function name & ID
        val line1 = """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_123","function":{"name":"bash","arguments":""}}]}}]}"""
        val chunks1 = parser.parseLine(line1)
        assertEquals(1, chunks1.size)

        // Chunk 2: First part of arguments
        val line2 = """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"command\":"}}]}}]}"""
        val chunks2 = parser.parseLine(line2)
        assertEquals(1, chunks2.size)

        // Chunk 3: Second part of arguments
        val line3 = """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"ls -la\"}"}}]}}]}"""
        val chunks3 = parser.parseLine(line3)
        assertEquals(1, chunks3.size)

        // Verify accumulated result
        val accumulated = parser.getAccumulatedToolCalls()
        assertEquals(1, accumulated.size)
        assertEquals("call_123", accumulated[0].id)
        assertEquals("bash", accumulated[0].name)
        assertEquals("{\"command\":\"ls -la\"}", accumulated[0].arguments.toString())
    }

    @Test
    fun testParseDone() {
        val line = "data: [DONE]"
        val chunks = parser.parseLine(line)
        assertEquals(1, chunks.size)
        assertTrue(chunks[0] is ParsedStreamChunk.Completed)
    }
}
