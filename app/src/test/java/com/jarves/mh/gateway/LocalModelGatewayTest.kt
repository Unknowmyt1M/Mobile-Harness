package com.jarves.mh.gateway

import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelGatewayTest {

    @Test
    fun `gateway starts and binds to ephemeral port`() {
        val profile = ProviderProfile(kind = ProviderKind.OPENAI, model = "gpt-5.4")
        val gateway = LocalModelGateway(profile, "sk-test-key")
        val url = gateway.start()
        try {
            assertTrue(gateway.port > 0)
            assertEquals("http://127.0.0.1:${gateway.port}", url)
            assertEquals(0, gateway.usage.requestCount)
        } finally {
            gateway.stop()
        }
    }

    @Test
    fun `cost calculator correctly computes token costs`() {
        // Claude 3.5 Sonnet: $3/M prompt, $15/M completion
        // 1,000,000 prompt + 100,000 completion = 3.0 + 1.5 = 4.5
        val sonnetCost = GatewayCostCalculator.calculateCost(
            model = "claude-3-5-sonnet-20241022",
            promptTokens = 1_000_000,
            completionTokens = 100_000,
        )
        assertEquals(4.50, sonnetCost, 0.001)

        // GPT-4o: $2.50/M prompt, $10/M completion
        val gptCost = GatewayCostCalculator.calculateCost(
            model = "gpt-4o",
            promptTokens = 2_000_000,
            completionTokens = 500_000,
        )
        assertEquals(10.0, gptCost, 0.001)

        // DeepSeek Chat: $0.14/M prompt, $0.28/M completion
        val deepseekCost = GatewayCostCalculator.calculateCost(
            model = "deepseek-chat",
            promptTokens = 1_000_000,
            completionTokens = 1_000_000,
        )
        assertEquals(0.42, deepseekCost, 0.001)
    }

    @Test
    fun `gateway supports custom headers configuration`() {
        val headers = mapOf("HTTP-Referer" to "https://mobileharness.app", "X-Title" to "Mobile Harness")
        val profile = ProviderProfile(
            kind = ProviderKind.CUSTOM,
            baseUrl = "https://openrouter.ai/api/v1",
            model = "anthropic/claude-3.5-sonnet",
            customHeaders = headers,
        )
        assertEquals("https://mobileharness.app", profile.customHeaders["HTTP-Referer"])
        assertEquals("Mobile Harness", profile.customHeaders["X-Title"])
    }
}
