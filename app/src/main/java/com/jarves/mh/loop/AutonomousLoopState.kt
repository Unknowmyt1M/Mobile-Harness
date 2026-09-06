package com.jarves.mh.loop

enum class LoopPhase {
    PLAN,
    EXECUTE,
    VERIFY,
    DIAGNOSE,
    PATCH,
    DONE,
    FAILED,
    CANCELLED,
}

data class LoopConfig(
    val maxTurns: Int = 5,
    val maxVerificationRetries: Int = 3,
    val timeoutSeconds: Long = 600L,
    val autoVerify: Boolean = true,
)

data class LoopIteration(
    val turn: Int,
    val phase: LoopPhase,
    val summary: String,
    val diagnostic: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

data class LoopSessionState(
    val taskId: String,
    val initialPrompt: String,
    val config: LoopConfig = LoopConfig(),
    var currentTurn: Int = 1,
    var retryCount: Int = 0,
    var currentPhase: LoopPhase = LoopPhase.PLAN,
    val history: MutableList<LoopIteration> = mutableListOf(),
    var isTerminated: Boolean = false,
    var terminationReason: String? = null,
)

sealed class LoopEvent {
    data class PlanGenerated(val planText: String) : LoopEvent()
    data class ExecutionCompleted(val outputSummary: String) : LoopEvent()
    object VerificationPassed : LoopEvent()
    data class VerificationFailed(val failureOutput: String) : LoopEvent()
    data class DiagnosticReady(val analysis: String) : LoopEvent()
    data class PatchApplied(val patchSummary: String) : LoopEvent()
    data class UserCancelRequested(val reason: String = "User stopped the task") : LoopEvent()
}

sealed class LoopTransitionResult {
    data class Transitioned(val newPhase: LoopPhase, val nextPrompt: String? = null) : LoopTransitionResult()
    data class Completed(val summary: String) : LoopTransitionResult()
    data class Terminated(val finalPhase: LoopPhase, val reason: String) : LoopTransitionResult()
}
