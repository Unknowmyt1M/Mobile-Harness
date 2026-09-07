package com.jarves.mh.runtime

import com.jarves.mh.model.CapabilityScope
import com.jarves.mh.model.PermissionDecision
import com.jarves.mh.model.RiskLevel
import com.jarves.mh.model.RuntimeEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList

class PermissionLifecycleTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testAllowOnceResumesOperationAndGrantsCommand() = runBlocking {
        val bridgeDir = tempFolder.newFolder("bridge")
        val events = CopyOnWriteArrayList<RuntimeEvent>()
        val permissionManager = PermissionManager(
            bridgeDir = bridgeDir,
            taskStore = null,
            onEvent = { events.add(it) },
        )

        val sessionId = "session_1"
        val command = "nohup python3 server.py > server.log 2>&1 &"

        val deferredResult = async {
            permissionManager.requestPermission(
                sessionId = sessionId,
                taskId = "task_1",
                projectId = "proj_1",
                capability = CapabilityScope.PROCESS_EXECUTE,
                explanation = "Run server",
                command = command,
                affectedPaths = emptyList(),
                riskLevel = RiskLevel.REVIEW,
            )
        }

        var reqEvent: RuntimeEvent.PermissionRequested? = null
        for (i in 0..20) {
            reqEvent = events.filterIsInstance<RuntimeEvent.PermissionRequested>().firstOrNull()
            if (reqEvent != null) break
            delay(20)
        }
        assertTrue("PermissionRequested event should be emitted", reqEvent != null)
        val approvalId = reqEvent!!.request.requestId

        permissionManager.respond(
            approvalId = approvalId,
            decision = PermissionDecision.ALLOW_ONCE,
            sessionId = sessionId,
        )

        val result = deferredResult.await()
        assertEquals(PermissionDecision.ALLOW_ONCE, result)

