package dev.moavia.codriod.cli

import dev.moavia.codriod.agent.CommandSafety
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** What a CLI backend needs from the app: start the CLI in Ubuntu, map paths, open links. */
interface CliHost {
    /** Starts [command] inside Ubuntu in the project folder; stdin/stdout carry the protocol. */
    fun launch(command: List<String>): Process
    /** The project folder as the CLI sees it (e.g. /root/projects/test). */
    val guestCwd: String
    /** Makes a phone file (attachment) readable inside Ubuntu and returns its path there. */
    fun guestPathForImage(hostPath: String): String
    fun openUrl(url: String)
    fun log(line: String)
}

/** Codriod decides approvals because the CLIs' own sandboxes can't run under proot. */
object CliPolicy {
    fun autoCommand(permission: CliPermission, command: String): Boolean = when (permission) {
        CliPermission.ReadOnly -> false
        CliPermission.Auto -> CommandSafety.isSafe(command)
        CliPermission.Autopilot -> !CommandSafety.isDangerous(command)
    }

    fun autoEdit(permission: CliPermission, paths: List<String>, guestRoot: String): Boolean = when (permission) {
        CliPermission.ReadOnly -> false
        else -> paths.all { !it.startsWith("/") || it == guestRoot || it.startsWith("$guestRoot/") }
    }

    fun relative(path: String, guestRoot: String) = path.removePrefix("$guestRoot/").ifEmpty { path }
}

internal fun itemStatus(value: String?): ItemStatus = when (value) {
    "completed", "success" -> ItemStatus.Done
    "failed", "error" -> ItemStatus.Failed
    "declined", "rejected", "cancelled" -> ItemStatus.Declined
    else -> ItemStatus.Running
}

/**
 * OpenAI Codex CLI through `codex app-server` (the JSON-RPC protocol of Codex's own IDE extension): threads, turns,
 * streaming items, approvals, models with reasoning effort, ChatGPT/API-key sign-in and 5-hour/weekly limits.
 */
