package com.jarves.mh.verification

import com.jarves.mh.security.SecretRedactor

object VerificationResultParser {

    fun parse(
        exitCode: Int,
        stdout: String,
        stderr: String,
        timedOut: Boolean = false,
    ): VerificationResult {
        val safeStdout = SecretRedactor.redact(stdout)
        val safeStderr = SecretRedactor.redact(stderr)
        val combined = buildString {
            if (safeStdout.isNotBlank()) appendLine(safeStdout)
            if (safeStderr.isNotBlank()) appendLine(safeStderr)
        }.trim()

        if (timedOut) {
            return VerificationResult(
                status = VerificationStatus.TIMEOUT,
                exitCode = exitCode,
                summary = "Verification process timed out.",
                failureDiagnostic = "Execution exceeded time limit. Check for infinite loops or blocking operations.",
                rawOutput = combined,
            )
        }

        if (exitCode == 0) {
            val summary = extractSuccessSummary(combined)
            return VerificationResult(
                status = VerificationStatus.PASSED,
                exitCode = 0,
                summary = summary,
                failureDiagnostic = null,
                rawOutput = combined,
            )
        }

        // Determine failure classification
        val lower = combined.lowercase()
        val status = when {
            lower.contains("syntaxerror") || lower.contains("syntax error") || lower.contains("unexpected token") -> {
                VerificationStatus.SYNTAX_ERROR
            }
            lower.contains("build failed") ||
                lower.contains("compilation error") ||
                lower.contains("compiledebug") ||
                lower.contains("could not compile") ||
                lower.contains("cannot find symbol") ||
                lower.contains("unresolved reference") -> {
                VerificationStatus.BUILD_ERROR
            }
            else -> VerificationStatus.FAILED
        }

        val diagnostic = extractFailureDiagnostic(combined)
        val summary = when (status) {
            VerificationStatus.SYNTAX_ERROR -> "Syntax error encountered during verification."
            VerificationStatus.BUILD_ERROR -> "Build/compilation failed during verification."
            else -> "Tests failed (exit code $exitCode)."
        }

        return VerificationResult(
            status = status,
            exitCode = exitCode,
            summary = summary,
            failureDiagnostic = diagnostic,
            rawOutput = combined,
        )
    }

    private fun extractSuccessSummary(output: String): String {
        val lines = output.lines()
        val match = lines.lastOrNull { line ->
            line.contains("passed", ignoreCase = true) ||
                line.contains("success", ignoreCase = true) ||
                line.contains("tests completed", ignoreCase = true)
        }
        return match?.trim() ?: "Verification passed successfully."
    }

    private fun extractFailureDiagnostic(output: String): String {
        val lines = output.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.size <= 25) {
            return lines.joinToString("\n")
        }

        val diagnosticLines = mutableListOf<String>()
        var capturingStack = false

        for (line in lines) {
            val lower = line.lowercase()
            if (lower.contains("failed") ||
                lower.contains("error") ||
                lower.contains("exception") ||
                lower.contains("assert") ||
                lower.contains("unresolved") ||
                lower.contains("cannot find") ||
                lower.startsWith("e:") ||
                lower.startsWith("error:")
            ) {
                diagnosticLines.add(line)
                capturingStack = true
                continue
            }

            if (capturingStack) {
                if (line.startsWith("at ") ||
                    line.startsWith("-->") ||
                    line.startsWith("File \"") ||
                    line.startsWith("e:") ||
                    line.startsWith("w:") ||
                    line.startsWith("Caused by:")
                ) {
                    diagnosticLines.add(line)
                } else if (diagnosticLines.size >= 25) {
                    capturingStack = false
                }
            }
        }

        if (diagnosticLines.isEmpty()) {
            return lines.takeLast(25).joinToString("\n")
        }

        return diagnosticLines.take(25).joinToString("\n")
    }
}
