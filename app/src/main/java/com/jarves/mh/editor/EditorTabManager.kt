package com.jarves.mh.editor

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class EditorTabManager {
    val openTabs = CopyOnWriteArrayList<EditorTab>()
    var activeTabId: String? = null
        private set

    fun getActiveTab(): EditorTab? {
        return openTabs.firstOrNull { it.id == activeTabId }
    }

    fun openFile(workspaceDir: File, relativePath: String): EditorTab {
        // Check if already open
        val existing = openTabs.firstOrNull { it.relativePath == relativePath }
        if (existing != null) {
            activeTabId = existing.id
            return existing
        }

        val loadResult = EditorFileManager.loadFile(workspaceDir, relativePath)
        val fileName = File(relativePath).name
        val isReadOnly = loadResult.status == FileLoadResultStatus.LARGE_FILE_CHUNKED ||
            loadResult.status == FileLoadResultStatus.BINARY_FILE
        val isBinary = loadResult.status == FileLoadResultStatus.BINARY_FILE

        val tab = EditorTab(
            relativePath = relativePath,
            displayName = fileName,
            content = loadResult.content,
            isDirty = false,
            isReadOnly = isReadOnly,
            isBinary = isBinary,
            totalSize = loadResult.totalBytes,
        )

        openTabs.add(tab)
        activeTabId = tab.id
        return tab
    }

    fun selectTab(tabId: String): EditorTab? {
        val tab = openTabs.firstOrNull { it.id == tabId }
        if (tab != null) {
            activeTabId = tab.id
        }
        return tab
    }

    fun closeTab(tabId: String): Boolean {
        val tab = openTabs.firstOrNull { it.id == tabId } ?: return false
        val index = openTabs.indexOf(tab)
        openTabs.remove(tab)

        if (activeTabId == tabId) {
            activeTabId = when {
                openTabs.isEmpty() -> null
                index < openTabs.size -> openTabs[index].id
                else -> openTabs.last().id
            }
        }
        return true
    }

    fun updateContent(tabId: String, newContent: String) {
        val tab = openTabs.firstOrNull { it.id == tabId } ?: return
        if (tab.isReadOnly) return
        if (tab.content != newContent) {
            tab.content = newContent
            tab.isDirty = true
        }
    }

    fun saveActiveTab(workspaceDir: File): Boolean {
        val tab = getActiveTab() ?: return false
        if (tab.isReadOnly || !tab.isDirty) return true
        val saved = EditorFileManager.saveFile(workspaceDir, tab.relativePath, tab.content)
        if (saved) {
            tab.isDirty = false
        }
        return saved
    }
}
