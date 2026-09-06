package com.jarves.mh.verification

enum class TestFramework(val displayName: String) {
    GRADLE("Gradle"),
    NPM("npm"),
    YARN("Yarn"),
    PNPM("pnpm"),
    PYTEST("pytest"),
    CARGO("Cargo"),
    GO("Go test"),
    CUSTOM("Custom"),
    NONE("None"),
}

data class VerificationProfile(
    val framework: TestFramework,
    val testCommand: String,
    val buildCommand: String? = null,
    val timeoutSeconds: Long = 180L,
)

enum class VerificationStatus {
    PASSED,
    FAILED,
    BUILD_ERROR,
    SYNTAX_ERROR,
    TIMEOUT,
    SKIPPED,
}

data class VerificationResult(
    val status: VerificationStatus,
    val exitCode: Int,
    val summary: String,
    val failureDiagnostic: String? = null,
    val rawOutput: String = "",
)
