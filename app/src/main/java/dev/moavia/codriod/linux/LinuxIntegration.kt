package dev.moavia.codriod.linux

import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalViewClient
import dev.moavia.codriod.TerminalExecutionBackend
import dev.moavia.codriod.agent.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** A terminal running bash inside Ubuntu (proot). */
class UbuntuShellBackend(private val linux: LinuxEnvironment, private val project: () -> Pair<File, String>?) : TerminalExecutionBackend {
    override val displayName = "Ubuntu"

    override fun createSession(viewClient: TerminalViewClient, sessionClient: TerminalSessionClient): TerminalSession {
        val spec = linux.spec(project())
        val argv = spec.argv(listOf("/bin/bash", "--login"))
        val env = (spec.hostEnv() + mapOf("TERM" to "xterm-256color", "HOME" to linux.base.absolutePath))
            .map { (k, v) -> "$k=$v" }.toTypedArray()
        return TerminalSession(argv.first(), linux.base.absolutePath, argv.toTypedArray(), env, 3000, sessionClient)
    }
}

/** Gives Codriod Agent the Ubuntu environment: run commands, install tools and CLIs, and run Codriod Doctor. */
class LinuxToolset(
    private val linux: LinuxEnvironment,
    private val approver: Approver,
    private val project: () -> Pair<File, String>?
) : AgentToolset {
    private val names = setOf("linux_run", "linux_install", "linux_doctor", "linux_status")

    override fun specs(mode: AgentMode): List<ToolSpec> = buildList {
        add(ToolSpec("linux_status", "Show whether Ubuntu is installed, the Node.js version and which AI CLIs are installed.",
            AgentTools.schema(required = emptyList())))
        if (!mode.canEdit) return@buildList
        add(ToolSpec("linux_run", "Run a bash command in the phone's Ubuntu 24.04 (proot, you are root, no sudo). " +
            "The open project is mounted and is the working directory. Use for apt-get, node/npm/npx, python3/pip, git, builds, tests " +
            "and non-interactive AI CLI calls (claude -p, codex exec, gemini -p). Start long servers with nohup … & and read their log.",
            AgentTools.schema(Triple("command", "string", "bash command line"),
                Triple("timeout_seconds", "integer", "default 300, max 1800"), required = listOf("command"))))
        add(ToolSpec("linux_install", "Install with Codriod's one-tap installer (auto-repairs known errors): 'ubuntu', 'node', or a CLI id: " +
            CliCatalog.all.joinToString { it.id } + ".",
            AgentTools.schema(Triple("item", "string", "ubuntu, node or a CLI id"), required = listOf("item"))))
        add(ToolSpec("linux_doctor", "Run Codriod Doctor's health check of Ubuntu (runtime, DNS, apt, Node.js, CLIs). With repair=true it fixes what it can.",
            AgentTools.schema(Triple("repair", "boolean", "fix problems"), required = emptyList())))
    }

    override fun handles(name: String) = name in names

    private suspend fun awaitIdle() { delay(300); while (linux.busy || linux.checking) delay(500) }

    override suspend fun execute(name: String, args: JSONObject, mode: AgentMode): ToolOutcome = when (name) {
        "linux_status" -> {
            val text = if (linux.phase == LinuxPhase.Unsupported) "Not available: ${linux.unsupportedReason}"
                else if (!linux.ready) "Ubuntu is not installed yet (phase ${linux.phase}). Use linux_install with item 'ubuntu', or the user can tap Add-ons → Linux & AI CLIs."
                else linux.agentNotes(project()?.second)
            ToolOutcome(text, "Linux status")
        }
        "linux_run" -> run(args.optString("command").trim(), args.optInt("timeout_seconds", 300).coerceIn(5, 1800))
        "linux_install" -> install(args.optString("item").trim().lowercase())
        "linux_doctor" -> {
            val repair = args.optBoolean("repair", false)
            if (repair && approver.approve(ApprovalRequest.Action("Repair Ubuntu with Codriod Doctor", "Fixes DNS, apt, Node.js and CLIs automatically.")) == Decision.Deny)
                ToolOutcome("The user declined the repair.", "Doctor declined")
            else {
                withContext(Dispatchers.Main) { linux.runChecks(repair) }
                awaitIdle()
                val lines = linux.checks.joinToString("\n") { (if (it.ok) "✓ " else "✗ ") + it.title + ": " + it.detail }
                ToolOutcome(lines.ifBlank { "No checks ran (Linux not installed?)" }, "Codriod Doctor · ${linux.checks.count { !it.ok }} problems",
                    detail = lines)
            }
        }
        else -> ToolOutcome("Error: unknown tool $name", "Unknown tool")
    }

    private suspend fun run(command: String, timeout: Int): ToolOutcome {
        require(command.isNotEmpty()) { "command is required" }
        if (!linux.ready) return ToolOutcome("Ubuntu is not installed. Call linux_install with item 'ubuntu' first (or ask the user to install it from Add-ons → Linux & AI CLIs).",
            "Ubuntu not installed")
        val request = ApprovalRequest.Command("[ubuntu] $command", CommandSafety.isSafe(command), CommandSafety.isDangerous(command))
        if (approver.approve(request) == Decision.Deny) return ToolOutcome("The user declined running: $command", "Command declined: ${command.take(60)}")
        val result = linux.exec(listOf("/bin/bash", "-lc", command), project(), timeoutSeconds = timeout.toLong())
        val output = result.output.let { if (it.length > 12_000) "…" + it.takeLast(12_000) else it }
        val status = if (result.timedOut) "timed out after $timeout s" else "exit code ${result.code}"
        val hint = if (result.code != 0) LinuxDoctor.diagnose(result.output, result.code).firstOrNull()?.let { "\nCodriod Doctor: ${it.title} (linux_doctor with repair=true can fix this)." }.orEmpty() else ""
        return ToolOutcome("$status\n$output$hint".trimEnd(), "🐧 $ ${command.take(60)} · $status", changedFiles = true,
            detail = "$ $command\n$output".trimEnd())
    }

    private suspend fun install(item: String): ToolOutcome {
        val cli = CliCatalog.byId(item)
        if (item !in setOf("ubuntu", "node") && cli == null) return ToolOutcome("Unknown item '$item'. Use ubuntu, node or one of: ${CliCatalog.all.joinToString { it.id }}", "Unknown item")
        val title = when { cli != null -> cli.name; item == "node" -> "Node.js 22"; else -> "Ubuntu 24.04" }
        if (approver.approve(ApprovalRequest.Action("Install $title", "Downloads and installs $title in Codriod's Ubuntu. Codriod Doctor repairs known errors.")) == Decision.Deny)
            return ToolOutcome("The user declined installing $title.", "Install declined")
        withContext(Dispatchers.Main) {
            when {
                cli != null -> linux.installCli(cli.id)
                item == "node" -> linux.installNode()
                else -> linux.install()
            }
        }
        awaitIdle()
        val failed = linux.steps.filter { it.status == StepStatus.Failed }
        val summary = linux.steps.joinToString("\n") { "${it.status}: ${it.title} ${it.detail}".trim() }
        return if (failed.isEmpty()) ToolOutcome("Installed $title.\n$summary", "Installed $title", changedFiles = true)
        else ToolOutcome("Install failed.\n$summary\n\nProblem: ${linux.problem}\nLog tail:\n${linux.logText().takeLast(4000)}", "Install of $title failed")
    }
}
