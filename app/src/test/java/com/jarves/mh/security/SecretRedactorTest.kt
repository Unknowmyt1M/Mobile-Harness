package com.jarves.mh.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRedactorTest {

    @Test
    fun redactsAnthropicAndOpenAiKeys() {
        val input = "Using key sk-ant-api03-abcdef123456789 and sk-proj-9876543210fedcba for request"
        val redacted = SecretRedactor.redact(input)
        assertFalse(redacted.contains("abcdef123456789"))
        assertFalse(redacted.contains("9876543210fedcba"))
        assertTrue(redacted.contains("sk-••••"))
    }

    @Test
    fun redactsAuthorizationHeaders() {
        val input = "headers: {\"Authorization\": \"Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9\", \"x-api-key\": \"secret12345\"}"
        val redacted = SecretRedactor.redact(input)
        assertFalse(redacted.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"))
        assertFalse(redacted.contains("secret12345"))
    }

    @Test
    fun preservesNormalText() {
        val normal = "Reading file src/main.js completed successfully."
        assertEquals(normal, SecretRedactor.redact(normal))
    }
}
