package com.jarves.mh.runtime

import com.jarves.mh.model.ChangeItem
import com.jarves.mh.model.CheckpointMetadata
import com.jarves.mh.model.DiffLine
import com.jarves.mh.model.DiffLineType
import com.jarves.mh.model.DiffSummary
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class CheckpointManager(private val checkpointsRoot: File) {

    fun checkpointDir(projectId: String): File = File(checkpointsRoot, "$projectId/latest")

    fun createCheckpoint(projectId: String, workspace: File) {
        val checkpoint = checkpointDir(projectId)
        if (File(checkpoint, "project").isDirectory && File(checkpoint, "changes.json").isFile) return
        checkpoint.deleteRecursively()
        val backup = File(checkpoint, "project").apply { mkdirs() }
        val workspacePath = workspace.canonicalFile.toPath()
        workspace.walkTopDown()
            .onEnter { directory ->
                directory == workspace || (
                    !java.nio.file.Files.isSymbolicLink(directory.toPath()) &&
                        runCatching { directory.canonicalFile.toPath().startsWith(workspacePath) }.getOrDefault(false)
                    )
            }
            .filter {
                it.isFile &&
                    !isInternalRuntimePath(it.relativeTo(workspace).invariantSeparatorsPath) &&
                    !java.nio.file.Files.isSymbolicLink(it.toPath())
            }
            .forEach { source ->
                val relative = source.relativeTo(workspace).invariantSeparatorsPath
                val destination = safeWorkspaceFile(backup, relative)
                destination.parentFile?.mkdirs()
                source.copyTo(destination, overwrite = true)
            }
    }

    fun snapshot(root: File): Map<String, String> = root.walkTopDown()
        .filter { it.isFile && !isInternalRuntimePath(it.relativeTo(root).invariantSeparatorsPath) }
        .associate { it.relativeTo(root).path to digest(it) }

    fun detectChangedFiles(root: File, before: Map<String, String>): List<String> {
        val after = snapshot(root)
        return (before.keys + after.keys).distinct().filter { before[it] != after[it] }.sorted()
    }

    fun saveChangedPaths(projectId: String, paths: List<String>) {
        val manifest = File(checkpointDir(projectId), "changes.json")
        manifest.parentFile?.mkdirs()
        val merged = (readChangedPaths(projectId) + paths)
            .filterNot(::isInternalRuntimePath)
            .distinct()
            .sorted()
        manifest.writeText(JSONArray(merged).toString())
    }

    fun readChangedPaths(projectId: String): List<String> {
        val manifest = File(checkpointDir(projectId), "changes.json")
        if (!manifest.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(manifest.readText())
            (0 until array.length()).map(array::getString)
        }.getOrDefault(emptyList())
    }

    fun removeChangedPath(projectId: String, path: String) {
        val remaining = readChangedPaths(projectId).filterNot { it == path }
        if (remaining.isEmpty()) {
            checkpointDir(projectId).deleteRecursively()
        } else {
            File(checkpointDir(projectId), "changes.json").writeText(JSONArray(remaining).toString())
        }
    }

    fun undoLastChanges(projectId: String, workspace: File): Boolean {
        val checkpoint = checkpointDir(projectId)
        val backup = File(checkpoint, "project")
        val manifest = File(checkpoint, "changes.json")
        if (!backup.isDirectory || !manifest.isFile) return false
        val paths = runCatching {
            val array = JSONArray(manifest.readText())
            (0 until array.length()).map(array::getString)
        }.getOrElse { return false }.filterNot(::isInternalRuntimePath)

        paths.forEach { path ->
            val target = safeWorkspaceFile(workspace, path)
            val original = safeWorkspaceFile(backup, path)
            if (original.isFile) {
                target.parentFile?.mkdirs()
                original.copyTo(target, overwrite = true)
            } else if (target.isFile) {
                target.delete()
            }
        }
        checkpoint.deleteRecursively()
        return true
    }

    fun acceptLastChanges(projectId: String) {
        checkpointDir(projectId).deleteRecursively()
    }

    fun loadPendingChanges(projectId: String, workspace: File): List<ChangeItem> {
        val paths = readChangedPaths(projectId).filterNot(::isInternalRuntimePath)
        if (paths.isEmpty()) return emptyList()
        val backup = File(checkpointDir(projectId), "project")
        return paths.map { path ->
            val before = safeWorkspaceFile(backup, path).takeIf(File::isFile)?.readBytes() ?: ByteArray(0)
            val after = safeWorkspaceFile(workspace, path).takeIf(File::isFile)?.readBytes() ?: ByteArray(0)
            val binary = before.any { it == 0.toByte() } || after.any { it == 0.toByte() }
            val (additions, deletions) = lineChanges(before, after)
            ChangeItem(
                path = path,
                additions = additions,
                deletions = deletions,
                diffLines = buildDiffLines(before, after),
                binary = binary,
            )
        }
    }

    fun computeDiffSummary(projectId: String, workspace: File): DiffSummary {
        val changes = loadPendingChanges(projectId, workspace)
        val totalAdditions = changes.sumOf { it.additions }
        val totalDeletions = changes.sumOf { it.deletions }
        return DiffSummary(
            totalFilesChanged = changes.size,
            totalAdditions = totalAdditions,
            totalDeletions = totalDeletions,
            changes = changes,
        )
    }

    fun createNamedCheckpoint(
        projectId: String,
        workspace: File,
        label: String,
        taskId: String? = null,
        checkpointId: String = UUID.randomUUID().toString(),
    ): CheckpointMetadata {
        val checkpointFolder = File(checkpointsRoot, "$projectId/$checkpointId")
        checkpointFolder.mkdirs()
        val backup = File(checkpointFolder, "project").apply { mkdirs() }

        var count = 0
        var totalBytes = 0L
        val workspacePath = workspace.canonicalFile.toPath()

        workspace.walkTopDown()
            .onEnter { directory ->
                directory == workspace || (
                    !java.nio.file.Files.isSymbolicLink(directory.toPath()) &&
                        runCatching { directory.canonicalFile.toPath().startsWith(workspacePath) }.getOrDefault(false)
                    )
            }
            .filter {
                it.isFile &&
                    !isInternalRuntimePath(it.relativeTo(workspace).invariantSeparatorsPath) &&
                    !java.nio.file.Files.isSymbolicLink(it.toPath())
            }
            .forEach { source ->
                val relative = source.relativeTo(workspace).invariantSeparatorsPath
                val destination = safeWorkspaceFile(backup, relative)
                destination.parentFile?.mkdirs()
                source.copyTo(destination, overwrite = true)
                count++
                totalBytes += source.length()
            }

        val metadata = CheckpointMetadata(
            id = checkpointId,
            projectId = projectId,
            taskId = taskId,
            label = label,
            timestamp = System.currentTimeMillis(),
            fileCount = count,
            totalBytes = totalBytes,
        )

        val metaJson = JSONObject().apply {
            put("id", metadata.id)
            put("projectId", metadata.projectId)
            put("taskId", metadata.taskId ?: JSONObject.NULL)
            put("label", metadata.label)
            put("timestamp", metadata.timestamp)
            put("fileCount", metadata.fileCount)
            put("totalBytes", metadata.totalBytes)
        }
        File(checkpointFolder, "metadata.json").writeText(metaJson.toString(2))
        return metadata
    }

    fun listCheckpoints(projectId: String): List<CheckpointMetadata> {
        val projectDir = File(checkpointsRoot, projectId)
        if (!projectDir.isDirectory) return emptyList()

        return projectDir.listFiles()
            ?.filter { it.isDirectory && it.name != "latest" }
            ?.mapNotNull { folder ->
                val metaFile = File(folder, "metadata.json")
                if (!metaFile.isFile) return@mapNotNull null
                runCatching {
                    val json = JSONObject(metaFile.readText())
                    CheckpointMetadata(
                        id = json.getString("id"),
                        projectId = json.getString("projectId"),
                        taskId = json.optString("taskId").takeIf { it.isNotBlank() && it != "null" },
                        label = json.optString("label", "checkpoint"),
                        timestamp = json.optLong("timestamp", 0L),
                        fileCount = json.optInt("fileCount", 0),
                        totalBytes = json.optLong("totalBytes", 0L),
                    )
                }.getOrNull()
            }
            ?.sortedByDescending { it.timestamp }
            ?: emptyList()
    }

    fun restoreNamedCheckpoint(projectId: String, workspace: File, checkpointId: String): Boolean {
        val checkpointFolder = File(checkpointsRoot, "$projectId/$checkpointId")
        val backup = File(checkpointFolder, "project")
        if (!backup.isDirectory) return false

        val backupFiles = mutableSetOf<String>()

        backup.walkTopDown().filter { it.isFile }.forEach { source ->
            val relative = source.relativeTo(backup).invariantSeparatorsPath
            backupFiles.add(relative)
            val destination = safeWorkspaceFile(workspace, relative)
            destination.parentFile?.mkdirs()
            source.copyTo(destination, overwrite = true)
        }

        workspace.walkTopDown().filter { it.isFile }.forEach { file ->
            val relative = file.relativeTo(workspace).invariantSeparatorsPath
            if (!isInternalRuntimePath(relative) && relative !in backupFiles) {
                file.delete()
            }
        }
        return true
    }

    fun deleteNamedCheckpoint(projectId: String, checkpointId: String): Boolean {
        val checkpointFolder = File(checkpointsRoot, "$projectId/$checkpointId")
        return checkpointFolder.deleteRecursively()
    }

    fun undoFileChange(projectId: String, workspace: File, path: String): Boolean {
        if (isInternalRuntimePath(path) || path !in readChangedPaths(projectId)) return false
        val backup = File(checkpointDir(projectId), "project")
        val target = safeWorkspaceFile(workspace, path)
        val original = safeWorkspaceFile(backup, path)
        if (original.isFile) {
            target.parentFile?.mkdirs()
            original.copyTo(target, overwrite = true)
        } else if (target.isFile) {
            target.delete()
        }
        removeChangedPath(projectId, path)
        return true
    }

    fun acceptFileChange(projectId: String, workspace: File, path: String): Boolean {
        if (isInternalRuntimePath(path) || path !in readChangedPaths(projectId)) return false
        val backup = File(checkpointDir(projectId), "project")
        val current = safeWorkspaceFile(workspace, path)
        val baseline = safeWorkspaceFile(backup, path)
        if (current.isFile) {
            baseline.parentFile?.mkdirs()
            current.copyTo(baseline, overwrite = true)
        } else if (baseline.isFile) {
            baseline.delete()
        }
        removeChangedPath(projectId, path)
        return true
    }

    fun safeWorkspaceFile(root: File, relative: String): File {
        require(relative.isNotBlank() && !relative.startsWith('/')) { "Unsafe workspace path" }
        val file = File(root, relative)
        val rootPath = root.canonicalFile.toPath()
        val parentPath = (file.parentFile ?: root).canonicalFile.toPath()
        require(parentPath.startsWith(rootPath)) { "Workspace path escapes project" }
        return file
    }

    fun isInternalRuntimePath(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        return normalized == ".claude" || normalized == ".claude.json" || normalized.startsWith(".claude/")
    }

    private fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun buildDiffLines(beforeBytes: ByteArray, afterBytes: ByteArray): List<DiffLine> {
        if (beforeBytes.any { it == 0.toByte() } || afterBytes.any { it == 0.toByte() }) {
            return listOf(DiffLine(DiffLineType.INFO, "Binary file changed"))
        }
        val before = textLines(beforeBytes)
        val after = textLines(afterBytes)
        if (before.size > MAX_RENDERED_DIFF_LINES || after.size > MAX_RENDERED_DIFF_LINES) {
            return listOf(
                DiffLine(
                    DiffLineType.INFO,
                    "Diff is too large to display (${before.size} → ${after.size} lines). Undo and Keep still work.",
                ),
            )
        }

        val lcs = Array(before.size + 1) { IntArray(after.size + 1) }
        for (oldIndex in before.lastIndex downTo 0) {
            for (newIndex in after.lastIndex downTo 0) {
                lcs[oldIndex][newIndex] = if (before[oldIndex] == after[newIndex]) {
                    lcs[oldIndex + 1][newIndex + 1] + 1
                } else {
                    maxOf(lcs[oldIndex + 1][newIndex], lcs[oldIndex][newIndex + 1])
                }
            }
        }

        val result = mutableListOf<DiffLine>()
        var oldIndex = 0
        var newIndex = 0
        while (oldIndex < before.size || newIndex < after.size) {
            when {
                oldIndex < before.size && newIndex < after.size && before[oldIndex] == after[newIndex] -> {
                    result += DiffLine(DiffLineType.CONTEXT, before[oldIndex], oldIndex + 1, newIndex + 1)
                    oldIndex++
                    newIndex++
                }
                newIndex < after.size && (oldIndex == before.size || lcs[oldIndex][newIndex + 1] >= lcs[oldIndex + 1][newIndex]) -> {
                    result += DiffLine(DiffLineType.ADDITION, after[newIndex], null, newIndex + 1)
                    newIndex++
                }
                oldIndex < before.size -> {
                    result += DiffLine(DiffLineType.DELETION, before[oldIndex], oldIndex + 1, null)
                    oldIndex++
                }
            }
        }
        return collapseUnchangedLines(result)
    }

    private fun collapseUnchangedLines(lines: List<DiffLine>): List<DiffLine> {
        val changedIndexes = lines.indices.filter { lines[it].type != DiffLineType.CONTEXT }
        if (changedIndexes.isEmpty()) return lines
        val visible = BooleanArray(lines.size)
        changedIndexes.forEach { changed ->
            for (index in maxOf(0, changed - DIFF_CONTEXT_LINES)..minOf(lines.lastIndex, changed + DIFF_CONTEXT_LINES)) {
                visible[index] = true
            }
        }
        val result = mutableListOf<DiffLine>()
        var index = 0
        while (index < lines.size) {
            if (visible[index]) {
                result += lines[index++]
            } else {
                val start = index
                while (index < lines.size && !visible[index]) index++
                result += DiffLine(DiffLineType.INFO, "… ${index - start} unchanged lines …")
            }
        }
        return result
    }

    private fun lineChanges(beforeBytes: ByteArray, afterBytes: ByteArray): Pair<Int, Int> {
        if (beforeBytes.any { it == 0.toByte() } || afterBytes.any { it == 0.toByte() }) {
            return (if (afterBytes.isNotEmpty()) 1 else 0) to (if (beforeBytes.isNotEmpty()) 1 else 0)
        }
        val before = textLines(beforeBytes)
        val after = textLines(afterBytes)
        if (before.size > MAX_DIFF_LINES || after.size > MAX_DIFF_LINES) {
            return maxOf(0, after.size - before.size) to maxOf(0, before.size - after.size)
        }
        var previous = IntArray(after.size + 1)
        before.forEach { oldLine ->
            val current = IntArray(after.size + 1)
            after.forEachIndexed { index, newLine ->
                current[index + 1] = if (oldLine == newLine) {
                    previous[index] + 1
                } else {
                    maxOf(previous[index + 1], current[index])
                }
            }
            previous = current
        }
        val common = previous[after.size]
        return (after.size - common) to (before.size - common)
    }

    private fun textLines(bytes: ByteArray): List<String> {
        if (bytes.isEmpty()) return emptyList()
        val lines = bytes.decodeToString().split('\n')
        return if (lines.lastOrNull().isNullOrEmpty()) lines.dropLast(1) else lines
    }

    companion object {
        private const val MAX_DIFF_LINES = 2_000
        private const val MAX_RENDERED_DIFF_LINES = 600
        private const val DIFF_CONTEXT_LINES = 3
    }
}
