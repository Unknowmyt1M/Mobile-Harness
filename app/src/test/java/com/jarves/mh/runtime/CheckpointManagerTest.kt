package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CheckpointManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun ignoresInternalClaudeRuntimePaths() {
        val manager = CheckpointManager(tempFolder.newFolder("checkpoints"))
        assertTrue(manager.isInternalRuntimePath(".claude"))
        assertTrue(manager.isInternalRuntimePath(".claude.json"))
        assertTrue(manager.isInternalRuntimePath(".claude/settings.json"))
        assertFalse(manager.isInternalRuntimePath("src/main.js"))
    }

    @Test
    fun snapshotAndDetectChangedFiles() {
        val checkpointsRoot = tempFolder.newFolder("checkpoints")
        val workspace = tempFolder.newFolder("workspace")
        val manager = CheckpointManager(checkpointsRoot)

        val file1 = File(workspace, "hello.txt").apply { writeText("original content") }
        val before = manager.snapshot(workspace)

        assertEquals(1, before.size)

        // Modify file
        file1.writeText("modified content")
        val changed = manager.detectChangedFiles(workspace, before)

        assertEquals(listOf("hello.txt"), changed)
    }

    @Test
    fun namedCheckpointCreateListRestoreAndDelete() {
        val checkpointsRoot = tempFolder.newFolder("checkpoints_named")
        val workspace = tempFolder.newFolder("workspace_named")
        val manager = CheckpointManager(checkpointsRoot)
        val projectId = "test-project-123"

        // Initial files
        File(workspace, "index.js").writeText("console.log('v1');")
        File(workspace, "README.md").writeText("# Project v1")

        // 1. Create named checkpoint
        val meta = manager.createNamedCheckpoint(
            projectId = projectId,
            workspace = workspace,
            label = "v1-baseline",
            taskId = "task-alpha"
        )
        assertEquals(2, meta.fileCount)
        assertEquals("v1-baseline", meta.label)
        assertEquals("task-alpha", meta.taskId)

        // 2. List checkpoints
        val list = manager.listCheckpoints(projectId)
        assertEquals(1, list.size)
        assertEquals(meta.id, list[0].id)

        // 3. Mutate workspace (modify a file, delete a file, add a new file)
        File(workspace, "index.js").writeText("console.log('v2-corrupted');")
        File(workspace, "README.md").delete()
        File(workspace, "unwanted.tmp").writeText("temporary garbage")

        // 4. Restore named checkpoint
        val restored = manager.restoreNamedCheckpoint(projectId, workspace, meta.id)
        assertTrue(restored)

        // Verify restored state
        assertEquals("console.log('v1');", File(workspace, "index.js").readText())
        assertTrue(File(workspace, "README.md").exists())
        assertEquals("# Project v1", File(workspace, "README.md").readText())
        assertFalse(File(workspace, "unwanted.tmp").exists())

        // 5. Delete named checkpoint
        val deleted = manager.deleteNamedCheckpoint(projectId, meta.id)
        assertTrue(deleted)
        assertEquals(0, manager.listCheckpoints(projectId).size)
    }

    @Test
    fun computeDiffSummaryCalculatesTotals() {
        val checkpointsRoot = tempFolder.newFolder("checkpoints_diff")
        val workspace = tempFolder.newFolder("workspace_diff")
        val manager = CheckpointManager(checkpointsRoot)
        val projectId = "proj-diff"

        val f = File(workspace, "code.py").apply { writeText("line1\nline2\nline3\n") }
        manager.createCheckpoint(projectId, workspace)

        // Mutate
        f.writeText("line1\nline2_modified\nline3\nline4_added\n")
        manager.saveChangedPaths(projectId, listOf("code.py"))

        val diff = manager.computeDiffSummary(projectId, workspace)
        assertEquals(1, diff.totalFilesChanged)
        assertTrue(diff.totalAdditions >= 1)
        assertTrue(diff.totalDeletions >= 1)
    }
}
