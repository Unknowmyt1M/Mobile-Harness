package com.jarves.mh.runtime

import java.io.File

object BoundedOutputLogger {
    const val MAX_LOG_SIZE_BYTES = 10L * 1024L * 1024L // 10 MB per session
    private const val MAX_RETAINED_LOG_FILES = 5

    fun cleanupStaleLogs(cacheDir: File) {
        runCatching {
            val logs = cacheDir.listFiles { file ->
                file.isFile && file.name.startsWith("runtime-output-") && file.name.endsWith(".log")
            }?.sortedByDescending { it.lastModified() } ?: return

            if (logs.size > MAX_RETAINED_LOG_FILES) {
                logs.drop(MAX_RETAINED_LOG_FILES).forEach { file ->
                    file.delete()
                }
            }
        }
    }

    fun isWithinBounds(file: File): Boolean {
        return file.length() <= MAX_LOG_SIZE_BYTES
    }
}
