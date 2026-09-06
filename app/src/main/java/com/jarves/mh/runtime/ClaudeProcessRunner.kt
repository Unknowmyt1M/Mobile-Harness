package com.jarves.mh.runtime

import android.content.Context
import android.util.Log
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.ProviderProfile
import java.io.File

class ClaudeProcessRunner(
    private val context: Context,
    private val installer: RuntimeInstaller,
) {
    @Volatile var activeProcess: Process? = null
        private set
    @Volatile var userStopRequested: Boolean = false

    fun launch(
        projectId: String,
        projectSlug: String,
        projectKind: ProjectKind,
        contextPrompt: String,
        provider: ProviderProfile,
        authToken: String?,
        localGatewayUrl: String?,
        workspace: File,
    ): Process {
        userStopRequested = false
        val installed = installer.installedRuntime()
        installer.ensureSettingsAndHooks()

        val launchConfig = RuntimeLaunchConfigBuilder.build(provider, authToken = authToken, localGatewayUrl = localGatewayUrl)
        val guestWorkspacePath = "/workspace/$projectSlug"

        // Fix ARG_MAX / E2BIG bug:
        // Linux ARG_MAX is 128KB. If the context prompt is large, write to a workspace task file
        // instead of overflowing the command line argument.
        val commandPrompt = if (contextPrompt.length > MAX_ARG_PROMPT_LENGTH) {
            val taskFile = File(workspace, ".task-context.md")
            taskFile.writeText(contextPrompt)
            "Read and execute the task instructions and prior history in .task-context.md"
        } else {
            contextPrompt
        }

        val command = buildList {
            add(launchConfig.executable)
            add("--bare")
            add("-p")
            add(commandPrompt)
            add("--output-format")
            add("stream-json")
            add("--include-partial-messages")
            add("--verbose")
            add("--model")
            add(provider.model)
            add("--max-turns")
            add("25")
        }

        Log.d("ClaudeProcessRunner", "Launching Claude Code: executable=${launchConfig.executable}, model=${provider.model}")
        val process = installer.process(
            installed.proot,
            installed.rootfs,
            workspace,
            launchConfig.environment,
            command,
            guestWorkspacePath = guestWorkspacePath,
        )

        activeProcess = process
        (process as? NativeSpawnProcess)?.let {
            // Record active PID for orphan process recovery
            OrphanProcessCleaner.recordActivePid(context, it.pid)
        }

        if (userStopRequested) {
            destroyProcess()
        }

        return process
    }

    fun stop() {
        userStopRequested = true
        destroyProcess()
    }

    fun destroyProcess() {
        val proc = activeProcess ?: return
        Thread {
            runCatching {
                proc.destroy()
                Thread.sleep(1_000)
                if (proc.isAlive) {
                    proc.destroyForcibly()
                }
            }
        }.apply { isDaemon = true }.start()
        OrphanProcessCleaner.clearActivePid(context)
    }

    fun cleanup() {
        activeProcess = null
        OrphanProcessCleaner.clearActivePid(context)
    }

    companion object {
        // Keep command-line argument safely below Linux 128KB limit
        private const val MAX_ARG_PROMPT_LENGTH = 24_000
    }
}
