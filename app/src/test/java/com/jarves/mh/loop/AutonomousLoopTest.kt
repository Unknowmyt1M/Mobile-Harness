package com.jarves.mh.loop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AutonomousLoopTest {

    private lateinit var loopManager: AutonomousLoopManager

    @Before
    fun setUp() {
        loopManager = AutonomousLoopManager()
    }

    @Test
    fun `happy path completes all phases successfully`() {
        val session = loopManager.startLoop("task-1", "Build a feature")
        assertEquals(LoopPhase.PLAN, session.currentPhase)

        val planRes = loopManager.advancePhase(session, LoopEvent.PlanGenerated("1. Write code 2. Run test"))
        assertTrue(planRes is LoopTransitionResult.Transitioned)
        assertEquals(LoopPhase.EXECUTE, (planRes as LoopTransitionResult.Transitioned).newPhase)
        assertTrue(planRes.nextPrompt?.contains("Write code") == true)

        val execRes = loopManager.advancePhase(session, LoopEvent.ExecutionCompleted("Modified 2 files"))
        assertTrue(execRes is LoopTransitionResult.Transitioned)
        assertEquals(LoopPhase.VERIFY, (execRes as LoopTransitionResult.Transitioned).newPhase)

        val verifyRes = loopManager.advancePhase(session, LoopEvent.VerificationPassed)
        assertTrue(verifyRes is LoopTransitionResult.Completed)
        assertEquals(LoopPhase.DONE, session.currentPhase)
        assertTrue(session.isTerminated)
    }

    @Test
    fun `verification failure triggers diagnose and patch loop`() {
        val session = loopManager.startLoop(
            taskId = "task-2",
            initialPrompt = "Fix issue",
            config = LoopConfig(maxTurns = 5, maxVerificationRetries = 2)
        )
        loopManager.advancePhase(session, LoopEvent.PlanGenerated("Fix"))
        loopManager.advancePhase(session, LoopEvent.ExecutionCompleted("Fixed code"))

        // Verification fails with sensitive API key in output
        val failRes = loopManager.advancePhase(
            session,
            LoopEvent.VerificationFailed("Test failed on auth key sk-ant-api03-1234567890abcdef1234567890abcdef-AA: NullPointerException")
        )
        assertTrue(failRes is LoopTransitionResult.Transitioned)
        assertEquals(LoopPhase.DIAGNOSE, (failRes as LoopTransitionResult.Transitioned).newPhase)
        // Redaction verification
        val prompt = failRes.nextPrompt.orEmpty()
        assertTrue(prompt.contains("NullPointerException"))
        assertFalse(prompt.contains("1234567890abcdef"))
        assertTrue(prompt.contains("sk-••••"))

        // Diagnose phase
        val diagRes = loopManager.advancePhase(
            session,
            LoopEvent.DiagnosticReady("The NullPointerException is due to null token check in UserAuth.kt")
        )
        assertTrue(diagRes is LoopTransitionResult.Transitioned)
        assertEquals(LoopPhase.PATCH, (diagRes as LoopTransitionResult.Transitioned).newPhase)
        assertTrue(diagRes.nextPrompt?.contains("UserAuth.kt") == true)

        // Patch applied -> returns to VERIFY
        val patchRes = loopManager.advancePhase(session, LoopEvent.PatchApplied("Added null check in UserAuth.kt"))
        assertTrue(patchRes is LoopTransitionResult.Transitioned)
        assertEquals(LoopPhase.VERIFY, (patchRes as LoopTransitionResult.Transitioned).newPhase)

        // Second verification passes
        val verifyRes = loopManager.advancePhase(session, LoopEvent.VerificationPassed)
        assertTrue(verifyRes is LoopTransitionResult.Completed)
        assertEquals(LoopPhase.DONE, session.currentPhase)
    }

    @Test
    fun `exceeding verification retries terminates with failure`() {
        val session = loopManager.startLoop(
            taskId = "task-3",
            initialPrompt = "Hard bug",
            config = LoopConfig(maxTurns = 10, maxVerificationRetries = 2)
        )
        loopManager.advancePhase(session, LoopEvent.PlanGenerated("Plan"))
        loopManager.advancePhase(session, LoopEvent.ExecutionCompleted("Done"))

        // Attempt 1 fails -> DIAGNOSE
        loopManager.advancePhase(session, LoopEvent.VerificationFailed("fail 1"))
        loopManager.advancePhase(session, LoopEvent.DiagnosticReady("diag 1"))
        loopManager.advancePhase(session, LoopEvent.PatchApplied("patch 1"))

        // Attempt 2 fails -> DIAGNOSE
        loopManager.advancePhase(session, LoopEvent.VerificationFailed("fail 2"))
        loopManager.advancePhase(session, LoopEvent.DiagnosticReady("diag 2"))
        loopManager.advancePhase(session, LoopEvent.PatchApplied("patch 2"))

        // Attempt 3 fails -> exceeds maxVerificationRetries (2) -> Terminated FAILED
        val res = loopManager.advancePhase(session, LoopEvent.VerificationFailed("fail 3"))
        assertTrue(res is LoopTransitionResult.Terminated)
        assertEquals(LoopPhase.FAILED, (res as LoopTransitionResult.Terminated).finalPhase)
        assertTrue(session.isTerminated)
        assertTrue(res.reason.contains("retry attempts"))
    }

    @Test
    fun `user cancellation stops loop immediately`() {
        val session = loopManager.startLoop("task-4", "Do work")
        loopManager.advancePhase(session, LoopEvent.PlanGenerated("Plan"))

        val cancelRes = loopManager.advancePhase(session, LoopEvent.UserCancelRequested("Cancelled by user"))
        assertTrue(cancelRes is LoopTransitionResult.Terminated)
        assertEquals(LoopPhase.CANCELLED, (cancelRes as LoopTransitionResult.Terminated).finalPhase)
        assertTrue(session.isTerminated)
    }
}
