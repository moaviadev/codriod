package dev.moavia.codriod.cli

import dev.moavia.codriod.agent.CommandSafety
import dev.moavia.codriod.agent.DiffKind
import dev.moavia.codriod.agent.LineDiff
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** How to start a CLI's ACP mode, tried in order (flags differ between versions). */
data class AcpConfig(
    val cliId: String,
    val name: String,
    val commands: List<List<String>>,
    /** Command typed in the terminal to sign in (when the agent needs it). */
    val loginCommand: String? = null,
    /** Copilot counts premium requests per prompt. */
    val countsRequests: Boolean = false
)

/** Unified diff text (like `git diff` hunks) from an ACP diff's old and new text. */
fun acpDiffText(oldText: String?, newText: String): String = buildString {
    LineDiff.diff(oldText.orEmpty(), newText).forEach { line ->
        append(when (line.kind) { DiffKind.Added -> "+"; DiffKind.Removed -> "-"; DiffKind.Same -> " "; DiffKind.Gap -> "@@ " })
        append(line.text).append('\n')
    }
}.trimEnd()

/**
 * Any agent that speaks the Agent Client Protocol (agentclientprotocol.com): Gemini CLI, Qwen Code, Claude Code
 * (through claude-code-acp), opencode, GitHub Copilot CLI (`copilot --acp`). The agent reads and writes files itself
 * inside Ubuntu; Codriod shows messages, thinking, tool calls with diffs and output, plans, modes, models and
 * permission requests.
 */
