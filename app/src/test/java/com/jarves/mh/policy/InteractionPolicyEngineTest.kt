package com.jarves.mh.policy

import com.jarves.mh.model.AgentQuestion
import com.jarves.mh.model.CapabilityScope
import com.jarves.mh.model.PermissionGrant
import com.jarves.mh.model.QuestionOption
import com.jarves.mh.model.QuestionType
import com.jarves.mh.model.ResolutionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractionPolicyEngineTest {

    @Test
    fun testActiveGrantAllowsExecutionWithoutApproval() {
        val grant = PermissionGrant(
            capability = CapabilityScope.FILESYSTEM_WRITE,
            projectId = "proj_123",
            pathPattern = "src/",
        )
        val decision = AgentPolicyEngine.evaluate(
            ToolEvaluationRequest(
                toolName = "Edit",
                paths = listOf("src/Main.kt"),
                projectId = "proj_123",
                explicitCapability = CapabilityScope.FILESYSTEM_WRITE,
                activeGrants = listOf(grant),
            ),
        )
        assertEquals(PolicyDecision.Allow, decision)
    }

    @Test
    fun testGrantForDifferentProjectDoesNotBypassApproval() {
        val grant = PermissionGrant(
            capability = CapabilityScope.FILESYSTEM_WRITE,
            projectId = "other_proj",
            pathPattern = "src/",
        )
        val decision = AgentPolicyEngine.evaluate(
            ToolEvaluationRequest(
                toolName = "Edit",
                paths = listOf("src/Main.kt"),
                projectId = "proj_123",
                explicitCapability = CapabilityScope.FILESYSTEM_WRITE,
                activeGrants = listOf(grant),
            ),
        )
        assertTrue(decision is PolicyDecision.RequireApproval)
    }

    @Test
    fun testQuestionPolicyEnforcesUserRequiredForDangerousKeywords() {
        val question = AgentQuestion(
            sessionId = "s1",
            taskId = "t1",
            title = "Confirm deletion",
            question = "Do you want to delete database and drop tables?",
            type = QuestionType.CONFIRMATION,
            resolutionPolicy = ResolutionPolicy.AUTO_RESOLVE,
            defaultOptionId = "opt_yes",
            options = listOf(
                QuestionOption("opt_yes", "Yes"),
                QuestionOption("opt_no", "No"),
            ),
        )
        val policy = QuestionPolicyEngine.evaluate(question)
        assertEquals(ResolutionPolicy.USER_REQUIRED, policy)
    }

    @Test
    fun testQuestionPolicyAllowsAutoResolveForSafeChoice() {
        val question = AgentQuestion(
            sessionId = "s1",
            taskId = "t1",
            title = "Select port",
            question = "Which port should we run the dev server on: 3000 or 8080?",
            type = QuestionType.SELECT_ONE,
            resolutionPolicy = ResolutionPolicy.AUTO_RESOLVE,
            defaultOptionId = "opt_3000",
            options = listOf(
                QuestionOption("opt_3000", "Port 3000", isRecommended = true),
                QuestionOption("opt_8080", "Port 8080"),
            ),
        )
        val policy = QuestionPolicyEngine.evaluate(question)
        assertEquals(ResolutionPolicy.AUTO_RESOLVE, policy)
    }
}
