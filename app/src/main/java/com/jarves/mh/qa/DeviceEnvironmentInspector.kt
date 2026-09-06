package com.jarves.mh.qa

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket

object DeviceEnvironmentInspector {
    const val LOW_STORAGE_THRESHOLD_BYTES = 200L * 1024L * 1024L // 200MB

    fun inspectMemory(): DeviceMemoryInfo {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val usedMemory = totalMemory - freeMemory
        return DeviceMemoryInfo(
            maxHeapBytes = maxMemory,
            totalHeapBytes = totalMemory,
            freeHeapBytes = freeMemory,
            usedHeapBytes = usedMemory,
        )
    }

    fun inspectStorage(dir: File): DeviceStorageInfo {
        val target = if (dir.exists()) dir else dir.parentFile ?: File(".")
        val total = target.totalSpace
        val free = target.freeSpace
        val usable = target.usableSpace
        val isLow = usable in 1 until LOW_STORAGE_THRESHOLD_BYTES
        return DeviceStorageInfo(
            totalBytes = total,
            freeBytes = free,
            usableBytes = usable,
            isLowStorage = isLow,
        )
    }

    fun checkLoopbackConnectivity(): Boolean {
        return runCatching {
            val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            val port = socket.localPort
            socket.close()
            port > 0
        }.getOrDefault(false)
    }

    fun generateSystemReport(dataDir: File): SystemEnvironmentReport {
        return SystemEnvironmentReport(
            osVersion = System.getProperty("os.version") ?: "unknown",
            osArch = System.getProperty("os.arch") ?: "unknown",
            deviceModel = System.getProperty("os.name") ?: "Android/Linux",
            storage = inspectStorage(dataDir),
            memory = inspectMemory(),
            loopbackAvailable = checkLoopbackConnectivity(),
        )
    }
}