class AcpBackend(
    private val host: CliHost,
    private val scope: CoroutineScope,
    val config: AcpConfig,
    override val state: CliState = CliState(),
    private val ui: CoroutineDispatcher = Dispatchers.Main
) : CliBackend {
    private var process: Process? = null
    private var rpc: JsonRpcPeer? = null
    private var sessionId: String? = null
    private var authMethods: List<JSONObject> = emptyList()
    private var canLoad = false
    private var canList = false
    private var modelConfigId: String? = null
    private var effortConfigId: String? = null
    private var legacyModels = false
    private var turn = 0
    private var pendingAnswer: CompletableDeferred<String>? = null
    /** True while session/load replays an old conversation. */
    private var replaying = false

    private val peer get() = rpc ?: throw RpcError(-32000, "${config.name} is not running")

    override suspend fun start() {
        state.status = "Starting ${config.name}…"
        var lastError: Exception? = null
        for (command in config.commands) {
            try { connect(command); lastError = null; break }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { lastError = error; host.log("${command.joinToString(" ")}: ${error.message}"); disconnect() }
        }
        if (lastError != null) throw RpcError(-32601, "${config.name} did not start its chat mode (ACP). Update the CLI, or use the terminal. (${lastError.message})")
        state.connected = true
        newSessionOrLogin()
    }

    private suspend fun connect(command: List<String>) {
        val p = withContext(Dispatchers.IO) { host.launch(command) }
        process = p
        scope.launch(Dispatchers.IO) { runCatching { p.errorStream.bufferedReader().forEachLine { host.log("${config.cliId}: $it") } } }
        val peer = JsonRpcPeer(p.inputStream, p.outputStream, scope, jsonrpcField = true, ::onNotification, ::onRequest, host::log)
        peer.onClosed = { scope.launch(ui) { state.connected = false; state.running = false; state.status = "${config.name} stopped" } }
        rpc = peer
        peer.start()
        val init = peer.requestObject("initialize", JSONObject()
            .put("protocolVersion", 1)
            .put("clientCapabilities", JSONObject().put("fs", JSONObject().put("readTextFile", false).put("writeTextFile", false)).put("terminal", false))
            .put("clientInfo", JSONObject().put("name", "codriod").put("title", "Codriod").put("version", "1.0")), timeoutMs = 45_000)
        val caps = init.optJSONObject("agentCapabilities") ?: JSONObject()
        canLoad = caps.optBoolean("loadSession")
        canList = caps.optJSONObject("sessionCapabilities")?.has("list") == true
        authMethods = init.optJSONArray("authMethods").objects()
        state.version = init.optJSONObject("agentInfo")?.let { "${it.optString("title", it.optString("name"))} ${it.optString("version")}".trim() }
        state.features = CliFeatures(images = caps.optJSONObject("promptCapabilities")?.optBoolean("image") == true,
            threads = canList || canLoad, login = authMethods.isNotEmpty() || config.loginCommand != null,
            requestsCount = config.countsRequests)
    }

    private fun disconnect() { rpc?.close(); rpc = null; process?.destroy(); process = null }

    private suspend fun newSessionOrLogin() {
        try {
            newSession()
            state.account = CliAccount(true, "Signed in")
            state.status = "Ready"
            refreshThreads()
        } catch (error: RpcError) {
            val msg = error.message.orEmpty().lowercase()
            if (error.code == -32000 || msg.contains("auth") || msg.contains("sign in") || msg.contains("login") || msg.contains("not authenticated") || msg.contains("unauthorized")) {
                state.account = CliAccount(false)
                state.status = "Sign in to ${config.name}"
                // Ensure login feature is enabled even if authMethods was empty
                if (!state.features.login && config.loginCommand != null) state.features = state.features.copy(login = true)
            } else throw error
        }
    }

    private suspend fun newSession() {
        val r = peer.requestObject("session/new", JSONObject().put("cwd", host.guestCwd).put("mcpServers", JSONArray()), timeoutMs = 90_000)
        sessionId = r.optString("sessionId")
        state.threadId = sessionId
        applySession(r)
    }

    private fun applySession(r: JSONObject) {
        r.optJSONObject("modes")?.let { m ->
            state.modes = m.optJSONArray("availableModes").objects().map { CliMode(it.optString("id"), it.optString("name"), it.optString("description")) }
            state.mode = m.str("currentModeId")
        }
        r.optJSONArray("configOptions")?.let { applyConfig(it) }
        r.optJSONObject("models")?.let { m -> // older agents (unstable "models")
            legacyModels = true
            state.models = m.optJSONArray("availableModels").objects().map { CliModel(it.optString("modelId"), it.optString("name", it.optString("modelId")), it.optString("description")) }
            state.model = m.str("currentModelId")
        }
        state.features = state.features.copy(models = state.models.isNotEmpty(), effort = effortConfigId != null, modes = true)
    }

    private fun selectOptions(option: JSONObject): List<Pair<String, String>> {
        val raw = option.optJSONArray("options") ?: return emptyList()
        return raw.objects().flatMap { o ->
            if (o.has("group")) o.optJSONArray("options").objects().map { it.optString("value") to it.optString("name", it.optString("value")) }
            else listOf(o.optString("value") to o.optString("name", o.optString("value")))
        }
    }

    private fun applyConfig(options: JSONArray) {
        options.objects().forEach { o ->
            when (o.optString("category")) {
                "model" -> {
                    modelConfigId = o.optString("id"); legacyModels = false
                    state.models = selectOptions(o).map { (v, n) -> CliModel(v, n) }
                    state.model = o.str("currentValue")
                }
                "thought_level" -> {
                    effortConfigId = o.optString("id")
                    val levels = selectOptions(o).map { it.first }
                    state.models = state.models.map { it.copy(efforts = levels) }
                    state.effort = o.str("currentValue")
                }
                "mode" -> if (state.modes.isEmpty()) {
                    state.modes = selectOptions(o).map { (v, n) -> CliMode(v, n) }
                    state.mode = o.str("currentValue")
                }
            }
        }
        state.features = state.features.copy(models = state.models.isNotEmpty(), effort = effortConfigId != null)
    }

    override suspend fun refreshThreads() {
        if (!canList) return
        val r = runCatching { peer.requestObject("session/list", JSONObject().put("cwd", host.guestCwd)) }.getOrNull() ?: return
        state.threads = r.optJSONArray("sessions").objects().map {
            CliThread(it.optString("sessionId"), it.str("title") ?: "Session ${it.optString("sessionId").take(8)}",
                runCatching { java.time.Instant.parse(it.optString("updatedAt")).toEpochMilli() }.getOrDefault(0L))
        }
    }

    override suspend fun openThread(id: String) {
        if (!canLoad) return
        state.reset()
        replaying = true
        try {
            val r = peer.requestObject("session/load", JSONObject().put("sessionId", id).put("cwd", host.guestCwd).put("mcpServers", JSONArray()), timeoutMs = 120_000)
            sessionId = id; state.threadId = id
            applySession(r)
            state.threadTitle = state.threads.firstOrNull { it.id == id }?.title ?: "Chat"
        } finally { replaying = false }
    }

    override suspend fun send(text: String, images: List<String>) {
        state.error = null
        if (sessionId == null) newSession()
        val prompt = JSONArray()
        if (text.isNotBlank()) prompt.put(JSONObject().put("type", "text").put("text", text))
        images.forEach { path ->
            val file = File(path)
            if (file.isFile) prompt.put(JSONObject().put("type", "image").put("mimeType", if (path.endsWith(".png", true)) "image/png" else "image/jpeg")
                .put("data", dev.moavia.codriod.git.Base64Codec.encode(file.readBytes())))
        }
        if (state.items.none { it is CliItem.User }) state.threadTitle = text.lineSequence().firstOrNull().orEmpty().take(50).ifBlank { "New chat" }
        state.items += CliItem.User("u${System.nanoTime()}", text, images)
        turn++
        state.running = true
        try {
            val r = peer.requestObject("session/prompt", JSONObject().put("sessionId", sessionId).put("prompt", prompt), timeoutMs = 3 * 60 * 60_000L)
            if (config.countsRequests) state.usage = state.usage.copy(requestsThisSession = state.usage.requestsThisSession + 1)
            r.optJSONObject("usage")?.let { u -> state.usage = state.usage.copy(tokensUsed = u.optLong("totalTokens").takeIf { it > 0 } ?: state.usage.tokensUsed) }
            when (r.optString("stopReason")) {
                "refusal" -> state.items += CliItem.Notice("n${System.nanoTime()}", "${config.name} refused this request.", "warning")
                "max_tokens", "max_turn_requests" -> state.items += CliItem.Notice("n${System.nanoTime()}", "${config.name} stopped at its limit. Say “continue” to go on.", "warning")
                "cancelled" -> state.items += CliItem.Notice("n${System.nanoTime()}", "Stopped.")
            }
        } catch (error: RpcError) {
            val msg = error.message.orEmpty().lowercase()
            if (error.code == -32000 || msg.contains("auth") || msg.contains("sign in") || msg.contains("login") || msg.contains("not authenticated") || msg.contains("unauthorized")) {
                state.account = CliAccount(false); state.status = "Sign in to ${config.name}"
            }
            throw error
        } finally {
            state.running = false
            pendingAnswer?.complete("cancelled")
        }
    }

    override suspend fun interrupt() { sessionId?.let { runCatching { peer.notify("session/cancel", JSONObject().put("sessionId", it)) } } }

    override suspend fun newThread() { state.reset(); state.threadTitle = "New chat"; sessionId = null; state.threadId = null; newSession() }

    override suspend fun setModel(id: String) {
        val sid = sessionId ?: return
        if (modelConfigId != null) {
            val r = peer.requestObject("session/set_config_option", JSONObject().put("sessionId", sid).put("configId", modelConfigId).put("value", id))
            r.optJSONArray("configOptions")?.let { applyConfig(it) } ?: run { state.model = id }
        } else if (legacyModels) {
            peer.requestObject("session/set_model", JSONObject().put("sessionId", sid).put("modelId", id)); state.model = id
        }
    }

    override suspend fun setEffort(effort: String) {
        val sid = sessionId ?: return
        val cid = effortConfigId ?: return
        val r = peer.requestObject("session/set_config_option", JSONObject().put("sessionId", sid).put("configId", cid).put("value", effort))
        r.optJSONArray("configOptions")?.let { applyConfig(it) } ?: run { state.effort = effort }
    }

    override suspend fun setMode(id: String) {
        val sid = sessionId ?: return
        peer.requestObject("session/set_mode", JSONObject().put("sessionId", sid).put("modeId", id))
        state.mode = id
    }

    /** kind = an auth method id from the agent, or "terminal" to sign in with the CLI's own login in the terminal. */
    override suspend fun login(kind: String, apiKey: String?) {
        if (kind == "terminal" || authMethods.none { it.optString("id") == kind && it.optString("type") != "terminal" }) {
            config.loginCommand?.let { host.log("login: $it") }
            state.login = CliLogin(message = "Finish signing in in the terminal, then tap \u201cI\u2019m signed in\u201d.")
            return
        }
        state.login = CliLogin(message = "Opening sign-in page…")
        try {
            val r = peer.requestObject("authenticate", JSONObject().put("methodId", kind), timeoutMs = 10 * 60_000L)
            // Some agents return a URL to open, some return a device code flow
            val url = r.str("url") ?: r.str("authUrl") ?: r.str("verificationUri") ?: r.str("verification_uri")
            val userCode = r.str("userCode") ?: r.str("user_code") ?: r.str("code")
            if (url != null || userCode != null) {
                state.login = CliLogin(url = url, userCode = userCode,
                    message = if (userCode != null) "Enter this code on the sign-in page." else "Complete sign-in in your browser, then come back.",
                    waiting = true)
                url?.let { host.openUrl(it) }
                // Wait for the agent to confirm login via a subsequent notification or poll
                // The login state will be cleared by retryAfterLogin() or a notification
            } else {
                // Immediate success (e.g. already authenticated or API key flow)
                state.login = null
                newSessionOrLogin()
            }
        } catch (error: RpcError) {
            val msg = error.message.orEmpty()
            // If the error contains a URL, extract and open it (some agents send auth URL in the error)
            val urlInError = Regex("https?://[^\\s\"']+").find(msg)?.value
            if (urlInError != null) {
                state.login = CliLogin(url = urlInError, message = "Sign in in your browser, then tap \u201cI\u2019m signed in\u201d.", waiting = true)
                host.openUrl(urlInError)
            } else {
                state.login = CliLogin(waiting = false,
                    error = when {
                        msg.contains("timeout", ignoreCase = true) -> "Sign-in timed out. Try again, or sign in in the terminal."
                        msg.contains("cancel", ignoreCase = true) -> "Sign-in was cancelled."
                        else -> msg.ifBlank { "Sign-in failed. Try again, or sign in in the terminal." }
                    })
            }
        }
    }

    /** After signing in in the terminal or browser. */
    suspend fun retryAfterLogin() {
        state.login = CliLogin(message = "Checking sign-in…", waiting = true, error = null)
        try {
            newSessionOrLogin()
            if (state.account?.signedIn == true) state.login = null
            else state.login = CliLogin(waiting = false, error = "Still not signed in. Complete sign-in and try again.")
        } catch (error: RpcError) {
            state.login = CliLogin(waiting = false, error = error.message ?: "Sign-in check failed. Try again.")
        }
    }

    fun authOptions(): List<Pair<String, String>> = authMethods.filter { it.optString("type") != "terminal" }
        .map { it.optString("id") to it.optString("name", it.optString("id")) }

    // ---- updates ----------------------------------------------------------------------------------------------------

    private fun relative(path: String) = CliPolicy.relative(path, host.guestCwd)

    private fun blockText(content: JSONObject?): String = when (content?.optString("type")) {
        "text" -> content.optString("text")
        "resource_link" -> "[${content.optString("name")}]"
        "resource" -> content.optJSONObject("resource")?.optString("text").orEmpty()
        "image" -> "🖼"
        else -> ""
    }

    private suspend fun onNotification(method: String, p: JSONObject) = withContext(ui) {
        if (method != "session/update") return@withContext
        val u = p.optJSONObject("update") ?: return@withContext
        when (u.optString("sessionUpdate")) {
            "user_message_chunk" -> if (replaying) {
                val text = blockText(u.optJSONObject("content"))
                val last = state.items.lastOrNull()
                if (last is CliItem.User) state.items[state.items.lastIndex] = CliItem.User(last.id, last.text + text)
                else state.items += CliItem.User("u${System.nanoTime()}", text)
            }
            "agent_message_chunk" -> {
                val text = blockText(u.optJSONObject("content"))
                val last = state.items.lastOrNull()
                if (last is CliItem.Agent) last.text += text else state.items += CliItem.Agent(u.str("messageId") ?: "a${System.nanoTime()}", text)
            }
            "agent_thought_chunk" -> {
                val text = blockText(u.optJSONObject("content"))
                val last = state.items.lastOrNull()
                if (last is CliItem.Reasoning && !last.done) last.text += text else state.items += CliItem.Reasoning("r${System.nanoTime()}", text)
            }
            "tool_call", "tool_call_update" -> toolCall(u)
            "plan" -> {
                val steps = u.optJSONArray("entries").objects().map { PlanStep(it.optString("content"), it.optString("status")) }
                val id = "plan-$turn"
                (state.find(id) as? CliItem.Plan)?.let { it.steps = steps } ?: run { state.items += CliItem.Plan(id, steps) }
            }
            "available_commands_update" -> state.commands = u.optJSONArray("availableCommands").objects().map { it.optString("name") to it.optString("description") }
            "current_mode_update" -> state.mode = u.str("currentModeId")
            "config_option_update" -> u.optJSONArray("configOptions")?.let { applyConfig(it) }
            "usage_update" -> state.usage = state.usage.copy(tokensUsed = u.optLong("used"), contextWindow = u.optLong("size").takeIf { it > 0 },
                cost = u.optJSONObject("cost")?.let { c -> "%.2f %s".format(c.optDouble("amount"), c.optString("currency")) })
            "session_info_update" -> u.str("title")?.let { state.threadTitle = it }
            "notice" -> u.str("message")?.let { state.items += CliItem.Notice("n${System.nanoTime()}", it, u.optString("severity", "info")) }
        }
        // thinking ends when something else arrives
        (state.items.getOrNull(state.items.lastIndex - 1) as? CliItem.Reasoning)?.done = true
    }

    private fun toolCall(u: JSONObject) {
        val id = u.optString("toolCallId")
        val kind = u.str("kind")
        val content = u.optJSONArray("content").objects()
        val diffs = content.filter { it.optString("type") == "diff" }.map {
            FileChange(relative(it.optString("path")), if (it.isNull("oldText") || !it.has("oldText")) "add" else "update", acpDiffText(it.str("oldText"), it.optString("newText")))
        }
        val text = content.filter { it.optString("type") == "content" }.joinToString("\n") { blockText(it.optJSONObject("content")) }
        val status = u.str("status")
        val raw = u.optJSONObject("rawInput")
        val existing = state.find(id)
        when {
            existing is CliItem.Command -> {
                if (text.isNotBlank()) existing.output = text.takeLast(30_000)
                status?.let { existing.status = itemStatus(it) }
                raw?.optJSONObject("result")?.let { if (it.has("exitCode")) existing.exitCode = it.optInt("exitCode") }
            }
            existing is CliItem.FileEdit -> { if (diffs.isNotEmpty()) existing.changes = diffs; status?.let { existing.status = itemStatus(it) } }
            existing is CliItem.Tool -> {
                u.str("title")?.let { existing.title = it }
                if (text.isNotBlank()) existing.detail = text.take(4000)
                status?.let { existing.status = itemStatus(it) }
                if (diffs.isNotEmpty()) { // became an edit
                    val index = state.items.indexOf(existing)
                    state.items[index] = CliItem.FileEdit(id, diffs).also { e -> e.status = itemStatus(status) }
                }
            }
            existing == null && diffs.isNotEmpty() -> state.items += CliItem.FileEdit(id, diffs).also { it.status = itemStatus(status) }
            existing == null && kind == "execute" -> state.items += CliItem.Command(id, raw?.str("command") ?: u.optString("title")).also {
                if (text.isNotBlank()) it.output = text; it.status = itemStatus(status)
            }
            existing == null -> state.items += CliItem.Tool(id, u.optString("title", kind ?: "Tool"), kind ?: "other").also {
                if (text.isNotBlank()) it.detail = text.take(4000); it.status = itemStatus(status)
                u.optJSONArray("locations").objects().firstOrNull()?.str("path")?.let { path -> if (it.detail.isBlank()) it.detail = relative(path) }
            }
        }
    }

    // ---- requests from the agent -------------------------------------------------------------------------------------

    private suspend fun onRequest(method: String, p: JSONObject): Any? = when (method) {
        "session/request_permission" -> {
            val tool = p.optJSONObject("toolCall") ?: JSONObject()
            val options = p.optJSONArray("options").objects()
            val kind = tool.str("kind") ?: (state.find(tool.optString("toolCallId")) as? CliItem.Tool)?.kind
            val command = tool.optJSONObject("rawInput")?.str("command") ?: (state.find(tool.optString("toolCallId")) as? CliItem.Command)?.command
            val diffs = tool.optJSONArray("content").objects().filter { it.optString("type") == "diff" }
            val paths = diffs.map { it.optString("path") } + tool.optJSONArray("locations").objects().map { it.optString("path") }
            val allow = options.firstOrNull { it.optString("kind") == "allow_once" } ?: options.firstOrNull { it.optString("kind").startsWith("allow") }
            val auto = allow != null && when (state.permission) {
                CliPermission.ReadOnly -> kind in setOf("read", "search", "think", "fetch")
                CliPermission.Auto -> kind in setOf("read", "search", "think", "fetch") ||
                    (kind in setOf("edit", "delete", "move") && CliPolicy.autoEdit(state.permission, paths, host.guestCwd)) ||
                    (kind == "execute" && command != null && CommandSafety.isSafe(command))
                CliPermission.Autopilot -> !(kind == "execute" && command != null && CommandSafety.isDangerous(command))
            }
            val chosen = if (auto) allow!!.optString("optionId") else ask(CliApproval(
                "${config.name}: ${tool.optString("title", "wants to continue")}",
                paths.firstOrNull()?.let { relative(it) } ?: "",
                command = command,
                changes = diffs.map { FileChange(relative(it.optString("path")), "update", acpDiffText(it.str("oldText"), it.optString("newText"))) },
                dangerous = command != null && CommandSafety.isDangerous(command),
                options = options.map { o -> Triple(o.optString("optionId"), o.optString("name"), when (o.optString("kind")) {
                    "allow_always" -> "always"; "allow_once" -> "allow"; else -> "reject" }) }
                    .sortedBy { when (it.third) { "reject" -> 0; "always" -> 1; else -> 2 } },
                answer = {}))
            if (chosen == "cancelled") JSONObject().put("outcome", JSONObject().put("outcome", "cancelled"))
            else JSONObject().put("outcome", JSONObject().put("outcome", "selected").put("optionId", chosen))
        }
        else -> throw RpcError(-32601, "Codriod does not provide $method")
    }

    private suspend fun ask(template: CliApproval): String = withContext(ui) {
        val answer = CompletableDeferred<String>()
        pendingAnswer = answer
        state.approval = CliApproval(template.title, template.detail, template.command, template.changes, template.dangerous, template.options) {
            answer.complete(it)
        }
        try { answer.await() } finally { state.approval = null; pendingAnswer = null }
    }

    override fun stop() {
        pendingAnswer?.complete("cancelled")
        disconnect()
        state.connected = false; state.running = false
    }
}
