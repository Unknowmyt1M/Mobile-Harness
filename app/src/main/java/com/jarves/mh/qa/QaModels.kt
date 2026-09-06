package com.jarves.mh.qa

data class DeviceStorageInfo(
    val totalBytes: Long,
    val freeBytes: Long,
    val usableBytes: Long,
    val isLowStorage: Boolean,
)

data class DeviceMemoryInfo(
    val maxHeapBytes: Long,
    val totalHeapBytes: Long,
    val freeHeapBytes: Long,
    val usedHeapBytes: Long,
)

data class SystemEnvironmentReport(
    val osVersion: String,
    val osArch: String,
    val deviceModel: String,
    val storage: DeviceStorageInfo,
    val memory: DeviceMemoryInfo,
    val loopbackAvailable: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
)

enum class HealthCheckStatus {
    PASS,
    WARN,
    FAIL,
}

data class HealthCheckItem(
    val name: String,
    val status: HealthCheckStatus,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
)

data class HealthReport(
    val overallStatus: HealthCheckStatus,
    val items: List<HealthCheckItem>,
    val timestamp: Long = System.currentTimeMillis(),
)
