package com.jarves.mh.gateway

data class GatewayUsage(
    var promptTokens: Long = 0,
    var completionTokens: Long = 0,
    var totalTokens: Long = 0,
    var requestCount: Int = 0,
    var estimatedCostUsd: Double = 0.0,
)

object GatewayCostCalculator {

    data class ModelRate(val promptPerMillion: Double, val completionPerMillion: Double)

    private val RATES = mapOf(
        "claude-3-5-sonnet" to ModelRate(3.00, 15.00),
        "claude-3-5-haiku" to ModelRate(0.80, 4.00),
        "gpt-4o" to ModelRate(2.50, 10.00),
        "gpt-4o-mini" to ModelRate(0.15, 0.60),
        "deepseek-chat" to ModelRate(0.14, 0.28),
        "moonshot-v1" to ModelRate(1.20, 1.20),
    )

    private val DEFAULT_RATE = ModelRate(1.00, 3.00)

    fun calculateCost(model: String, promptTokens: Long, completionTokens: Long): Double {
        val lower = model.lowercase()
        val rate = RATES.entries.firstOrNull { (k, _) -> lower.contains(k) }?.value ?: DEFAULT_RATE
        val promptCost = (promptTokens.toDouble() / 1_000_000.0) * rate.promptPerMillion
        val completionCost = (completionTokens.toDouble() / 1_000_000.0) * rate.completionPerMillion
        return promptCost + completionCost
    }
}