class CodexBackend(private val host: CliHost, private val scope: CoroutineScope, override val state: CliState = CliState(),
    private val ui: CoroutineDispatcher = Dispatchers.Main) : CliBackend {
    private var process: Process? = null
    private var rpc: JsonRpcPeer? = null
    private var turnId: String? = null
    private var loginId: String? = null
    private var pendingAnswer: CompletableDeferred<String>? = null

    override suspend fun start() {
        state.status = "Starting Codex…"
        state.features = CliFeatures(models = true, effort = true, modes = true, limits = true, threads = true, fork = true,
            archive = true, review = true, compact = true, images = true, steer = true, login = true, apiKeyLogin = true, deviceLogin = true)
        state.modes = CliPermission.entries.map { CliMode(it.name, it.label, it.hint) }
        state.mode = state.permission.name
        val p = withContext(Dispatchers.IO) { host.launch(listOf("codex", "app-server")) }
        process = p
        scope.launch(Dispatchers.IO) { runCatching { p.errorStream.bufferedReader().forEachLine { host.log("codex: $it") } } }
        val peer = JsonRpcPeer(p.inputStream, p.outputStream, scope, jsonrpcField = false, ::onNotification, ::onRequest, host::log)
        peer.onClosed = { scope.launch(ui) { state.connected = false; state.running = false; state.status = "Codex stopped" } }
        rpc = peer
        peer.start()
        val init = peer.requestObject("initialize", JSONObject()
            .put("clientInfo", JSONObject().put("name", "codriod").put("title", "Codriod").put("version", "1.0"))
            .put("capabilities", JSONObject().put("experimentalApi", false)), timeoutMs = 60_000)
        state.version = init.str("userAgent")
        peer.notify("initialized")
        state.connected = true
        state.status = "Ready"
        refreshAccount()
        loadModels()
        refreshUsage()
        refreshThreads()
    }

    private val peer get() = rpc ?: throw RpcError(-32000, "Codex is not running")

    private suspend fun refreshAccount() {
        val r = runCatching { peer.requestObject("account/read", JSONObject()) }.getOrNull() ?: return
        val account = r.optJSONObject("account")
        state.account = when (account?.optString("type")) {
            null -> if (r.optBoolean("requiresOpenaiAuth", true)) CliAccount(false) else CliAccount(true, "Signed in")
            "chatgpt" -> CliAccount(true, account.str("email") ?: "ChatGPT", account.str("planType"))
            "apiKey" -> CliAccount(true, "OpenAI API key")
            else -> CliAccount(true, account.optString("type"))
        }
    }

    private suspend fun loadModels() {
        val r = runCatching { peer.requestObject("model/list", JSONObject()) }.getOrNull() ?: return
        val models = r.optJSONArray("data").objects().filter { !it.optBoolean("hidden") }.map { m ->
            CliModel(m.optString("model", m.optString("id")), m.optString("displayName", m.optString("id")), m.optString("description"),
                m.optJSONArray("supportedReasoningEfforts").objects().map { it.optString("reasoningEffort") }.filter { it.isNotBlank() },
                m.str("defaultReasoningEffort"), m.optBoolean("isDefault"),
                m.optJSONArray("inputModalities")?.strings()?.contains("image") ?: true)
        }
        state.models = models
        val chosen = models.firstOrNull { it.id == state.model } ?: models.firstOrNull { it.isDefault } ?: models.firstOrNull()
        if (chosen != null) {
            state.model = chosen.id
            if (state.effort == null || state.effort !in chosen.efforts) state.effort = chosen.defaultEffort ?: chosen.efforts.lastOrNull()
        }
    }

    override suspend fun refreshUsage() {
        if (state.account?.signedIn != true) return
        val r = runCatching { peer.requestObject("account/rateLimits/read", JSONObject()) }.getOrNull() ?: return
        r.optJSONObject("rateLimits")?.let(::applyRateLimits)
    }

    private fun applyRateLimits(limits: JSONObject) {
        val primary = limits.optJSONObject("primary")
        val secondary = limits.optJSONObject("secondary")
        state.usage = state.usage.copy(
            primaryPercent = primary?.optInt("usedPercent"), primaryWindowMins = primary?.optLong("windowDurationMins")?.takeIf { it > 0 },
            primaryResetsAt = primary?.optLong("resetsAt")?.takeIf { it > 0 },
            secondaryPercent = secondary?.optInt("usedPercent"), secondaryWindowMins = secondary?.optLong("windowDurationMins")?.takeIf { it > 0 },
            secondaryResetsAt = secondary?.optLong("resetsAt")?.takeIf { it > 0 },
            planType = limits.str("planType") ?: state.usage.planType)
    }

    override suspend fun refreshThreads() {
        val r = runCatching { peer.requestObject("thread/list", JSONObject().put("cwd", host.guestCwd).put("limit", 40)) }.getOrNull() ?: return
        state.threads = r.optJSONArray("data").objects().map { t ->
            CliThread(t.optString("id"), t.str("name") ?: t.optString("preview").lineSequence().firstOrNull().orEmpty().take(60).ifBlank { "Untitled" },
                t.optLong("updatedAt") * (if (t.optLong("updatedAt") < 10_000_000_000L) 1000 else 1),
                t.optJSONObject("status")?.optString("type").orEmpty())
        }
    }

    private fun threadSettings() = JSONObject().put("cwd", host.guestCwd)
        // Codex's Linux sandbox needs kernel features proot doesn't have, so it runs open and Codriod approves every
        // command and edit that the "untrusted" policy sends us (see CliPolicy).
        .put("approvalPolicy", "untrusted").put("sandbox", "danger-full-access")
        .apply { state.model?.let { put("model", it) } }

    private suspend fun ensureThread(firstText: String) {
        if (state.threadId != null) return
        val r = peer.requestObject("thread/start", threadSettings())
        val thread = r.optJSONObject("thread") ?: throw RpcError(-32000, "Codex did not start a thread")
        state.threadId = thread.optString("id")
        state.threadTitle = firstText.lineSequence().firstOrNull().orEmpty().take(50).ifBlank { "New chat" }
    }

    private fun input(text: String, images: List<String>) = JSONArray().apply {
        if (text.isNotBlank()) put(JSONObject().put("type", "text").put("text", text))
        images.forEach { put(JSONObject().put("type", "localImage").put("path", host.guestPathForImage(it))) }
    }

    override suspend fun send(text: String, images: List<String>) {
        state.error = null
        if (state.running && turnId != null) { // steer the running turn
            state.items += CliItem.User("u${System.nanoTime()}", text, images)
            peer.requestObject("turn/steer", JSONObject().put("threadId", state.threadId).put("input", input(text, images)).put("expectedTurnId", turnId))
            return
        }
        ensureThread(text)
        state.items += CliItem.User("u${System.nanoTime()}", text, images)
        state.running = true
        val params = JSONObject().put("threadId", state.threadId).put("input", input(text, images))
            .put("approvalPolicy", "untrusted")
        state.model?.let { params.put("model", it) }
        state.effort?.let { params.put("effort", it) }
        try {
            val r = peer.requestObject("turn/start", params)
            r.optJSONObject("turn")?.str("id")?.let { turnId = it }
        } catch (error: Exception) { state.running = false; throw error }
    }

    override suspend fun interrupt() {
        val t = turnId ?: return
        runCatching { peer.requestObject("turn/interrupt", JSONObject().put("threadId", state.threadId).put("turnId", t)) }
    }

    override suspend fun newThread() {
        state.reset(); state.threadId = null; state.threadTitle = "New chat"; turnId = null
        state.usage = state.usage.copy(tokensUsed = null)
    }

    override suspend fun openThread(id: String) {
        val r = peer.requestObject("thread/resume", threadSettings().put("threadId", id))
        showThread(r.optJSONObject("thread"))
    }

    private fun showThread(thread: JSONObject?) {
        thread ?: return
        state.reset()
        state.threadId = thread.optString("id")
        state.threadTitle = thread.str("name") ?: thread.optString("preview").take(50).ifBlank { "Chat" }
        thread.optJSONArray("turns").objects().forEach { turn ->
            turn.optJSONArray("items").objects().forEach { upsert(it, history = true) }
        }
    }

    override suspend fun forkThread() {
        val id = state.threadId ?: return
        val r = peer.requestObject("thread/fork", JSONObject().put("threadId", id))
        showThread(r.optJSONObject("thread"))
        state.items += CliItem.Notice("n${System.nanoTime()}", "Forked: a copy of the chat you can take another way.")
        refreshThreads()
    }

    override suspend fun archiveThread(id: String) {
        peer.requestObject("thread/archive", JSONObject().put("threadId", id))
        if (id == state.threadId) newThread()
        refreshThreads()
    }

    override suspend fun compact() {
        val id = state.threadId ?: return
        peer.requestObject("thread/compact/start", JSONObject().put("threadId", id))
        state.items += CliItem.Notice("n${System.nanoTime()}", "Compacting the conversation to free context…")
    }

    override suspend fun review(target: String) {
        ensureThread("Code review")
        val t = when {
            target == "uncommitted" -> JSONObject().put("type", "uncommittedChanges")
            target.startsWith("branch:") -> JSONObject().put("type", "baseBranch").put("branch", target.removePrefix("branch:"))
            else -> JSONObject().put("type", "custom").put("instructions", target)
        }
        state.items += CliItem.User("u${System.nanoTime()}", "/review " + if (target == "uncommitted") "uncommitted changes" else target)
        state.running = true
        val r = peer.requestObject("review/start", JSONObject().put("threadId", state.threadId).put("target", t).put("delivery", "inline"))
        r.optJSONObject("turn")?.str("id")?.let { turnId = it }
    }

    override suspend fun setModel(id: String) {
        state.model = id
        state.models.firstOrNull { it.id == id }?.let { m -> if (state.effort !in m.efforts) state.effort = m.defaultEffort ?: m.efforts.lastOrNull() }
    }

    override suspend fun setEffort(effort: String) { state.effort = effort }
    override suspend fun setMode(id: String) { state.permission = CliPermission.valueOf(id); state.mode = id }

    override suspend fun login(kind: String, apiKey: String?) {
        val params = when (kind) {
            "apiKey" -> JSONObject().put("type", "apiKey").put("apiKey", apiKey.orEmpty().trim())
            "device" -> JSONObject().put("type", "chatgptDeviceCode")
            else -> JSONObject().put("type", "chatgpt")
        }
        state.login = CliLogin(message = "Starting sign-in…")
        try {
            val r = peer.requestObject("account/login/start", params)
            loginId = r.str("loginId")
            when (r.optString("type")) {
                "chatgpt" -> {
                    val url = r.optString("authUrl")
                    state.login = CliLogin(url = url, message = "Sign in on OpenAI's page in your browser, then come back.")
                    host.openUrl(url)
                }
                "chatgptDeviceCode" -> state.login = CliLogin(url = r.optString("verificationUrl"), userCode = r.optString("userCode"),
                    message = "Open the page and enter this code.")
                else -> { state.login = null; refreshAccount(); loadModels(); refreshUsage() }
            }
        } catch (error: RpcError) {
            state.login = CliLogin(waiting = false,
                error = when {
                    error.message.orEmpty().contains("timeout", ignoreCase = true) -> "Sign-in timed out. Try again."
                    error.message.orEmpty().contains("not running", ignoreCase = true) -> "Codex is not running. Tap Restart."
                    else -> error.message ?: "Sign-in failed. Try again."
                })
        }
    }

    override suspend fun logout() {
        runCatching { peer.request("account/logout", null) }
        refreshAccount()
    }

    // ---- notifications -------------------------------------------------------------------------------------------

    private suspend fun onNotification(method: String, p: JSONObject) = withContext(ui) {
        when (method) {
            "turn/started" -> { state.running = true; turnId = p.optJSONObject("turn")?.str("id") ?: turnId }
            "turn/completed" -> {
                state.running = false
                val turn = p.optJSONObject("turn")
                if (turn?.optString("status") == "failed") turn.optJSONObject("error")?.str("message")?.let { state.error = it }
                pendingAnswer?.complete("cancel")
                state.approval = null
                scope.launch { refreshUsage(); refreshThreads() }
            }
            "item/started", "item/completed" -> p.optJSONObject("item")?.let { upsert(it, completed = method == "item/completed") }
            "item/agentMessage/delta" -> agent(p.optString("itemId")).let { it.text += p.optString("delta") }
            "item/reasoning/summaryTextDelta", "item/reasoning/textDelta" -> reasoning(p.optString("itemId")).let { it.text += p.optString("delta") }
            "item/reasoning/summaryPartAdded" -> reasoning(p.optString("itemId")).let { if (it.text.isNotEmpty()) it.text += "\n\n" }
            "item/commandExecution/outputDelta" -> (state.find(p.optString("itemId")) as? CliItem.Command)?.let {
                it.output = (it.output + p.optString("delta")).takeLast(30_000)
            }
            "turn/plan/updated" -> {
                val id = "plan-" + p.optString("turnId")
                val steps = p.optJSONArray("plan").objects().map { PlanStep(it.optString("step"), planStatus(it.optString("status"))) }
                val existing = state.find(id) as? CliItem.Plan
                if (existing != null) { existing.steps = steps; existing.explanation = p.str("explanation") }
                else state.items += CliItem.Plan(id, steps, p.str("explanation"))
            }
            "thread/tokenUsage/updated" -> p.optJSONObject("tokenUsage")?.let { u ->
                val last = u.optJSONObject("last")
                state.usage = state.usage.copy(tokensUsed = last?.optLong("totalTokens") ?: u.optJSONObject("total")?.optLong("totalTokens"),
                    contextWindow = u.optLong("modelContextWindow").takeIf { it > 0 } ?: state.usage.contextWindow)
            }
            "account/rateLimits/updated" -> p.optJSONObject("rateLimits")?.let(::applyRateLimits)
            "account/updated" -> scope.launch { refreshAccount() }
            "account/login/completed" -> {
                if (p.optBoolean("success")) {
                    state.login = null
                    state.error = null
                    scope.launch { refreshAccount(); loadModels(); refreshUsage(); refreshThreads() }
                } else {
                    val errMsg = p.str("error") ?: p.str("message") ?: "Sign-in failed. Try again."
                    state.login = (state.login ?: CliLogin()).copy(waiting = false, error = errMsg)
                }
            }
            "thread/name/updated" -> if (p.optString("threadId") == state.threadId) p.str("threadName")?.let { state.threadTitle = it }
            "thread/compacted" -> state.items += CliItem.Notice("n${System.nanoTime()}", "Context compacted: older parts were summarized.")
            "model/rerouted" -> state.items += CliItem.Notice("n${System.nanoTime()}", "Model switched: ${p.optString("toModel", p.toString().take(120))}")
            "error" -> p.optJSONObject("error")?.str("message")?.let { msg ->
                if (p.optBoolean("willRetry")) state.status = "Retrying… $msg" else state.error = msg
            }
            "warning", "configWarning", "deprecationNotice" -> p.str("message")?.let { state.items += CliItem.Notice("n${System.nanoTime()}", it, "warning") }
        }
    }

    private fun planStatus(s: String) = when (s) { "inProgress", "in_progress" -> "in_progress"; "completed" -> "completed"; else -> "pending" }

    private fun agent(id: String) = (state.find(id) as? CliItem.Agent) ?: CliItem.Agent(id).also { state.items += it }
    private fun reasoning(id: String) = (state.find(id) as? CliItem.Reasoning) ?: CliItem.Reasoning(id).also { state.items += it }

    /** Adds or updates the panel entry for one Codex thread item. */
    internal fun upsert(item: JSONObject, completed: Boolean = false, history: Boolean = false) {
        val id = item.optString("id")
        when (item.optString("type")) {
            "userMessage" -> if (history) {
                val content = item.optJSONArray("content").objects()
                state.items += CliItem.User(id, content.filter { it.optString("type") == "text" }.joinToString("\n") { it.optString("text") },
                    content.filter { it.optString("type") == "localImage" }.map { it.optString("path") })
            }
            "agentMessage" -> { val a = agent(id); if (item.has("text") && (completed || history || a.text.isEmpty())) a.text = item.optString("text").ifEmpty { a.text } }
            "reasoning" -> {
                val r = reasoning(id)
                val summary = item.optJSONArray("summary").strings().joinToString("\n\n")
                val content = item.optJSONArray("content").strings().joinToString("\n\n")
                if (summary.isNotBlank() || content.isNotBlank()) r.text = summary.ifBlank { content }
                if (completed || history) r.done = true
            }
            "commandExecution" -> {
                val c = (state.find(id) as? CliItem.Command) ?: CliItem.Command(id, item.optString("command"),
                    CliPolicy.relative(item.optString("cwd"), host.guestCwd)).also { state.items += it }
                c.status = itemStatus(item.optString("status"))
                item.str("aggregatedOutput")?.let { if (it.length >= c.output.length) c.output = it.takeLast(30_000) }
                if (item.has("exitCode") && !item.isNull("exitCode")) c.exitCode = item.optInt("exitCode")
                if (item.has("durationMs") && !item.isNull("durationMs")) c.durationMs = item.optLong("durationMs")
            }
            "fileChange" -> {
                val changes = item.optJSONArray("changes").objects().map {
                    FileChange(CliPolicy.relative(it.optString("path"), host.guestCwd), it.optJSONObject("kind")?.optString("type") ?: "update", it.optString("diff"))
                }
                val f = (state.find(id) as? CliItem.FileEdit) ?: CliItem.FileEdit(id, changes).also { state.items += it }
                if (changes.isNotEmpty()) f.changes = changes
                f.status = itemStatus(item.optString("status"))
            }
            "mcpToolCall" -> tool(id, "${item.optString("server")} · ${item.optString("tool")}", "mcp", item).also { t ->
                item.optJSONObject("error")?.str("message")?.let { t.detail = it }
            }
            "webSearch" -> tool(id, "Searched the web" + (item.str("query")?.let { ": $it" } ?: ""), "fetch", item)
            "dynamicToolCall" -> tool(id, item.optString("tool"), "other", item)
            "plan" -> tool(id, "Plan", "think", item).also { it.detail = item.optString("text") }
            "enteredReviewMode" -> state.items += CliItem.Notice(id, "Reviewing" + (item.str("review")?.let { ": $it" } ?: "…"))
            "exitedReviewMode" -> item.str("review")?.let { agent(id).text = it }
            "contextCompaction" -> {}
            "imageView" -> tool(id, "Looked at an image" + (item.str("path")?.let { ": ${CliPolicy.relative(it, host.guestCwd)}" } ?: ""), "read", item)
            else -> {}
        }
    }

    private fun tool(id: String, title: String, kind: String, item: JSONObject): CliItem.Tool {
        val t = (state.find(id) as? CliItem.Tool) ?: CliItem.Tool(id, title, kind).also { state.items += it }
        t.title = title
        t.status = itemStatus(item.str("status") ?: "completed")
        return t
    }

    // ---- approvals ------------------------------------------------------------------------------------------------

    private suspend fun onRequest(method: String, p: JSONObject): Any? = when (method) {
        "item/commandExecution/requestApproval" -> {
            val command = p.str("command") ?: "(command)"
            val available = p.optJSONArray("availableDecisions")?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.opt(i) as? String }
            }
            val decision = if (CliPolicy.autoCommand(state.permission, command)) "accept"
                else ask(CliApproval("Codex wants to run a command", p.str("reason") ?: "", command = command,
                    dangerous = CommandSafety.isDangerous(command),
                    options = listOfNotNull(Triple("decline", "Deny", "reject"),
                        Triple("acceptForSession", "Always", "always").takeIf { available == null || "acceptForSession" in available },
                        Triple("accept", "Run", "allow")), answer = {}))
            JSONObject().put("decision", decision)
        }
        "item/fileChange/requestApproval" -> {
            val edit = state.find(p.optString("itemId")) as? CliItem.FileEdit
            val paths = edit?.changes?.map { it.path } ?: emptyList()
            val decision = if (edit != null && CliPolicy.autoEdit(state.permission, paths, host.guestCwd)) "accept"
                else ask(CliApproval("Codex wants to change ${paths.size.coerceAtLeast(1)} file" + if (paths.size == 1) "" else "s",
                    p.str("reason") ?: paths.joinToString(), changes = edit?.changes ?: emptyList(),
                    options = listOf(Triple("decline", "Reject", "reject"), Triple("acceptForSession", "Always", "always"), Triple("accept", "Apply", "allow")),
                    answer = {}))
            JSONObject().put("decision", decision)
        }
        else -> throw RpcError(-32601, "Codriod does not support $method yet")
    }

    /** Shows an approval card and waits for the user's choice. */
    private suspend fun ask(template: CliApproval): String = withContext(ui) {
        val answer = CompletableDeferred<String>()
        pendingAnswer = answer
        state.approval = CliApproval(template.title, template.detail, template.command, template.changes, template.dangerous, template.options) {
            answer.complete(it)
        }
        try { answer.await() } finally { state.approval = null; pendingAnswer = null }
    }

    override fun stop() {
        pendingAnswer?.complete("cancel")
        rpc?.close(); rpc = null
        process?.destroy(); process = null
        state.connected = false; state.running = false
    }
}
