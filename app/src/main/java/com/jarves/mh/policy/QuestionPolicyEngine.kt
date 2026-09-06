package com.jarves.mh.policy

import com.jarves.mh.model.AgentQuestion
import com.jarves.mh.model.QuestionType
import com.jarves.mh.model.ResolutionPolicy

object QuestionPolicyEngine {

    private val SENSITIVE_KEYWORDS = listOf(
        "delete", "remove", "drop database", "overwrite", "secret", "token", "password",
        "credential", "api key", "deploy", "production", "publish", "format disk", "reset",
        "terminate", "kill process", "unlink", "force push",
    )

    /**
     * Determines the final ResolutionPolicy for a question.
     * The agent may suggest AUTO_RESOLVE, but QuestionPolicyEngine has final authority:
     * - Destructive, security-sensitive, or credentials questions are strictly USER_REQUIRED.
     * - Irreversible actions (CONFIRMATION with dangerous prompt) are USER_REQUIRED.
     * - Questions without a default option/value cannot be AUTO_RESOLVE.
     */
    fun evaluate(question: AgentQuestion): ResolutionPolicy {
        // Questions without defaults can never auto-resolve
        if (question.defaultOptionId.isNullOrBlank() && question.options.none { it.isRecommended }) {
            return ResolutionPolicy.USER_REQUIRED
        }

        val text = "${question.title} ${question.question}".lowercase()

        // Check for sensitive keywords
        for (kw in SENSITIVE_KEYWORDS) {
            if (text.contains(kw)) {
                return ResolutionPolicy.USER_REQUIRED
            }
        }

        // CONFIRMATION questions about deleting or modifying core resources require user
        if (question.type == QuestionType.CONFIRMATION) {
            if (text.contains("proceed") || text.contains("confirm") || text.contains("continue")) {
                if (text.contains("change") || text.contains("replace") || text.contains("install")) {
                    return ResolutionPolicy.USER_REQUIRED
                }
            }
        }

        // Honor agent's request only if deemed safe
        return question.resolutionPolicy
    }
}
