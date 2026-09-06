package com.jarves.mh.editor

import java.io.File
import java.io.RandomAccessFile

object EditorFileManager {
    const val MAX_FILE_SIZE_BYTES = 1_048_576L // 1MB
    const val DEFAULT_CHUNK_SIZE_CHARS = 50_000

    fun resolveSafeFile(workspaceDir: File, relativePath: String): File {
        val normalized = relativePath.trim().trimStart('/', '\\')
        require(normalized.isNotBlank()) { "Empty relative path" }
        val target = File(workspaceDir, normalized)
        val canonicalRoot = workspaceDir.canonicalFile.toPath()
        val canonicalTarget = target.canonicalFile.toPath()
        require(canonicalTarget.startsWith(canonicalRoot)) { "Path escapes workspace: $relativePath" }
        return target
    }

    fun isBinaryFile(file: File): Boolean {
        if (!file.exists() || !file.isFile || file.length() == 0L) return false
        val buffer = ByteArray(minOf(file.length(), 4096L).toInt())
        RandomAccessFile(file, "r").use { raf ->
            raf.read(buffer)
        }
        return buffer.any { it == 0.toByte() }
    }

    fun loadFile(
        workspaceDir: File,
        relativePath: String,
        maxChars: Int = DEFAULT_CHUNK_SIZE_CHARS,
    ): FileLoadResult {
        return runCatching {
            val file = resolveSafeFile(workspaceDir, relativePath)
            if (!file.exists() || !file.isFile) {
                return FileLoadResult(
                    status = FileLoadResultStatus.FILE_NOT_FOUND,
                    errorMessage = "File does not exist: $relativePath",
                )
            }

            if (isBinaryFile(file)) {
                return FileLoadResult(
                    status = FileLoadResultStatus.BINARY_FILE,
                    totalBytes = file.length(),
                    errorMessage = "Binary file cannot be edited in text editor.",
                )
            }

            val totalSize = file.length()
            if (totalSize > MAX_FILE_SIZE_BYTES) {
                // Read leading chunk
                val bytesToRead = minOf(totalSize, (maxChars * 2).toLong()).toInt()
                val bytes = ByteArray(bytesToRead)
                RandomAccessFile(file, "r").use { raf ->
                    raf.read(bytes)
                }
                val text = bytes.decodeToString().take(maxChars)
                return FileLoadResult(
                    status = FileLoadResultStatus.LARGE_FILE_CHUNKED,
                    content = text,
                    isChunked = true,
                    totalBytes = totalSize,
                )
            }

            val fullText = file.readText(Charsets.UTF_8)
            FileLoadResult(
                status = FileLoadResultStatus.SUCCESS,
                content = fullText,
                isChunked = false,
                totalBytes = totalSize,
            )
        }.getOrElse { ex ->
            FileLoadResult(
                status = FileLoadResultStatus.ERROR,
                errorMessage = ex.message ?: "Failed to read file",
            )
        }
    }

    fun saveFile(workspaceDir: File, relativePath: String, content: String): Boolean {
        return runCatching {
            val file = resolveSafeFile(workspaceDir, relativePath)
            file.parentFile?.mkdirs()
            file.writeText(content, Charsets.UTF_8)
            true
        }.getOrDefault(false)
    }

    fun buildFileTree(workspaceDir: File, maxDepth: Int = 4): List<FileNode> {
        if (!workspaceDir.exists() || !workspaceDir.isDirectory) return emptyList()

        val ignoredDirs = setOf(
            ".git", ".gradle", "build", ".idea", "node_modules", ".checkpoints", ".memory", "target", "dist"
        )

        fun scan(dir: File, currentDepth: Int): List<FileNode> {
            if (currentDepth > maxDepth) return emptyList()
            val files = dir.listFiles() ?: return emptyList()
            return files
                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                .mapNotNull { file ->
                    val relPath = file.relativeTo(workspaceDir).invariantSeparatorsPath
                    if (file.isDirectory) {
                        if (file.name in ignoredDirs || file.name.startsWith(".")) {
                            null
                        } else {
                            FileNode(
                                name = file.name,
                                relativePath = relPath,
                                isDirectory = true,
                                children = scan(file, currentDepth + 1),
                            )
                        }
                    } else {
                        FileNode(
                            name = file.name,
                            relativePath = relPath,
                            isDirectory = false,
                            sizeBytes = file.length(),
                        )
                    }
                }
        }

        return scan(workspaceDir, 1)
    }
}
