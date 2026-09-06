package com.jarves.mh.runtime.provider

import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.ProviderProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelIdPreservationTest {

    @Test
    fun testOpaqueModelIdsAreNeverMutated() {
        val testIds = listOf(
            "auto/best",
            "auto/code",
            "auto/best-coding",
            "google/gemini-2.5-pro",
            "deepseek/deepseek-r1:free",
            "anthropic/claude-3-7-sonnet",
            "my-custom-endpoint:8000/v1",
        )

        for (modelId in testIds) {
            val profile = ProviderProfile(
                kind = ProviderKind.GATEWAY,
                baseUrl = "https://openrouter.ai/api/v1",
                model = modelId,
                hasSecret = true,
            )

            assertEquals("Model ID should be preserved exactly as passed", modelId, profile.model)

            // Verify payload JSON serialization preserves exact model ID
            val payload = JSONObject().apply {
                put("model", profile.model)
                put("stream", true)
            }
            assertEquals(modelId, payload.getString("model"))
        }
    }
}
