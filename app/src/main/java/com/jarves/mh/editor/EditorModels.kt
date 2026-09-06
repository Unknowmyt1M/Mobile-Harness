package com.jarves.mh.editor

import java.util.UUID

enum class FileLoadResultStatus {
    SUCCESS,
    LARGE_FILE_CHUNKED,
    BINARY_FILE,
    FILE_NOT_FOUND,
    ERROR,
}

data class FileLoadResult(
    val status: FileLoadResultStatus,
    val content: String = "",
    val isChunked: Boolean = false,
    val totalBytes: Long = 0L,
    val errorMessage: String? = null,
)

data class EditorTab(
    val id: String = UUID.randomUUID().toString(),
    val relativePath: String,
    val displayName: String,
    var content: String = "",
    var isDirty: Boolean = false,
    var isReadOnly: Boolean = false,
    var isBinary: Boolean = false,
    var totalSize: Long = 0L,
)

data class FileNode(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0L,
    val children: List<FileNode> = emptyList(),
)
