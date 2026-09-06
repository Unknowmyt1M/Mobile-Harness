package com.jarves.mh.verification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VerificationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var workspace: File

    @Before
    fun setUp() {
        workspace = tempFolder.newFolder("workspace")
    }

    @Test
    fun `detects Gradle workspace`() {
        File(workspace, "gradlew").writeText("#!/bin/sh")
        val profile = VerificationProfileDetector.detect(workspace)
        assertEquals(TestFramework.GRADLE, profile.framework)
        assertEquals("./gradlew test", profile.testCommand)
        assertEquals("./gradlew assembleDebug", profile.buildCommand)
    }

    @Test
    fun `detects Node workspace with pnpm and test script`() {
        File(workspace, "package.json").writeText("""{"scripts": {"test": "vitest run", "build": "vite build"}}""")
        File(workspace, "pnpm-lock.yaml").writeText("lockfileVersion: 5.4")

        val profile = VerificationProfileDetector.detect(workspace)
        assertEquals(TestFramework.PNPM, profile.framework)
        assertEquals("pnpm test", profile.testCommand)
        assertEquals("pnpm run build", profile.buildCommand)
    }

    @Test
    fun `detects Python workspace with pytest`() {
        File(workspace, "pyproject.toml").writeText("[tool.pytest.ini_options]")
        val profile = VerificationProfileDetector.detect(workspace)
        assertEquals(TestFramework.PYTEST, profile.framework)
        assertEquals("pytest", profile.testCommand)
    }

    @Test
    fun `detects Cargo and Go workspaces`() {
        val cargoDir = tempFolder.newFolder("cargo_ws")
        File(cargoDir, "Cargo.toml").writeText("[package]\nname = \"foo\"")
        val cargoProfile = VerificationProfileDetector.detect(cargoDir)
        assertEquals(TestFramework.CARGO, cargoProfile.framework)
        assertEquals("cargo test", cargoProfile.testCommand)

        val goDir = tempFolder.newFolder("go_ws")
        File(goDir, "go.mod").writeText("module foo.bar\ngo 1.21")
        val goProfile = VerificationProfileDetector.detect(goDir)
        assertEquals(TestFramework.GO, goProfile.framework)
        assertEquals("go test ./...", goProfile.testCommand)
    }

    @Test
    fun `parses successful test execution`() {
        val stdout = "Tests completed: 42 passed, 0 failed in 1.4s"
        val result = VerificationResultParser.parse(0, stdout, "")
        assertEquals(VerificationStatus.PASSED, result.status)
        assertEquals(0, result.exitCode)
        assertTrue(result.summary.contains("42 passed"))
    }

    @Test
    fun `parses build and compilation failures`() {
        val stderr = """
            > Task :app:compileDebugKotlin FAILED
            e: file:///App.kt:14:5 Unresolved reference: NonExistentService
            BUILD FAILED in 4s
        """.trimIndent()
        val result = VerificationResultParser.parse(1, "", stderr)
        assertEquals(VerificationStatus.BUILD_ERROR, result.status)
        assertEquals(1, result.exitCode)
        assertTrue(result.failureDiagnostic?.contains("Unresolved reference") == true)
    }

    @Test
    fun `parses syntax error`() {
        val stderr = "SyntaxError: Unexpected token '}' in src/index.js:25"
        val result = VerificationResultParser.parse(1, "", stderr)
        assertEquals(VerificationStatus.SYNTAX_ERROR, result.status)
        assertTrue(result.failureDiagnostic?.contains("SyntaxError") == true)
    }

    @Test
    fun `parses timeout result`() {
        val result = VerificationResultParser.parse(143, "", "", timedOut = true)
        assertEquals(VerificationStatus.TIMEOUT, result.status)
        assertTrue(result.summary.contains("timed out"))
    }

    @Test
    fun `redacts secrets in verification output`() {
        val stderr = "Test failed with key sk-ant-api03-1234567890abcdef1234567890abcdef-AA"
        val result = VerificationResultParser.parse(1, "", stderr)
        assertFalse(result.rawOutput.contains("1234567890abcdef"))
        assertTrue(result.rawOutput.contains("sk-••••"))
    }
}
