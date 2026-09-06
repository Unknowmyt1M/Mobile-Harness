package com.jarves.mh.verification

import org.json.JSONObject
import java.io.File

object VerificationProfileDetector {

    fun detect(workspaceDir: File): VerificationProfile {
        if (!workspaceDir.exists() || !workspaceDir.isDirectory) {
            return VerificationProfile(
                framework = TestFramework.NONE,
                testCommand = "true",
            )
        }

        // 1. Rust
        val cargoFile = File(workspaceDir, "Cargo.toml")
        if (cargoFile.exists()) {
            return VerificationProfile(
                framework = TestFramework.CARGO,
                testCommand = "cargo test",
                buildCommand = "cargo check",
            )
        }

        // 2. Go
        val goModFile = File(workspaceDir, "go.mod")
        if (goModFile.exists()) {
            return VerificationProfile(
                framework = TestFramework.GO,
                testCommand = "go test ./...",
                buildCommand = "go build ./...",
            )
        }

        // 3. Gradle / Android / Kotlin
        val gradlew = File(workspaceDir, "gradlew")
        val buildGradleKts = File(workspaceDir, "build.gradle.kts")
        val buildGradle = File(workspaceDir, "build.gradle")
        if (gradlew.exists() || buildGradleKts.exists() || buildGradle.exists()) {
            val cmd = if (gradlew.exists()) "./gradlew test" else "gradle test"
            val buildCmd = if (gradlew.exists()) "./gradlew assembleDebug" else "gradle assembleDebug"
            return VerificationProfile(
                framework = TestFramework.GRADLE,
                testCommand = cmd,
                buildCommand = buildCmd,
            )
        }

        // 4. Node.js / TypeScript / JavaScript
        val packageJson = File(workspaceDir, "package.json")
        if (packageJson.exists()) {
            val isPnpm = File(workspaceDir, "pnpm-lock.yaml").exists()
            val isYarn = File(workspaceDir, "yarn.lock").exists()
            val (framework, runner) = when {
                isPnpm -> TestFramework.PNPM to "pnpm"
                isYarn -> TestFramework.YARN to "yarn"
                else -> TestFramework.NPM to "npm"
            }

            var hasTestScript = false
            var hasBuildScript = false
            runCatching {
                val json = JSONObject(packageJson.readText())
                val scripts = json.optJSONObject("scripts")
                if (scripts != null) {
                    hasTestScript = scripts.has("test")
                    hasBuildScript = scripts.has("build")
                }
            }

            val testCmd = if (hasTestScript) "$runner test" else "$runner run test --if-present"
            val buildCmd = if (hasBuildScript) "$runner run build" else null
            return VerificationProfile(
                framework = framework,
                testCommand = testCmd,
                buildCommand = buildCmd,
            )
        }

        // 5. Python / Pytest
        val pytestIni = File(workspaceDir, "pytest.ini")
        val pyprojectToml = File(workspaceDir, "pyproject.toml")
        val testsDir = File(workspaceDir, "tests")
        val reqsFile = File(workspaceDir, "requirements.txt")
        if (pytestIni.exists() || pyprojectToml.exists() || testsDir.exists() || reqsFile.exists()) {
            return VerificationProfile(
                framework = TestFramework.PYTEST,
                testCommand = "pytest",
            )
        }

        return VerificationProfile(
            framework = TestFramework.NONE,
            testCommand = "true",
        )
    }
}
