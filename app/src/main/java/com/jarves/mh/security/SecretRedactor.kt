package com.jarves.mh.security

object SecretRedactor {
    private val PATTERNS = listOf(
        // Anthropic, OpenAI, OpenRouter, and general sk- API keys
        Regex("sk-(?:ant-|or-|proj-)?[A-Za-z0-9_-]{8,}") to "sk-••••",
        // Authorization headers or key-value pairs
        Regex("(?i)([\"']?(?:authorization|api[_-]?key|x-api-key|secret|token|password)[\"']?\\s*[:=]\\s*[\"']?)(?:bearer\\s+)?[A-Za-z0-9._~+/-]{6,}([\"']?)") to "$1••••$2",
        // Standalone Bearer tokens
        Regex("(?i)bearer\\s+[A-Za-z0-9._~+/-]{8,}={0,2}") to "Bearer ••••",
        // Private keys
        Regex("-----BEGIN [A-Z ]+ PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]+ PRIVATE KEY-----") to "[REDACTED PRIVATE KEY]",
    )

    fun redact(input: String?): String {
        if (input.isNullOrBlank()) return ""
        var sanitized: String = input
        for ((pattern, replacement) in PATTERNS) {
            sanitized = sanitized.replace(pattern, replacement)
        }
        return sanitized
    }
}
