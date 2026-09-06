package com.jarves.mh.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectMemoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var workspaceDir: File

    @Before
    fun setUp() {
        workspaceDir = tempFolder.newFolder("workspace")
    }

    @Test
    fun `initializeIfMissing creates memory directory and standard files`() {
        ProjectMemoryStore.initializeIfMissing(workspaceDir, "my-test-app")

        val memoryDir = File(workspaceDir, ".memory")
        assertTrue(memoryDir.exists())
        assertTrue(File(memoryDir, "project.md").exists())
        assertTrue(File(memoryDir, "architecture.md").exists())
        assertTrue(File(memoryDir, "decisions.md").exists())
        assertTrue(File(memoryDir, "constraints.md").exists())
        assertTrue(File(memoryDir, "known-issues.md").exists())
        assertTrue(File(memoryDir, "agent-state.json").exists())

        val memory = ProjectMemoryStore.readMemory(workspaceDir)
        assertTrue(memory.project.contains("my-test-app"))
        assertTrue(memory.constraints.contains("Constraints & Rules"))
        assertEquals(1, memory.state.version)
    }

    @Test
    fun `updateSection redacts secrets before writing`() {
        ProjectMemoryStore.initializeIfMissing(workspaceDir, "secure-app")

        val sensitiveContent = "Use API key sk-ant-api03-1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef-AA for server."
        ProjectMemoryStore.updateSection(workspaceDir, MemorySection.CONSTRAINTS, sensitiveContent)

        val readBack = ProjectMemoryStore.readSection(workspaceDir, MemorySection.CONSTRAINTS)
        assertFalse(readBack.contains("1234567890abcdef"))
        assertTrue(readBack.contains("sk-••••"))
    }

    @Test
    fun `appendDecision and appendKnownIssue persist entries with redaction`() {
        ProjectMemoryStore.initializeIfMissing(workspaceDir, "test-app")

        ProjectMemoryStore.appendDecision(
            workspaceDir = workspaceDir,
            title = "Use Room DB",
            context = "Auth token Bearer eyJhbGciOiJIUzI1NiJ9.test was discussed",
            decision = "Adopt Room for durable persistence"
        )

        val memory = ProjectMemoryStore.readMemory(workspaceDir)
        assertTrue(memory.decisions.contains("Use Room DB"))
        assertTrue(memory.decisions.contains("Adopt Room for durable persistence"))
        assertFalse(memory.decisions.contains("eyJhbGciOiJIUzI1NiJ9.test"))
        assertTrue(memory.decisions.contains("Bearer ••••"))

        ProjectMemoryStore.appendKnownIssue(
            workspaceDir = workspaceDir,
            issue = "Port 8080 collision with key sk-1234567890abcdef1234567890abcdef",
            workaround = "Use port 8081"
        )

        val updated = ProjectMemoryStore.readMemory(workspaceDir)
        assertTrue(updated.knownIssues.contains("Port 8080 collision"))
        assertTrue(updated.knownIssues.contains("Use port 8081"))
        assertFalse(updated.knownIssues.contains("1234567890abcdef"))
        assertTrue(updated.knownIssues.contains("sk-••••"))
    }

    @Test
    fun `contextSelectionEngine generates compact prompt within budget`() {
        val memory = ProjectMemory(
            project = "A large mobile application built with Jetpack Compose.",
            architecture = "MVVM + Clean Architecture with Room and Ktor.",
            decisions = "Decided on SQLite task store v2.",
            constraints = "Never edit build.gradle directly without verification.\nNo raw root commands.",
            knownIssues = "E2BIG ARG_MAX limits on Android PRoot.",
        )

        val prompt = ContextSelectionEngine.selectContext(
            memory = memory,
            currentPrompt = "Fix the login button",
            recentFiles = listOf("MainActivity.kt", "LoginScreen.kt"),
            maxCharBudget = 2000
        )

        assertTrue(prompt.contains("<project_memory>"))
        assertTrue(prompt.contains("</project_memory>"))
        assertTrue(prompt.contains("Constraints & Rules:"))
        assertTrue(prompt.contains("Never edit build.gradle"))
        assertTrue(prompt.contains("Recently Modified Workspace Files:"))
        assertTrue(prompt.contains("MainActivity.kt"))
        assertTrue(prompt.length <= 2500)
    }

    @Test
    fun `summarizeWorkspaceFiles ignores hidden and build dirs`() {
        File(workspaceDir, "src/main").mkdirs()
        File(workspaceDir, "build/intermediates").mkdirs()
        File(workspaceDir, ".git/objects").mkdirs()

        File(workspaceDir, "src/main/App.kt").writeText("package app")
        File(workspaceDir, "build/intermediates/dummy.dex").writeText("dex")
        File(workspaceDir, ".git/objects/obj1").writeText("git")

        val files = ContextSelectionEngine.summarizeWorkspaceFiles(workspaceDir)
        assertTrue(files.any { it.replace("\\", "/").contains("src/main/App.kt") })
        assertFalse(files.any { it.contains("build") })
        assertFalse(files.any { it.contains(".git") })
    }
}
