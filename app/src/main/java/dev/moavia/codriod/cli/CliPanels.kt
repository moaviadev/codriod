package dev.moavia.codriod.cli

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.*
import dev.moavia.codriod.linux.CliCatalog
import dev.moavia.codriod.linux.CliSpec
import dev.moavia.codriod.linux.LinuxEnvironment
import kotlinx.coroutines.*
import java.io.File

/** One CLI panel: its backend, its log and the composer's draft and attachments. */
class CliPanel(private val context: Context, val cli: CliSpec, val backend: CliBackend, private val scope: CoroutineScope,
    private val onTerminal: (String?) -> Unit) {
    val state get() = backend.state
    var draft by mutableStateOf("")
    val attachments = mutableStateListOf<String>()
    val log = mutableStateListOf<String>()
    private var started = false

    fun addLog(line: String) { scope.launch(Dispatchers.Main) { log += line; if (log.size > 400) log.removeRange(0, log.size - 300) } }

    /** Runs a backend call; errors show in the panel instead of crashing. */
    fun act(block: suspend CliBackend.() -> Unit) {
        scope.launch(Dispatchers.Main) {
            try { backend.block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { state.error = error.message ?: error.javaClass.simpleName }
        }
    }

    fun ensureStarted() {
        if (started) return
        started = true
        act { start() }
    }

    fun restart() { backend.stop(); state.reset(); state.status = "Restarting…"; started = false; ensureStarted() }

    fun send() {
        val text = draft.trim()
        val images = attachments.toList()
        if (text.isEmpty() && images.isEmpty()) return
        // While a step waits for approval, yes / no / always (or ہاں / نہیں) answers it.
        state.approval?.let { approval ->
            val decision = dev.moavia.codriod.agent.VoiceText.approvalAnswer(text)
            if (decision != null) {
                val wanted = when (decision) { dev.moavia.codriod.agent.Decision.Allow -> "allow"; dev.moavia.codriod.agent.Decision.AlwaysAllow -> "always"; else -> "reject" }
                approval.options.firstOrNull { it.third == wanted }?.let { approval.answer(it.first); draft = ""; return }
            }
        }
        draft = ""; attachments.clear()
        when {
            text == "/new" || text == "/clear" -> act { newThread() }
            text == "/compact" -> act { compact() }
            text == "/fork" -> act { forkThread() }
            text.startsWith("/review") -> act { review(text.removePrefix("/review").trim().ifEmpty { "uncommitted" }) }
            else -> act { send(text, images) }
        }
    }

    /** Copies a picked photo to a private file the CLI can be given. */
    fun attachUri(uri: Uri) {
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(context.cacheDir, "cli-attach").apply { mkdirs() }
                    val name = (uri.lastPathSegment ?: "image").substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val out = File(dir, "${System.currentTimeMillis()}-$name" + if (name.contains('.')) "" else ".jpg")
                    context.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
                    out.takeIf { it.length() > 0 }
                }.getOrNull()
            }
            file?.let { attachments += it.absolutePath }
        }
    }

    fun openTerminal(command: String? = null) = onTerminal(command)
    fun stop() { backend.stop(); started = false }
}

/** All CLI panels of the open project, created on first use. */
class CliPanels(
    private val context: Context,
    private val scope: CoroutineScope,
    private val linux: LinuxEnvironment,
    private val project: () -> Pair<File, String>?,
    private val openTerminal: (String?) -> Unit
) {
    private val panels = HashMap<String, CliPanel>()
    /** CLI id of the open panel, or null. */
    var openId by mutableStateOf<String?>(null)
    var expanded by mutableStateOf(false)

    fun hasPanel(id: String) = CliCatalog.byId(id)?.protocol != null

    fun panel(id: String): CliPanel? {
        val cli = CliCatalog.byId(id) ?: return null
        val mount = project() ?: return null
        val key = "$id@${mount.first.absolutePath}"
        return panels.getOrPut(key) {
            lateinit var panel: CliPanel
            val host = object : CliHost {
                override fun launch(command: List<String>) = linux.spawn(command, mount)
                override val guestCwd = linux.guestProjectPath(mount.second)
                override fun guestPathForImage(hostPath: String) = linux.shareIntoUbuntu(hostPath)
                override fun openUrl(url: String) {
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    catch (e: Exception) { panel.addLog("openUrl failed: ${e.message} ($url)"); panel.state.error = "Could not open browser for sign-in. Copy the URL from the log." }
                }
                override fun log(line: String) = panel.addLog(line)
            }
            val backend: CliBackend = when (cli.protocol) {
                "codex" -> CodexBackend(host, scope)
                else -> AcpBackend(host, scope, AcpConfig(cli.id, cli.name, cli.acpCommands, cli.loginCommand, cli.countsRequests))
            }
            CliPanel(context, cli, backend, scope) { command -> openId = null; openTerminal(command ?: cli.command) }.also { panel = it }
        }
    }

    fun open(id: String) {
        if (!hasPanel(id)) { openTerminal(CliCatalog.byId(id)?.command); return }
        openId = id
        panel(id)?.ensureStarted()
    }

    /** The project changed or closed: stop every CLI. */
    fun stopAll() { panels.values.forEach { it.stop() }; panels.clear(); openId = null }
}
