package com.jarves.mh.policy

import com.jarves.mh.model.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPolicyEngineTest {

    @Test
    fun safeToolsAreAllowed() {
        val decision = AgentPolicyEngine.evaluate(ToolEvaluationRequest(toolName = "Read", paths = listOf("src/App.kt")))
        assertEquals(PolicyDecision.Allow, decision)
    }

    @Test
    fun safeBashCommandsAreAllowed() {
        val decision = AgentPolicyEngine.evaluate(ToolEvaluationRequest(toolName = "Bash", command = "git status"))
        assertEquals(PolicyDecision.Allow, decision)
    }

    @Test
    fun fileModificationsRequireReview() {
        val decision = AgentPolicyEngine.evaluate(ToolEvaluationRequest(toolName = "Edit", paths = listOf("src/App.kt")))
        assertTrue(decision is PolicyDecision.RequireApproval)
        assertEquals(RiskLevel.REVIEW, (decision as PolicyDecision.RequireApproval).level)
    }

    @Test
    fun destructiveCommandsAreHighRisk() {
        val decision = AgentPolicyEngine.evaluate(ToolEvaluationRequest(toolName = "Bash", command = "rm -rf node_modules"))
        assertTrue(decision is PolicyDecision.RequireApproval)
        assertEquals(RiskLevel.HIGH, (decision as PolicyDecision.RequireApproval).level)
    }

    @Test
    fun pathTraversalIsBlocked() {
        val decision = AgentPolicyEngine.evaluate(ToolEvaluationRequest(toolName = "Edit", paths = listOf("../../../etc/passwd")))
        assertTrue(decision is PolicyDecision.Block)
    }

    @Test
    fun secretAccessIsBlocked() {
        val decision = AgentPolicyEngine.evaluate(ToolEvaluationRequest(toolName = "Bash", command = "cat /data/data/com.jarves.mh/shared_prefs/pocket_secrets.xml"))
        assertTrue(decision is PolicyDecision.Block)
    }
}
