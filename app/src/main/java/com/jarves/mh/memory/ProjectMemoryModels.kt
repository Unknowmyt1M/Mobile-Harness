package com.jarves.mh.memory

enum class MemorySection(val fileName: String, val title: String) {
    PROJECT("project.md", "Project Overview"),
    ARCHITECTURE("architecture.md", "Architecture & Stack"),
    DECISIONS("decisions.md", "Architectural Decisions"),
    CONSTRAINTS("constraints.md", "Constraints & Rules"),
    KNOWN_ISSUES("known-issues.md", "Known Issues & Workarounds"),
}

data class AgentStateData(
    val version: Int = 1,
    val lastTaskId: String? = null,
    val lastSessionId: String? = null,
    val lastUpdated: Long = System.currentTimeMillis(),
    val activeBranch: String? = null,
    val customMetadata: Map<String, String> = emptyMap(),
)

data class ProjectMemory(
    val project: String = "",
    val architecture: String = "",
    val decisions: String = "",
    val constraints: String = "",
    val knownIssues: String = "",
    val state: AgentStateData = AgentStateData(),
)
