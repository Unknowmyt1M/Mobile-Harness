package com.jarves.mh.runtime

import android.content.Context
import android.util.Log
import java.io.File

object OrphanProcessCleaner {
    private const val PID_FILE_NAME = "active_runtime.pid"

    fun recordActivePid(context: Context, pid: Int) {
        runCatching {
            File(context.filesDir, PID_FILE_NAME).writeText(pid.toString())
        }
    }

    fun clearActivePid(context: Context) {
        runCatching {
            File(context.filesDir, PID_FILE_NAME).delete()
        }
    }

    fun cleanupOrphans(context: Context) {
        val pidFile = File(context.filesDir, PID_FILE_NAME)
        if (!pidFile.isFile) return

        val pid = runCatching { pidFile.readText().trim().toInt() }.getOrNull()
        if (pid != null && pid > 0) {
            Log.i("OrphanProcessCleaner", "Found orphaned runtime PID $pid, terminating process group...")
            runCatching {
                // Kill process group with SIGTERM then SIGKILL
                android.system.Os.kill(-pid, 15)
                Thread.sleep(200)
                android.system.Os.kill(-pid, 9)
            }.onFailure { error ->
                Log.w("OrphanProcessCleaner", "Could not kill orphan PID $pid: ${error.message}")
            }
        }
        pidFile.delete()
    }
}
