package com.jarves.mh.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EditorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var workspace: File

    @Before
    fun setUp() {
        workspace = tempFolder.newFolder("workspace")
    }

    @Test
    fun `safe path resolution prevents escaping workspace`() {
        val safe = EditorFileManager.resolveSafeFile(workspace, "src/main.js")
        assertTrue(safe.canonicalPath.startsWith(workspace.canonicalPath))

        var failed = false
        try {
            EditorFileManager.resolveSafeFile(workspace, "../../secret.txt")
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun `loads and saves normal UTF-8 text files`() {
        File(workspace, "hello.txt").writeText("Hello Mobile Harness!")
        val result = EditorFileManager.loadFile(workspace, "hello.txt")

        assertEquals(FileLoadResultStatus.SUCCESS, result.status)
        assertEquals("Hello Mobile Harness!", result.content)
        assertFalse(result.isChunked)

        val saved = EditorFileManager.saveFile(workspace, "hello.txt", "Updated content")
        assertTrue(saved)
        assertEquals("Updated content", File(workspace, "hello.txt").readText())
    }

    @Test
    fun `detects binary files and marks read-only`() {
        val binFile = File(workspace, "image.png")
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x00, 0x01)
        binFile.writeBytes(bytes)

        val result = EditorFileManager.loadFile(workspace, "image.png")
        assertEquals(FileLoadResultStatus.BINARY_FILE, result.status)
    }

    @Test
    fun `chunks large files above 1MB safeguard`() {
        val largeFile = File(workspace, "huge.log")
        // Create file larger than 1MB
        val sb = StringBuilder()
        repeat(20_000) {
            sb.append("This is a line in a very large file intended to exceed one megabyte.\n")
        }
        largeFile.writeText(sb.toString())
        assertTrue(largeFile.length() > EditorFileManager.MAX_FILE_SIZE_BYTES)

        val result = EditorFileManager.loadFile(workspace, "huge.log", maxChars = 1000)
        assertEquals(FileLoadResultStatus.LARGE_FILE_CHUNKED, result.status)
        assertTrue(result.isChunked)
        assertEquals(1000, result.content.length)
    }

    @Test
    fun `tab manager opens, edits, dirty tracks, closes, and saves`() {
        val tabManager = EditorTabManager()
        File(workspace, "script.py").writeText("print('hi')")

        // 1. Open file
        val tab = tabManager.openFile(workspace, "script.py")
        assertEquals("script.py", tab.displayName)
        assertEquals("print('hi')", tab.content)
        assertFalse(tab.isDirty)
        assertEquals(tab.id, tabManager.activeTabId)

        // 2. Edit content -> dirty
        tabManager.updateContent(tab.id, "print('hello world')")
        assertTrue(tab.isDirty)

        // 3. Save active tab
        val saved = tabManager.saveActiveTab(workspace)
        assertTrue(saved)
        assertFalse(tab.isDirty)
        assertEquals("print('hello world')", File(workspace, "script.py").readText())

        // 4. Close tab
        val closed = tabManager.closeTab(tab.id)
        assertTrue(closed)
        assertEquals(0, tabManager.openTabs.size)
        assertEquals(null, tabManager.activeTabId)
    }

    @Test
    fun `buildFileTree constructs valid hierarchy`() {
        File(workspace, "src/components").mkdirs()
        File(workspace, "build/intermediates").mkdirs()
        File(workspace, "src/components/Button.tsx").writeText("<button />")
        File(workspace, "package.json").writeText("{}")

        val tree = EditorFileManager.buildFileTree(workspace)
        assertNotNull(tree)
        assertTrue(tree.any { it.name == "src" && it.isDirectory })
        assertTrue(tree.any { it.name == "package.json" && !it.isDirectory })
        // Build folder should be ignored
        assertFalse(tree.any { it.name == "build" })
    }
}