        assertTrue(permissionManager.hasSessionGrant(sessionId, CapabilityScope.PROCESS_EXECUTE, command))
        assertFalse(permissionManager.hasSessionGrant(sessionId, CapabilityScope.PROCESS_EXECUTE, "rm -rf /"))
    }

    @Test
    fun testAllowTaskGrantsEntireSession() = runBlocking {
        val bridgeDir = tempFolder.newFolder("bridge")
        val events = CopyOnWriteArrayList<RuntimeEvent>()
        val permissionManager = PermissionManager(
            bridgeDir = bridgeDir,
            taskStore = null,
            onEvent = { events.add(it) },
        )

        val sessionId = "session_task_grant"

        val deferredResult = async {
            permissionManager.requestPermission(
                sessionId = sessionId,
                taskId = "task_2",
                projectId = "proj_2",
                capability = CapabilityScope.PROCESS_EXECUTE,
                explanation = "Run initial command",
                command = "ls -la",
            )
        }

        var reqEvent: RuntimeEvent.PermissionRequested? = null
        for (i in 0..20) {
            reqEvent = events.filterIsInstance<RuntimeEvent.PermissionRequested>().firstOrNull()
            if (reqEvent != null) break
            delay(20)
        }
        val approvalId = reqEvent!!.request.requestId

        permissionManager.respond(
            approvalId = approvalId,
            decision = PermissionDecision.ALLOW_TASK,
            sessionId = sessionId,
        )

        val result = deferredResult.await()
        assertEquals(PermissionDecision.ALLOW_TASK, result)

        assertTrue(permissionManager.hasSessionGrant(sessionId, CapabilityScope.PROCESS_EXECUTE, "python3 worker.py"))
        assertTrue(permissionManager.hasSessionGrant(sessionId, CapabilityScope.PROCESS_EXECUTE, null))

        val secondResult = permissionManager.requestPermission(
            sessionId = sessionId,
            taskId = "task_2",
            projectId = "proj_2",
            capability = CapabilityScope.PROCESS_EXECUTE,
            explanation = "Run subsequent command",
            command = "python3 worker.py",
        )
        assertEquals(PermissionDecision.ALLOW_ONCE, secondResult)
    }

    @Test
    fun testDenyReturnsDenyOnceWithoutSessionGrant() = runBlocking {
        val bridgeDir = tempFolder.newFolder("bridge")
        val events = CopyOnWriteArrayList<RuntimeEvent>()
        val permissionManager = PermissionManager(
            bridgeDir = bridgeDir,
            taskStore = null,
            onEvent = { events.add(it) },
        )

        val sessionId = "session_deny"
        val deferredResult = async {
            permissionManager.requestPermission(
                sessionId = sessionId,
                taskId = "task_3",
                projectId = "proj_3",
                capability = CapabilityScope.FILESYSTEM_WRITE,
                explanation = "Modify critical file",
                command = null,
                affectedPaths = listOf("config.json"),
            )
        }

        var reqEvent: RuntimeEvent.PermissionRequested? = null
        for (i in 0..20) {
            reqEvent = events.filterIsInstance<RuntimeEvent.PermissionRequested>().firstOrNull()
            if (reqEvent != null) break
            delay(20)
        }
        val approvalId = reqEvent!!.request.requestId

        permissionManager.respond(
            approvalId = approvalId,
            decision = PermissionDecision.DENY_ONCE,
            sessionId = sessionId,
        )

        val result = deferredResult.await()
        assertEquals(PermissionDecision.DENY_ONCE, result)
        assertFalse(permissionManager.hasSessionGrant(sessionId, CapabilityScope.FILESYSTEM_WRITE))
    }

    @Test
    fun testDuplicatePendingRequestReusesExistingDeferred() = runBlocking {
        val bridgeDir = tempFolder.newFolder("bridge")
        val events = CopyOnWriteArrayList<RuntimeEvent>()
        val permissionManager = PermissionManager(
            bridgeDir = bridgeDir,
            taskStore = null,
            onEvent = { events.add(it) },
        )

        val sessionId = "session_dup"
        val command = "git commit -m 'test'"

        val req1 = async {
            permissionManager.requestPermission(
                sessionId = sessionId,
                taskId = "task_dup",
                projectId = "proj_dup",
                capability = CapabilityScope.PROCESS_EXECUTE,
                explanation = "Commit changes",
                command = command,
            )
        }

        for (i in 0..20) {
            if (events.filterIsInstance<RuntimeEvent.PermissionRequested>().isNotEmpty()) break
            delay(20)
        }

        val req2 = async {
            permissionManager.requestPermission(
                sessionId = sessionId,
                taskId = "task_dup",
                projectId = "proj_dup",
                capability = CapabilityScope.PROCESS_EXECUTE,
                explanation = "Commit changes",
                command = command,
            )
        }

        delay(50)
        assertEquals(1, events.filterIsInstance<RuntimeEvent.PermissionRequested>().size)

        val approvalId = events.filterIsInstance<RuntimeEvent.PermissionRequested>().first().request.requestId
        permissionManager.respond(approvalId, PermissionDecision.ALLOW_ONCE, sessionId)

        assertEquals(PermissionDecision.ALLOW_ONCE, req1.await())
        assertEquals(PermissionDecision.ALLOW_ONCE, req2.await())
    }

    @Test
    fun testSessionIsolation() {
        val bridgeDir = tempFolder.newFolder("bridge")
        val permissionManager = PermissionManager(bridgeDir = bridgeDir)

        permissionManager.grantSessionCapability("session_A", CapabilityScope.PROCESS_EXECUTE, "npm start")

        assertTrue(permissionManager.hasSessionGrant("session_A", CapabilityScope.PROCESS_EXECUTE, "npm start"))
        assertFalse(permissionManager.hasSessionGrant("session_B", CapabilityScope.PROCESS_EXECUTE, "npm start"))

        permissionManager.clearSessionGrants("session_A")
        assertFalse(permissionManager.hasSessionGrant("session_A", CapabilityScope.PROCESS_EXECUTE, "npm start"))
    }

    @Test
    fun testCancelAllPendingResumesWithDeny() = runBlocking {
        val bridgeDir = tempFolder.newFolder("bridge")
        val permissionManager = PermissionManager(bridgeDir = bridgeDir)

        val req1 = async {
            permissionManager.requestPermission(
                sessionId = "s1",
                taskId = "t1",
                projectId = "p1",
                capability = CapabilityScope.PROCESS_EXECUTE,
                explanation = "Action 1",
                command = "cmd1",
            )
        }

        val req2 = async {
            permissionManager.requestPermission(
                sessionId = "s2",
                taskId = "t2",
                projectId = "p2",
                capability = CapabilityScope.FILESYSTEM_READ,
                explanation = "Action 2",
            )
        }

        delay(50)
        permissionManager.cancelAllPending()

        assertEquals(PermissionDecision.DENY_ONCE, req1.await())
        assertEquals(PermissionDecision.DENY_ONCE, req2.await())
    }
}
