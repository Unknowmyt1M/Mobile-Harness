package com.jarves.mh.memory

import com.jarves.mh.security.SecretRedactor
import java.io.File

object ContextSelectionEngine {
    const val DEFAULT_MAX_MEMORY_CHARS = 3500

    /**
     * Selects and formats curated project memory into a structured XML-tagged block
     * suitable for injecting into task context, respecting character budgets.
     */
    fun selectContext(
        memory: ProjectMemory,
        currentPrompt: String,
        recentFiles: List<String> = emptyList(),
        maxCharBudget: Int = DEFAULT_MAX_MEMORY_CHARS,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("<project_memory>")
        sb.appendLine("The following persistent memory documents project rules, architecture, and constraints.")

        var remainingBudget = maxCharBudget

        // 1. Constraints (Highest priority to prevent the agent from violating rules)
        if (memory.constraints.isNotBlank()) {
            val trimmed = memory.constraints.trim()
            val chunk = if (trimmed.length > 800) trimmed.take(800) + "\n...[truncated]" else trimmed
            sb.appendLine("\n### Constraints & Rules:")
            sb.appendLine(chunk)
            remainingBudget -= chunk.length
        }

        // 2. Project Overview
        if (memory.project.isNotBlank() && remainingBudget > 500) {
            val trimmed = memory.project.trim()
            val budget = minOf(remainingBudget / 3, 800)
            val chunk = if (trimmed.length > budget) trimmed.take(budget) + "\n...[truncated]" else trimmed
            sb.appendLine("\n### Project Overview:")
            sb.appendLine(chunk)
            remainingBudget -= chunk.length
        }

        // 3. Architecture
        if (memory.architecture.isNotBlank() && remainingBudget > 400) {
            val trimmed = memory.architecture.trim()
            val budget = minOf(remainingBudget / 2, 700)
            val chunk = if (trimmed.length > budget) trimmed.take(budget) + "\n...[truncated]" else trimmed
            sb.appendLine("\n### Architecture:")
            sb.appendLine(chunk)
            remainingBudget -= chunk.length
        }

        // 4. Known Issues
        if (memory.knownIssues.isNotBlank() && remainingBudget > 300) {
            val trimmed = memory.knownIssues.trim()
            val budget = minOf(remainingBudget, 600)
            val chunk = if (trimmed.length > budget) trimmed.take(budget) + "\n...[truncated]" else trimmed
            sb.appendLine("\n### Known Issues:")
            sb.appendLine(chunk)
            remainingBudget -= chunk.length
        }

        // 5. Recent Decisions
        if (memory.decisions.isNotBlank() && remainingBudget > 200) {
            val trimmed = memory.decisions.trim()
            val chunk = if (trimmed.length > remainingBudget) trimmed.take(remainingBudget) + "\n...[truncated]" else trimmed
            sb.appendLine("\n### Recent Decisions:")
            sb.appendLine(chunk)
        }

        if (recentFiles.isNotEmpty()) {
            sb.appendLine("\n### Recently Modified Workspace Files:")
            recentFiles.take(10).forEach { file ->
                sb.appendLine("- $file")
            }
        }

        sb.appendLine("</project_memory>")
        return SecretRedactor.redact(sb.toString())
    }

    /**
     * Lists primary workspace files ignoring build artifacts, .git, and cache directories.
     */
    fun summarizeWorkspaceFiles(workspaceDir: File, maxFiles: Int = 30): List<String> {
        if (!workspaceDir.exists() || !workspaceDir.isDirectory) return emptyList()

        val ignoredDirs = setOf(
            ".git", ".gradle", "build", ".idea", "node_modules", ".memory", ".checkpoints", "target", "dist"
        )
        val fileList = mutableListOf<String>()

        fun scan(dir: File) {
            if (fileList.size >= maxFiles) return
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (fileList.size >= maxFiles) break
                if (child.isDirectory) {
                    if (child.name !in ignoredDirs && !child.name.startsWith(".")) {
                        scan(child)
                    }
                } else {
                    val relPath = child.relativeTo(workspaceDir).invariantSeparatorsPath
                    fileList.add(relPath)
                }
            }
        }

        scan(workspaceDir)
        return fileList
    }
}
