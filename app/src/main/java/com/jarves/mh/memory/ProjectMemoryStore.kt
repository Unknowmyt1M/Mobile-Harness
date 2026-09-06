package com.jarves.mh.memory

import com.jarves.mh.security.SecretRedactor
import org.json.JSONObject
import java.io.File

object ProjectMemoryStore {
    const val MEMORY_DIR_NAME = ".memory"
    private const val STATE_FILE_NAME = "agent-state.json"

    fun getMemoryDir(workspaceDir: File): File {
        return File(workspaceDir, MEMORY_DIR_NAME)
    }

    fun initializeIfMissing(workspaceDir: File, projectName: String) {
        val memoryDir = getMemoryDir(workspaceDir)
        if (!memoryDir.exists()) {
            memoryDir.mkdirs()
        }

        val projectFile = File(memoryDir, MemorySection.PROJECT.fileName)
        if (!projectFile.exists()) {
            projectFile.writeText(
                """
                # Project: $projectName
                
                ## Purpose
                Workspace for $projectName managed by Mobile Harness.
                
                ## Stack & Dependencies
                To be determined / populated by agent during task execution.
                """.trimIndent() + "\n"
            )
        }

        val archFile = File(memoryDir, MemorySection.ARCHITECTURE.fileName)
        if (!archFile.exists()) {
            archFile.writeText(
                """
                # Architecture & Components
                
                ## Directory Layout
                Root workspace containing source and configuration.
                
                ## Conventions
                Follow standard conventions for this project type.
                """.trimIndent() + "\n"
            )
        }

        val decisionsFile = File(memoryDir, MemorySection.DECISIONS.fileName)
        if (!decisionsFile.exists()) {
            decisionsFile.writeText(
                """
                # Architectural Decisions
                
                ## Initial Setup
                - Date: ${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())}
                - Decision: Initialized autonomous workspace memory.
                """.trimIndent() + "\n"
            )
        }

        val constraintsFile = File(memoryDir, MemorySection.CONSTRAINTS.fileName)
        if (!constraintsFile.exists()) {
            constraintsFile.writeText(
                """
                # Constraints & Rules
                - Do not write unverified code or assume missing libraries.
                - Never commit or log raw credentials or API keys.
                - Prefer minimal reliable fixes over giant rewrites.
                """.trimIndent() + "\n"
            )
        }

        val issuesFile = File(memoryDir, MemorySection.KNOWN_ISSUES.fileName)
        if (!issuesFile.exists()) {
            issuesFile.writeText(
                """
                # Known Issues & Workarounds
                No known issues recorded yet.
                """.trimIndent() + "\n"
            )
        }

        val stateFile = File(memoryDir, STATE_FILE_NAME)
        if (!stateFile.exists()) {
            updateState(workspaceDir, AgentStateData())
        }
    }

    fun readMemory(workspaceDir: File): ProjectMemory {
        val memoryDir = getMemoryDir(workspaceDir)
        if (!memoryDir.exists()) {
            return ProjectMemory()
        }

        val project = readFile(File(memoryDir, MemorySection.PROJECT.fileName))
        val arch = readFile(File(memoryDir, MemorySection.ARCHITECTURE.fileName))
        val decisions = readFile(File(memoryDir, MemorySection.DECISIONS.fileName))
        val constraints = readFile(File(memoryDir, MemorySection.CONSTRAINTS.fileName))
        val issues = readFile(File(memoryDir, MemorySection.KNOWN_ISSUES.fileName))
        val state = readState(workspaceDir)

        return ProjectMemory(
            project = project,
            architecture = arch,
            decisions = decisions,
            constraints = constraints,
            knownIssues = issues,
            state = state,
        )
    }

    fun readSection(workspaceDir: File, section: MemorySection): String {
        val file = File(getMemoryDir(workspaceDir), section.fileName)
        return readFile(file)
    }

    fun updateSection(workspaceDir: File, section: MemorySection, rawContent: String) {
        val memoryDir = getMemoryDir(workspaceDir)
        if (!memoryDir.exists()) {
            memoryDir.mkdirs()
        }
        val safeContent = SecretRedactor.redact(rawContent)
        val file = File(memoryDir, section.fileName)
        file.writeText(safeContent)
    }

    fun appendDecision(workspaceDir: File, title: String, context: String, decision: String) {
        val memoryDir = getMemoryDir(workspaceDir)
        if (!memoryDir.exists()) memoryDir.mkdirs()
        val file = File(memoryDir, MemorySection.DECISIONS.fileName)
        val entry = buildString {
            append("\n## $title\n")
            append("- Date: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}\n")
            append("- Context: ${SecretRedactor.redact(context)}\n")
            append("- Decision: ${SecretRedactor.redact(decision)}\n")
        }
        file.appendText(entry)
    }

    fun appendKnownIssue(workspaceDir: File, issue: String, workaround: String? = null) {
        val memoryDir = getMemoryDir(workspaceDir)
        if (!memoryDir.exists()) memoryDir.mkdirs()
        val file = File(memoryDir, MemorySection.KNOWN_ISSUES.fileName)
        val entry = buildString {
            append("\n### Issue: ${SecretRedactor.redact(issue)}\n")
            append("- Reported: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}\n")
            if (!workaround.isNullOrBlank()) {
                append("- Workaround: ${SecretRedactor.redact(workaround)}\n")
            }
        }
        file.appendText(entry)
    }

    fun readState(workspaceDir: File): AgentStateData {
        val file = File(getMemoryDir(workspaceDir), STATE_FILE_NAME)
        if (!file.exists()) return AgentStateData()
        return try {
            val json = JSONObject(file.readText())
            val metadataMap = mutableMapOf<String, String>()
            val metaObj = json.optJSONObject("customMetadata")
            metaObj?.keys()?.forEach { k ->
                metadataMap[k] = metaObj.optString(k, "")
            }
            AgentStateData(
                version = json.optInt("version", 1),
                lastTaskId = json.optString("lastTaskId").takeIf { it.isNotBlank() },
                lastSessionId = json.optString("lastSessionId").takeIf { it.isNotBlank() },
                lastUpdated = json.optLong("lastUpdated", System.currentTimeMillis()),
                activeBranch = json.optString("activeBranch").takeIf { it.isNotBlank() },
                customMetadata = metadataMap,
            )
        } catch (_: Exception) {
            AgentStateData()
        }
    }

    fun updateState(workspaceDir: File, state: AgentStateData) {
        val memoryDir = getMemoryDir(workspaceDir)
        if (!memoryDir.exists()) memoryDir.mkdirs()
        val file = File(memoryDir, STATE_FILE_NAME)
        val json = JSONObject().apply {
            put("version", state.version)
            put("lastTaskId", state.lastTaskId ?: JSONObject.NULL)
            put("lastSessionId", state.lastSessionId ?: JSONObject.NULL)
            put("lastUpdated", state.lastUpdated)
            put("activeBranch", state.activeBranch ?: JSONObject.NULL)
            val metaObj = JSONObject()
            state.customMetadata.forEach { (k, v) ->
                metaObj.put(k, SecretRedactor.redact(v))
            }
            put("customMetadata", metaObj)
        }
        file.writeText(json.toString(2))
    }

    private fun readFile(file: File): String {
        return if (file.exists()) file.readText() else ""
    }
}
