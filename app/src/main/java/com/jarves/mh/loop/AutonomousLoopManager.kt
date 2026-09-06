package com.jarves.mh.loop

import com.jarves.mh.security.SecretRedactor

class AutonomousLoopManager {

    fun startLoop(
        taskId: String,
        initialPrompt: String,
        config: LoopConfig = LoopConfig(),
    ): LoopSessionState {
        val session = LoopSessionState(
            taskId = taskId,
            initialPrompt = initialPrompt,
            config = config,
            currentTurn = 1,
            retryCount = 0,
            currentPhase = LoopPhase.PLAN,
        )
        session.history.add(
            LoopIteration(
                turn = 1,
                phase = LoopPhase.PLAN,
                summary = "Initial autonomous loop started for: ${initialPrompt.take(120)}",
            )
        )
        return session
    }

    fun advancePhase(session: LoopSessionState, event: LoopEvent): LoopTransitionResult {
        if (session.isTerminated) {
            return LoopTransitionResult.Terminated(
                session.currentPhase,
                session.terminationReason ?: "Session already terminated",
            )
        }

        // Cancellation always immediately terminates
        if (event is LoopEvent.UserCancelRequested) {
            session.isTerminated = true
            session.currentPhase = LoopPhase.CANCELLED
            session.terminationReason = event.reason
            return LoopTransitionResult.Terminated(LoopPhase.CANCELLED, event.reason)
        }

        // Check if max turns exceeded
        if (session.currentTurn > session.config.maxTurns) {
            session.isTerminated = true
            session.currentPhase = LoopPhase.FAILED
            val reason = "Autonomous turn budget exceeded (${session.config.maxTurns} turns)."
            session.terminationReason = reason
            return LoopTransitionResult.Terminated(LoopPhase.FAILED, reason)
        }

        return when (event) {
            is LoopEvent.PlanGenerated -> {
                session.currentPhase = LoopPhase.EXECUTE
                session.history.add(
                    LoopIteration(
                        turn = session.currentTurn,
                        phase = LoopPhase.PLAN,
                        summary = event.planText.take(200),
                    )
                )
                val prompt = buildExecutionPrompt(session.initialPrompt, event.planText)
                LoopTransitionResult.Transitioned(LoopPhase.EXECUTE, prompt)
            }

            is LoopEvent.ExecutionCompleted -> {
                session.history.add(
                    LoopIteration(
                        turn = session.currentTurn,
                        phase = LoopPhase.EXECUTE,
                        summary = event.outputSummary.take(200),
                    )
                )
                if (session.config.autoVerify) {
                    session.currentPhase = LoopPhase.VERIFY
                    LoopTransitionResult.Transitioned(LoopPhase.VERIFY, null)
                } else {
                    session.isTerminated = true
                    session.currentPhase = LoopPhase.DONE
                    LoopTransitionResult.Completed("Task execution completed without auto-verification.")
                }
            }

            is LoopEvent.VerificationPassed -> {
                session.isTerminated = true
                session.currentPhase = LoopPhase.DONE
                session.history.add(
                    LoopIteration(
                        turn = session.currentTurn,
                        phase = LoopPhase.VERIFY,
                        summary = "Verification succeeded.",
                    )
                )
                LoopTransitionResult.Completed("Autonomous engineering loop succeeded and verified.")
            }

            is LoopEvent.VerificationFailed -> {
                session.retryCount++
                session.currentTurn++
                val safeOutput = SecretRedactor.redact(event.failureOutput)

                if (session.retryCount > session.config.maxVerificationRetries) {
                    session.isTerminated = true
                    session.currentPhase = LoopPhase.FAILED
                    val reason = "Verification failed after ${session.config.maxVerificationRetries} retry attempts."
                    session.terminationReason = reason
                    return LoopTransitionResult.Terminated(LoopPhase.FAILED, reason)
                }

                if (session.currentTurn > session.config.maxTurns) {
                    session.isTerminated = true
                    session.currentPhase = LoopPhase.FAILED
                    val reason = "Turn budget of ${session.config.maxTurns} reached during verification retry."
                    session.terminationReason = reason
                    return LoopTransitionResult.Terminated(LoopPhase.FAILED, reason)
                }

                session.currentPhase = LoopPhase.DIAGNOSE
                session.history.add(
                    LoopIteration(
                        turn = session.currentTurn,
                        phase = LoopPhase.VERIFY,
                        summary = "Verification failed (attempt ${session.retryCount}/${session.config.maxVerificationRetries})",
                        diagnostic = safeOutput.take(500),
                    )
                )
                val diagPrompt = buildDiagnosticPrompt(session, safeOutput)
                LoopTransitionResult.Transitioned(LoopPhase.DIAGNOSE, diagPrompt)
            }

            is LoopEvent.DiagnosticReady -> {
                session.currentPhase = LoopPhase.PATCH
                session.history.add(
                    LoopIteration(
                        turn = session.currentTurn,
                        phase = LoopPhase.DIAGNOSE,
                        summary = event.analysis.take(200),
                    )
                )
                val patchPrompt = buildPatchPrompt(session, event.analysis)
                LoopTransitionResult.Transitioned(LoopPhase.PATCH, patchPrompt)
            }

            is LoopEvent.PatchApplied -> {
                session.currentPhase = LoopPhase.VERIFY
                session.history.add(
                    LoopIteration(
                        turn = session.currentTurn,
                        phase = LoopPhase.PATCH,
                        summary = event.patchSummary.take(200),
                    )
                )
                LoopTransitionResult.Transitioned(LoopPhase.VERIFY, null)
            }

            is LoopEvent.UserCancelRequested -> {
                // Handled above
                LoopTransitionResult.Terminated(LoopPhase.CANCELLED, event.reason)
            }
        }
    }

    private fun buildExecutionPrompt(initialPrompt: String, plan: String): String {
        return buildString {
            appendLine("The implementation plan has been established:")
            appendLine(plan)
            appendLine()
            appendLine("Proceed with executing the implementation for task:")
            appendLine(initialPrompt)
        }
    }

    private fun buildDiagnosticPrompt(session: LoopSessionState, failureOutput: String): String {
        return buildString {
            appendLine("[AUTONOMOUS LOOP - VERIFICATION FAILED (Attempt ${session.retryCount}/${session.config.maxVerificationRetries})]")
            appendLine("Automated project verification failed with the following diagnostics:")
            appendLine("```")
            appendLine(failureOutput.take(1500))
            appendLine("```")
            appendLine()
            appendLine("Analyze the root cause of this failure and produce a diagnosis explaining why the test/build failed and what files need modification.")
        }
    }

    private fun buildPatchPrompt(session: LoopSessionState, analysis: String): String {
        return buildString {
            appendLine("[AUTONOMOUS LOOP - APPLY FIX]")
            appendLine("Based on diagnosis:")
            appendLine(analysis)
            appendLine()
            appendLine("Apply the minimal, targeted code changes to fix the error. Once edited, prepare for re-verification.")
        }
    }
}
