package com.jarves.mh.qa

import com.jarves.mh.security.SecretRedactor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

object SmokeTestRunner {

    fun checkStorageHealth(workspaceDir: File): HealthCheckItem {
        val storage = DeviceEnvironmentInspector.inspectStorage(workspaceDir)
        return when {
            storage.isLowStorage -> HealthCheckItem(
                name = "Storage Space",
                status = HealthCheckStatus.WARN,
                message = "Low storage space available (${storage.usableBytes / (1024 * 1024)}MB free).",
            )
            storage.usableBytes == 0L -> HealthCheckItem(
                name = "Storage Space",
                status = HealthCheckStatus.FAIL,
                message = "Storage is full or read-only.",
            )
            else -> HealthCheckItem(
                name = "Storage Space",
                status = HealthCheckStatus.PASS,
                message = "Sufficient storage space available (${storage.usableBytes / (1024 * 1024)}MB free).",
            )
        }
    }

    fun checkWorkspaceWriteAccess(workspaceDir: File): HealthCheckItem {
        if (!workspaceDir.exists() && !workspaceDir.mkdirs()) {
            return HealthCheckItem(
                name = "Workspace Write Access",
                status = HealthCheckStatus.FAIL,
                message = "Cannot create or access workspace directory: ${workspaceDir.path}",
            )
        }

        val testFile = File(workspaceDir, ".probe_${UUID.randomUUID()}")
        return try {
            testFile.writeText("write_test")
            val content = testFile.readText()
            testFile.delete()
            if (content == "write_test") {
                HealthCheckItem(
                    name = "Workspace Write Access",
                    status = HealthCheckStatus.PASS,
                    message = "Workspace directory is writable.",
                )
            } else {
                HealthCheckItem(
                    name = "Workspace Write Access",
                    status = HealthCheckStatus.FAIL,
                    message = "Probe write verification failed.",
                )
            }
        } catch (ex: Exception) {
            testFile.delete()
            HealthCheckItem(
                name = "Workspace Write Access",
                status = HealthCheckStatus.FAIL,
                message = "Cannot write to workspace: ${ex.message}",
            )
        }
    }

    fun checkLoopbackHealth(): HealthCheckItem {
        val loopbackOk = DeviceEnvironmentInspector.checkLoopbackConnectivity()
        return if (loopbackOk) {
            HealthCheckItem(
                name = "Loopback Networking",
                status = HealthCheckStatus.PASS,
                message = "Local loopback (127.0.0.1) server socket binding operational.",
            )
        } else {
            HealthCheckItem(
                name = "Loopback Networking",
                status = HealthCheckStatus.FAIL,
                message = "Failed to bind to local loopback socket.",
            )
        }
    }

    fun checkMemoryDirectory(workspaceDir: File): HealthCheckItem {
        val memoryDir = File(workspaceDir, ".memory")
        return if (memoryDir.exists() && memoryDir.isDirectory) {
            HealthCheckItem(
                name = "Project Memory Directory",
                status = HealthCheckStatus.PASS,
                message = ".memory directory is present and accessible.",
            )
        } else {
            HealthCheckItem(
                name = "Project Memory Directory",
                status = HealthCheckStatus.WARN,
                message = ".memory directory not initialized yet.",
            )
        }
    }

    fun runSmokeTests(workspaceDir: File): HealthReport {
        val items = listOf(
            checkWorkspaceWriteAccess(workspaceDir),
            checkStorageHealth(workspaceDir),
            checkLoopbackHealth(),
            checkMemoryDirectory(workspaceDir),
        )

        val overallStatus = when {
            items.any { it.status == HealthCheckStatus.FAIL } -> HealthCheckStatus.FAIL
            items.any { it.status == HealthCheckStatus.WARN } -> HealthCheckStatus.WARN
            else -> HealthCheckStatus.PASS
        }

        return HealthReport(overallStatus = overallStatus, items = items)
    }

    fun exportDiagnosticBundle(report: HealthReport, extraLogs: String = ""): String {
        val safeLogs = SecretRedactor.redact(extraLogs)
        val json = JSONObject().apply {
            put("timestamp", report.timestamp)
            put("overallStatus", report.overallStatus.name)

            val checksArr = JSONArray()
            report.items.forEach { item ->
                checksArr.put(
                    JSONObject().apply {
                        put("name", item.name)
                        put("status", item.status.name)
                        put("message", item.message)
                    }
                )
            }
            put("checks", checksArr)
            put("runtimeLogs", safeLogs)
        }
        return json.toString(2)
    }
}
