package com.jarves.mh.qa

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class QaTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var workspaceDir: File

    @Before
    fun setUp() {
        workspaceDir = tempFolder.newFolder("workspace_qa")
    }

    @Test
    fun `inspects runtime memory and storage`() {
        val memory = DeviceEnvironmentInspector.inspectMemory()
        assertTrue(memory.maxHeapBytes > 0)
        assertTrue(memory.totalHeapBytes > 0)

        val storage = DeviceEnvironmentInspector.inspectStorage(workspaceDir)
        assertTrue(storage.totalBytes > 0)
        assertTrue(storage.freeBytes > 0)
    }

    @Test
    fun `verifies loopback socket connectivity`() {
        val loopbackOk = DeviceEnvironmentInspector.checkLoopbackConnectivity()
        assertTrue(loopbackOk)
    }

    @Test
    fun `generates comprehensive system report`() {
        val report = DeviceEnvironmentInspector.generateSystemReport(workspaceDir)
        assertNotNull(report.osArch)
        assertNotNull(report.osVersion)
        assertTrue(report.loopbackAvailable)
    }

    @Test
    fun `smoke tests evaluate workspace write and loopback health`() {
        val healthReport = SmokeTestRunner.runSmokeTests(workspaceDir)
        assertNotNull(healthReport)
        assertTrue(healthReport.items.isNotEmpty())

        val writeCheck = healthReport.items.firstOrNull { it.name == "Workspace Write Access" }
        assertNotNull(writeCheck)
        assertEquals(HealthCheckStatus.PASS, writeCheck?.status)

        val loopbackCheck = healthReport.items.firstOrNull { it.name == "Loopback Networking" }
        assertNotNull(loopbackCheck)
        assertEquals(HealthCheckStatus.PASS, loopbackCheck?.status)
    }

    @Test
    fun `exports diagnostic bundle with secret redaction`() {
        val report = SmokeTestRunner.runSmokeTests(workspaceDir)
        val sensitiveLogs = "Agent crashed with key sk-ant-api03-1234567890abcdef1234567890abcdef-AA and Bearer eyJhbGciOiJIUzI1NiJ9.test"

        val bundleJson = SmokeTestRunner.exportDiagnosticBundle(report, sensitiveLogs)
        assertNotNull(bundleJson)

        val json = JSONObject(bundleJson)
        assertEquals(report.overallStatus.name, json.getString("overallStatus"))
        val logs = json.getString("runtimeLogs")

        assertFalse(logs.contains("1234567890abcdef"))
        assertTrue(logs.contains("sk-••••"))
        assertFalse(logs.contains("eyJhbGciOiJIUzI1NiJ9.test"))
        assertTrue(logs.contains("Bearer ••••"))
    }
}
