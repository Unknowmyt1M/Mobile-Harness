package com.jarves.mh.terminal

import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

class PtyProcess(
    val pid: Int,
    val masterFd: Int,
    val inputStream: InputStream,
    val outputStream: OutputStream,
) {
    @Volatile var isAlive: Boolean = true
        private set

    fun setWindowSize(rows: Int, cols: Int): Int {
        if (!isAlive) return -1
        return NativePty.setWindowSize(masterFd, rows, cols)
    }

    fun close() {
        isAlive = false
        runCatching { outputStream.close() }
        runCatching { inputStream.close() }
        NativePty.closePty(masterFd, pid)
    }

    companion object {
        fun create(
            argv: List<String>,
            environment: Map<String, String>,
            cwd: String,
            rows: Int = 24,
            cols: Int = 80,
        ): PtyProcess {
            val envArr = environment.map { "${it.key}=${it.value}" }.toTypedArray()
            val res = NativePty.createPty(argv.toTypedArray(), envArr, cwd, rows, cols)
            check(res.size == 2 && res[0] >= 0 && res[1] > 0) { "Failed to allocate native PTY" }
            val masterFd = res[0]
            val pid = res[1]

            val pfdIn = ParcelFileDescriptor.adoptFd(masterFd)
            val inStream = FileInputStream(pfdIn.fileDescriptor)
            val outStream = FileOutputStream(pfdIn.fileDescriptor)

            return PtyProcess(pid, masterFd, inStream, outStream)
        }
    }
}

internal object NativePty {
    init {
        System.loadLibrary("pocketspawn")
    }

    external fun createPty(argv: Array<String>, env: Array<String>, cwd: String, rows: Int, cols: Int): IntArray
    external fun setWindowSize(ptmFd: Int, rows: Int, cols: Int): Int
    external fun closePty(ptmFd: Int, pid: Int): Int
}
