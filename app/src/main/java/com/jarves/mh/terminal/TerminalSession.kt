package com.jarves.mh.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class TerminalSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "T1",
    val initialCwd: String = "/workspace",
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var readerJob: Job? = null
    var ptyProcess: PtyProcess? = null
        private set

    private val _buffer = MutableStateFlow("")
    val buffer: StateFlow<String> = _buffer.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _cwd = MutableStateFlow(initialCwd)
    val cwd: StateFlow<String> = _cwd.asStateFlow()

    fun attachProcess(process: PtyProcess) {
        ptyProcess = process
        _isRunning.value = true
        readerJob?.cancel()
        readerJob = scope.launch {
            val buf = ByteArray(4096)
            while (isActive && process.isAlive) {
                val read = runCatching { process.inputStream.read(buf) }.getOrDefault(-1)
                if (read > 0) {
                    val chunk = buf.decodeToString(0, read)
                    _buffer.value = (_buffer.value + chunk).takeLast(MAX_BUFFER_CHARS)
                } else if (read < 0) {
                    break
                }
            }
            _isRunning.value = false
        }
    }

    fun write(data: String) {
        ptyProcess?.let { proc ->
            scope.launch {
                runCatching {
                    proc.outputStream.write(data.toByteArray())
                    proc.outputStream.flush()
                }
            }
        }
    }

    fun writeBytes(bytes: ByteArray) {
        ptyProcess?.let { proc ->
            scope.launch {
                runCatching {
                    proc.outputStream.write(bytes)
                    proc.outputStream.flush()
                }
            }
        }
    }

    fun resize(rows: Int, cols: Int) {
        ptyProcess?.setWindowSize(rows, cols)
    }

    fun clear() {
        _buffer.value = ""
    }

    fun close() {
        readerJob?.cancel()
        ptyProcess?.close()
        ptyProcess = null
        _isRunning.value = false
    }

    companion object {
        const val MAX_BUFFER_CHARS = 100_000 // bounded scrollback (~2500 lines)
    }
}
