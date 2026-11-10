package dev.moavia.codriod.cli

import androidx.compose.runtime.*

/** One entry in a CLI panel's conversation. Mutable fields stream in while the CLI works. */
sealed class CliItem(val id: String) {
    class User(id: String, val text: String, val images: List<String> = emptyList()) : CliItem(id)
    class Agent(id: String, text: String = "") : CliItem(id) { var text by mutableStateOf(text) }
    class Reasoning(id: String, text: String = "") : CliItem(id) { var text by mutableStateOf(text); var done by mutableStateOf(false) }
    class Command(id: String, val command: String, val cwd: String = "") : CliItem(id) {
        var output by mutableStateOf("")
        var status by mutableStateOf(ItemStatus.Running)
        var exitCode by mutableStateOf<Int?>(null)
        var durationMs by mutableStateOf<Long?>(null)
    }
    class FileEdit(id: String, changes: List<FileChange>) : CliItem(id) {
        var changes by mutableStateOf(changes)
        var status by mutableStateOf(ItemStatus.Running)
    }
    /** Any other tool: search, web, MCP, read, fetch… */
    class Tool(id: String, title: String, val kind: String = "other") : CliItem(id) {
        var title by mutableStateOf(title)
        var detail by mutableStateOf("")
        var status by mutableStateOf(ItemStatus.Running)
    }
    class Plan(id: String, steps: List<PlanStep>, explanation: String? = null) : CliItem(id) {
        var steps by mutableStateOf(steps)
        var explanation by mutableStateOf(explanation)
    }
    class Notice(id: String, val text: String, val level: String = "info") : CliItem(id)
}

enum class ItemStatus { Running, Done, Failed, Declined }
data class FileChange(val path: String, val kind: String, val diff: String)
data class PlanStep(val text: String, val status: String) // pending | in_progress | completed

data class CliModel(val id: String, val name: String, val description: String = "", val efforts: List<String> = emptyList(),
    val defaultEffort: String? = null, val isDefault: Boolean = false, val vision: Boolean = true)
data class CliMode(val id: String, val name: String, val description: String = "")
data class CliThread(val id: String, val title: String, val updatedAt: Long = 0, val status: String = "")

/** Limits and usage the CLI reports. Null fields are unknown for this CLI. */
data class CliUsage(
    val primaryPercent: Int? = null, val primaryWindowMins: Long? = null, val primaryResetsAt: Long? = null,
    val secondaryPercent: Int? = null, val secondaryWindowMins: Long? = null, val secondaryResetsAt: Long? = null,
    val planType: String? = null,
    val tokensUsed: Long? = null, val contextWindow: Long? = null,
    val requestsThisSession: Int = 0,
    val cost: String? = null
) {
    val contextLeftPercent: Int? get() = if (tokensUsed != null && contextWindow != null && contextWindow > 0)
        (100 - (tokensUsed * 100 / contextWindow)).toInt().coerceIn(0, 100) else null
}

data class CliAccount(val signedIn: Boolean, val label: String = "", val plan: String? = null)

/** Sign-in in progress: a page to open, or a device code to type there. */
data class CliLogin(val url: String? = null, val userCode: String? = null, val message: String = "", val waiting: Boolean = true,
    val error: String? = null)

/** An approval the CLI is waiting for. */
class CliApproval(
    val title: String,
    val detail: String,
    val command: String? = null,
    val changes: List<FileChange> = emptyList(),
    val dangerous: Boolean = false,
    /** (id, label, kind) — kind: allow, always, reject */
    val options: List<Triple<String, String, String>>,
    val answer: (String) -> Unit
)

/** Codriod's permission level for a CLI. The CLI's own sandbox can't run under proot, so Codriod decides. */
enum class CliPermission(val label: String, val hint: String) {
    ReadOnly("Read only", "Reads and answers. Every change and command waits for you."),
    Auto("Auto", "Edits files and runs safe commands in this project. Asks before anything else."),
    Autopilot("Full access · Autopilot", "Never asks. Codriod still stops dangerous commands.")
}

/** What a CLI backend can do; the panel shows only what is supported. */
data class CliFeatures(
    val models: Boolean = false, val effort: Boolean = false, val modes: Boolean = false, val limits: Boolean = false,
    val threads: Boolean = false, val fork: Boolean = false, val archive: Boolean = false, val review: Boolean = false,
    val compact: Boolean = false, val images: Boolean = false, val steer: Boolean = false, val login: Boolean = false,
    val apiKeyLogin: Boolean = false, val deviceLogin: Boolean = false, val requestsCount: Boolean = false
)

/** UI state of one CLI panel; backends write it, the panel reads it. */
class CliState {
    val items = mutableStateListOf<CliItem>()
    var status by mutableStateOf("Starting…")
    var connected by mutableStateOf(false)
    var running by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var features by mutableStateOf(CliFeatures())
    var models by mutableStateOf<List<CliModel>>(emptyList())
    var model by mutableStateOf<String?>(null)
    var effort by mutableStateOf<String?>(null)
    var modes by mutableStateOf<List<CliMode>>(emptyList())
    var mode by mutableStateOf<String?>(null)
    var permission by mutableStateOf(CliPermission.Auto)
    var usage by mutableStateOf(CliUsage())
    var account by mutableStateOf<CliAccount?>(null)
    var login by mutableStateOf<CliLogin?>(null)
    var threads by mutableStateOf<List<CliThread>>(emptyList())
    var threadId by mutableStateOf<String?>(null)
    var threadTitle by mutableStateOf("New chat")
    var approval by mutableStateOf<CliApproval?>(null)
    var commands by mutableStateOf<List<Pair<String, String>>>(emptyList()) // slash commands: name, description
    var version by mutableStateOf<String?>(null)

    fun find(id: String) = items.firstOrNull { it.id == id }
    fun reset() { items.clear(); approval = null; running = false; error = null }
}

/** One protocol implementation (Codex app-server, ACP). Calls come from the UI thread. */
interface CliBackend {
    val state: CliState
    suspend fun start()
    suspend fun send(text: String, images: List<String>)
    suspend fun interrupt()
    suspend fun newThread()
    suspend fun openThread(id: String) {}
    suspend fun refreshThreads() {}
    suspend fun forkThread() {}
    suspend fun archiveThread(id: String) {}
    suspend fun compact() {}
    suspend fun review(target: String) {}
    suspend fun setModel(id: String) {}
    suspend fun setEffort(effort: String) {}
    suspend fun setMode(id: String) {}
    suspend fun refreshUsage() {}
    suspend fun login(kind: String, apiKey: String? = null) {}
    suspend fun logout() {}
    fun stop()
}
