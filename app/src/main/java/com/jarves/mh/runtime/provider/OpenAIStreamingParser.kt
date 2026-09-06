package com.jarves.mh.runtime.provider

import org.json.JSONArray
import org.json.JSONObject

sealed interface ParsedStreamChunk {
    data class TextDelta(val text: String) : ParsedStreamChunk
    data class ReasoningDelta(val text: String) : ParsedStreamChunk
    data class ToolCallDelta(
        val index: Int,
        val id: String?,
        val name: String?,
        val argumentsDelta: String?,
    ) : ParsedStreamChunk
    data class Completed(val finishReason: String?) : ParsedStreamChunk
}

class OpenAIStreamingParser {
    // Accumulators for multi-chunk tool calls
    data class ToolCallAccumulator(
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder(),
    )

    private val toolAccumulators = mutableMapOf<Int, ToolCallAccumulator>()

    fun getAccumulatedToolCalls(): List<ToolCallAccumulator> {
        return toolAccumulators.toSortedMap().values.toList()
    }

    fun reset() {
        toolAccumulators.clear()
    }

    /**
     * Parses a single line or data line from an SSE stream.
     * Line should typically start with "data: " or be the JSON payload.
     */
    fun parseLine(line: String): List<ParsedStreamChunk> {
        val trimmed = line.trim()
        if (!trimmed.startsWith("data:")) return emptyList()
        val payload = trimmed.removePrefix("data:").trim()
        if (payload == "[DONE]") {
            return listOf(ParsedStreamChunk.Completed("stop"))
        }
        if (payload.isBlank()) return emptyList()

        return runCatching {
            val json = JSONObject(payload)
            val results = mutableListOf<ParsedStreamChunk>()

            // 1. OpenAI Chat /v1/chat/completions schema
            val choices = json.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val choice = choices.getJSONObject(0)
                val delta = choice.optJSONObject("delta")
                val finishReason = choice.optString("finish_reason").takeIf { it.isNotBlank() && it != "null" }

                if (delta != null) {
                    // Content text delta
                    val content = delta.optString("content")
                    if (content.isNotBlank() && content != "null") {
                        results.add(ParsedStreamChunk.TextDelta(content))
                    }

                    // Reasoning / thinking delta (DeepSeek R1, OpenAI o-series, etc.)
                    val reasoning = delta.optString("reasoning_content")
                    if (reasoning.isNotBlank() && reasoning != "null") {
                        results.add(ParsedStreamChunk.ReasoningDelta(reasoning))
                    }

                    // Tool calls
                    val toolCalls = delta.optJSONArray("tool_calls")
                    if (toolCalls != null) {
                        for (i in 0 until toolCalls.length()) {
                            val tc = toolCalls.getJSONObject(i)
                            val index = tc.optInt("index", i)
                            val id = tc.optString("id").takeIf { it.isNotBlank() }
                            val fn = tc.optJSONObject("function")
                            val name = fn?.optString("name")?.takeIf { it.isNotBlank() }
                            val args = fn?.optString("arguments")?.takeIf { it.isNotEmpty() }

                            val accum = toolAccumulators.getOrPut(index) { ToolCallAccumulator() }
                            if (id != null) accum.id = id
                            if (name != null) accum.name = name
                            if (args != null) accum.arguments.append(args)

                            results.add(
                                ParsedStreamChunk.ToolCallDelta(
                                    index = index,
                                    id = id,
                                    name = name,
                                    argumentsDelta = args,
                                )
                            )
                        }
                    }
                }

                if (finishReason != null) {
                    results.add(ParsedStreamChunk.Completed(finishReason))
                }
            }

            // 2. OpenAI Responses /v1/responses schema
            val type = json.optString("type")
            when (type) {
                "response.output_item.added" -> {
                    val item = json.optJSONObject("item")
                    if (item?.optString("type") == "function_call") {
                        val callId = item.optString("call_id")
                        val name = item.optString("name")
                        toolAccumulators[0] = ToolCallAccumulator(id = callId, name = name)
                        results.add(ParsedStreamChunk.ToolCallDelta(0, callId, name, null))
                    }
                }
                "response.output_item.delta" -> {
                    val delta = json.optJSONObject("delta")
                    val text = delta?.optString("text")
                    if (!text.isNullOrBlank()) {
                        results.add(ParsedStreamChunk.TextDelta(text))
                    }
                    val args = delta?.optString("arguments")
                    if (!args.isNullOrBlank()) {
                        val accum = toolAccumulators.getOrPut(0) { ToolCallAccumulator() }
                        accum.arguments.append(args)
                        results.add(ParsedStreamChunk.ToolCallDelta(0, null, null, args))
                    }
                }
                "response.completed", "response.done" -> {
                    results.add(ParsedStreamChunk.Completed("stop"))
                }
            }

            results
        }.getOrElse {
            emptyList()
        }
    }
}
