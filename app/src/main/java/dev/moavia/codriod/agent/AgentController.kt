package dev.moavia.codriod.agent

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import dev.moavia.codriod.workspace.ProjectStore
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Something the user can add to a message with @. */
data class ContextOption(val id: String, val icon: String, val label: String, val subtitle: String, val load: suspend () -> String)

/** Context attached to the next message. */
data class ContextItem(val label: String, val text: String)

/** What the agent needs from the workspace. */
interface AgentHost {
    /** id to display name of the open project, or null on the Home screen. */
    fun currentProject(): Pair<String, String>?
    fun agentWorkspace(): AgentWorkspace?
    /** Path and text of the file open in the editor. */
    fun openFile(): Pair<String, String>?
    suspend fun beforeAgentRun()
    suspend fun afterAgentChangedFiles()
    val gitBridge: GitAccountBridge
    /** Browser tools, when the browser is available. */
    fun browserTools(vision: () -> Boolean, onScreenshot: (String) -> Unit): AgentToolset? = null
    /** Choices for the @ menu: open file, folders, browser page, terminal output, Git changes. */
    fun contextOptions(): List<ContextOption> = emptyList()
    /** Screenshot of Codriod's browser as an image file, or null. */
    suspend fun browserScreenshot(): String? = null
    /** Shows the browser on the agent's tab ("Take over"). */
    fun showAgentBrowser() {}
    /** Name of a project, for the history list. */
    fun projectName(id: String): String? = null
}

/** The project folder as seen by the agent; every path goes through [ProjectStore]'s safety checks. */
class ProjectAgentWorkspace(private val store: ProjectStore, private val projectId: String,
    private val shell: ShellRunner = ShellRunner()) : AgentWorkspace {
    override fun files() = store.entries(projectId).map { if (it.directory) it.path + "/" else it.path }
    override fun read(path: String) = store.read(projectId, path)
    override fun exists(path: String) = store.entries(projectId).any { it.path == path }
    override fun write(path: String, text: String) {
        val folders = store.entries(projectId).filter { it.directory }.map { it.path }.toSet()
        val parts = path.split('/').dropLast(1)
        for (i in parts.indices) {
            val folder = parts.subList(0, i + 1).joinToString("/")
            if (folder !in folders) store.createEntry(projectId, folder, true)
        }
        store.write(projectId, path, text)
    }
    override fun delete(path: String) = store.delete(projectId, path)
    override fun rename(from: String, to: String) {
        val folders = store.entries(projectId).filter { it.directory }.map { it.path }.toSet()
        val parts = to.split('/').dropLast(1)
        for (i in parts.indices) {
            val folder = parts.subList(0, i + 1).joinToString("/")
            if (folder !in folders) store.createEntry(projectId, folder, true)
        }
        store.rename(projectId, from, to)
    }
    override fun file(path: String): File? = runCatching { store.resolve(projectId, path) }.getOrNull()
    override fun search(query: String) = store.search(projectId, query).map { SearchMatch(it.path, it.line, it.text) }
    override fun run(command: String, timeoutSeconds: Int) = shell.run(store.root(projectId), command, timeoutSeconds)
}

class PendingApproval(val request: ApprovalRequest, internal val answer: CompletableDeferred<Decision>)

data class ChatMeta(val id: String, val projectId: String, val title: String, val updated: Long, val mode: String,
    val filesChanged: Int, val status: String)

data class ModelRef(val provider: String, val model: String) {
    fun encode() = "$provider/$model"
    companion object { fun decode(s: String?) = s?.indexOf('/')?.takeIf { it > 0 }?.let { ModelRef(s.substring(0, it), s.substring(it + 1)) } }
}

/**
 * Settings, keys and chats for Codriod Agent. API keys are the user's own and are kept in this app's
 * private storage on the phone (plain, as chosen for this app; excluded from backups); they are sent only to
 * that provider.
 */
class AgentController(context: Context, private val scope: CoroutineScope, private val host: AgentHost) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences("agent", Context.MODE_PRIVATE)
    private val client = LlmClient()
    internal val llm get() = client
    /** Voice chat: speech recognition, spoken replies and the hands-free loop. */
    private val voiceHolder = lazy { VoiceController(appContext, scope, this) }
    val voice: VoiceController by voiceHolder
    fun releaseVoice() { if (voiceHolder.isInitialized()) voice.release() }
    private val root = File(appContext.filesDir, "agent")
    private val chatsRoot = File(root, "chats")
    private val imagesDir = File(root, "images")
    private val main = Handler(Looper.getMainLooper())

    // ---- chat state ----------------------------------------------------------------------------------
    val messages = mutableStateListOf<ChatMessage>()
    var chatId by mutableStateOf<String?>(null)
        private set
    var chatTitle by mutableStateOf("New chat")
        private set
    var running by mutableStateOf(false)
        private set
    var pending by mutableStateOf<PendingApproval?>(null)
        private set
    /** Text of the answer while it streams in. */
    var streaming by mutableStateOf("")
        private set
    var lastUsage by mutableStateOf<AgentEngine.Usage?>(null)
        private set
    var totalTokens by mutableIntStateOf(0)
        private set
    var planTitle by mutableStateOf("")
        private set
    val plan = mutableStateListOf<PlanStep>()
    /** True after Plan mode produced a plan that the user has not approved yet. */
    var planAwaitingApproval by mutableStateOf(false)
        private set
    /** Files changed by the last run: path → content before (null = the file was new). */
    val lastChanges = mutableStateMapOf<String, String?>()
    /** Latest browser screenshot taken by the agent (live browser card). */
    var liveShot by mutableStateOf<String?>(null)
        private set
    var usingBrowser by mutableStateOf(false)
        private set
    val attachedImages = mutableStateListOf<String>()
    val attachedContext = mutableStateListOf<ContextItem>()
    val chats = mutableStateListOf<ChatMeta>()

    // ---- providers -----------------------------------------------------------------------------------
    /** Bumped whenever keys or model lists change so the UI re-reads them. */
    var revision by mutableIntStateOf(0)
        private set
    val fetching = mutableStateMapOf<String, Boolean>()
    val fetchErrors = mutableStateMapOf<String, String>()

    var providerId by mutableStateOf(prefs.getString("provider", null))
        private set
    var modelId by mutableStateOf(prefs.getString("model", null))
        private set
    var fastModel by mutableStateOf(ModelRef.decode(prefs.getString("fast_model", null)))
        private set

    // ---- settings ------------------------------------------------------------------------------------
    var mode by mutableStateOf(runCatching { AgentMode.valueOf(prefs.getString("mode", "Agent")!!) }.getOrDefault(AgentMode.Agent))
        private set
    var defaultMode by mutableStateOf(runCatching { AgentMode.valueOf(prefs.getString("default_mode", "Agent")!!) }.getOrDefault(AgentMode.Agent))
        private set
    var autoApplyEdits by mutableStateOf(prefs.getBoolean("auto_apply", false))
        private set
    var commandPolicy by mutableStateOf(runCatching { CommandPolicy.valueOf(prefs.getString("command_policy", "Ask")!!) }.getOrDefault(CommandPolicy.Ask))
        private set
    var browserEnabled by mutableStateOf(prefs.getBoolean("browser_enabled", true))
        private set
    var internetEnabled by mutableStateOf(prefs.getBoolean("internet_enabled", true))
        private set
    var streamingEnabled by mutableStateOf(prefs.getBoolean("streaming", true))
        private set
    var includeOpenFile by mutableStateOf(prefs.getBoolean("include_open_file", true))
        private set
    var instructions by mutableStateOf(prefs.getString("instructions", "") ?: "")
        private set
    var maxSteps by mutableIntStateOf(prefs.getInt("max_steps", 40))
        private set
    var onboarded by mutableStateOf(prefs.getBoolean("onboarded", false))
        private set

    private var boundProject: String? = null
    private var job: Job? = null

    val provider get() = Providers.byId(providerId)
    val ready get() = provider != null && !modelId.isNullOrBlank()
    val visionModel get() = modelId?.let { canSee(providerId, it) } == true

    init { imagesDir.mkdirs() }

    // ---- providers & models ------------------------------------------------------------------------------

    fun key(id: String): String = prefs.getString("key_$id", "") ?: ""
    fun hasKey(spec: ProviderSpec) = !spec.needsKey || key(spec.id).isNotBlank()
    fun baseUrl(spec: ProviderSpec): String = prefs.getString("url_${spec.id}", null)?.takeIf { it.isNotBlank() } ?: spec.baseUrl

    /** Providers the user set up: a key (or a local server) and a model list or known models. */
    fun configured(): List<ProviderSpec> = Providers.all.filter { spec ->
        if (spec.needsKey) key(spec.id).isNotBlank() else modelsFetched(spec) || (spec.id == Providers.CUSTOM && key(spec.id).isNotBlank())
    }

    fun saveProvider(spec: ProviderSpec, apiKey: String, url: String?) {
        prefs.edit().putString("key_${spec.id}", apiKey.trim()).apply {
            if (spec.editableUrl) putString("url_${spec.id}", url?.trim().orEmpty())
        }.apply()
        revision++
    }

    private val modelCache = HashMap<String, List<ModelInfo>>()

    fun models(spec: ProviderSpec): List<ModelInfo> = modelCache.getOrPut(spec.id) { loadModels(spec) }

    private fun loadModels(spec: ProviderSpec): List<ModelInfo> = prefs.getString("models_${spec.id}", null)?.let { json ->
        runCatching {
            val array = JSONArray(json)
            (0 until array.length()).map { i -> array.getJSONObject(i).let {
                ModelInfo(it.getString("id"), it.optBoolean("free"), it.optInt("ctx"),
                    if (it.has("vis")) it.optBoolean("vis") else null) } }
        }.getOrNull()
    } ?: spec.fallbackModels.map { ModelInfo(it) }

    fun modelsFetched(spec: ProviderSpec) = prefs.contains("models_${spec.id}")

    /** Loads the provider's live model list. [done] gets the count or an error, on the main thread. */
    fun fetchModels(spec: ProviderSpec, done: (Int?, String?) -> Unit = { _, _ -> }) {
        if (fetching[spec.id] == true) return
        if (!hasKey(spec)) { fetchErrors[spec.id] = "Add your API key first"; done(null, "Add your API key first"); return }
        fetching[spec.id] = true
        fetchErrors.remove(spec.id)
        scope.launch {
            try {
                val list = client.listModels(spec, key(spec.id), baseUrl(spec))
                val json = JSONArray().apply { list.forEach { put(JSONObject().put("id", it.id).put("free", it.free).put("ctx", it.contextLength)
                    .apply { it.vision?.let { v -> put("vis", v) } }) } }
                prefs.edit().putString("models_${spec.id}", json.toString()).apply()
                modelCache.remove(spec.id)
                revision++
                done(list.size, null)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { val m = error.message ?: "Could not load models"; fetchErrors[spec.id] = m; done(null, m) }
            finally { fetching[spec.id] = false }
        }
    }

    // ---- images ---------------------------------------------------------------------------------------------

    private fun visionOverrides(): JSONObject = runCatching { JSONObject(prefs.getString("vision_overrides", "{}")!!) }.getOrDefault(JSONObject())

    /** The user's choice for a model, if they set one: true = sees images, false = text only. */
    fun visionOverride(provider: String?, model: String): Boolean? =
        visionOverrides().let { if (it.has("$provider/$model")) it.optBoolean("$provider/$model") else null }

    /** Whether the model takes images: the user's choice, then the provider's model list, then a guess from the name. */
    fun canSee(provider: String?, model: String): Boolean {
        visionOverride(provider, model)?.let { return it }
        val spec = Providers.byId(provider)
        val info = spec?.let { s -> models(s).firstOrNull { it.id == model } }
        return info?.vision ?: ModelCaps.vision(model)
    }

    /** Marks a model as able (or not able) to see images; null goes back to automatic. */
    fun setVisionOverride(provider: String, model: String, value: Boolean?) {
        val json = visionOverrides()
        if (value == null) json.remove("$provider/$model") else json.put("$provider/$model", value)
        prefs.edit().putString("vision_overrides", json.toString()).apply()
        revision++
    }

    /** Configured models that can see images, for "switch to a vision model". */
    fun visionModels(): List<Pair<ProviderSpec, String>> = configured().flatMap { spec ->
        models(spec).filter { canSee(spec.id, it.id) }.map { spec to it.id }
    }

    fun select(spec: ProviderSpec, model: String) {
        providerId = spec.id; modelId = model.trim()
        val recent = (listOf(ModelRef(spec.id, modelId!!).encode()) + recentModels().map { it.encode() }).distinct().take(6)
        prefs.edit().putString("provider", spec.id).putString("model", modelId).putString("recent_models", JSONArray(recent).toString()).apply()
        revision++
    }

    fun recentModels(): List<ModelRef> = runCatching {
        val a = JSONArray(prefs.getString("recent_models", "[]")); (0 until a.length()).mapNotNull { ModelRef.decode(a.getString(it)) }
    }.getOrDefault(emptyList())

    fun updateFastModel(ref: ModelRef?) { fastModel = ref; prefs.edit().putString("fast_model", ref?.encode()).apply() }

    fun finishOnboarding() { onboarded = true; prefs.edit().putBoolean("onboarded", true).apply() }

    // ---- settings --------------------------------------------------------------------------------------

    fun updateMode(value: AgentMode) { mode = value; prefs.edit().putString("mode", value.name).apply(); if (value != AgentMode.Plan) planAwaitingApproval = false }
    fun updateDefaultMode(value: AgentMode) { defaultMode = value; prefs.edit().putString("default_mode", value.name).apply() }
    fun updateAutoApply(value: Boolean) { autoApplyEdits = value; prefs.edit().putBoolean("auto_apply", value).apply() }
    fun updateCommandPolicy(value: CommandPolicy) { commandPolicy = value; prefs.edit().putString("command_policy", value.name).apply() }
    fun updateBrowserEnabled(value: Boolean) { browserEnabled = value; prefs.edit().putBoolean("browser_enabled", value).apply() }
    fun updateInternetEnabled(value: Boolean) { internetEnabled = value; prefs.edit().putBoolean("internet_enabled", value).apply() }
    fun updateStreaming(value: Boolean) { streamingEnabled = value; prefs.edit().putBoolean("streaming", value).apply() }
    fun updateIncludeOpenFile(value: Boolean) { includeOpenFile = value; prefs.edit().putBoolean("include_open_file", value).apply() }
    fun updateInstructions(value: String) { instructions = value; prefs.edit().putString("instructions", value).apply() }
    fun updateMaxSteps(value: Int) { maxSteps = value.coerceIn(5, 100); prefs.edit().putInt("max_steps", maxSteps).apply() }

    private fun commandsAlways(project: String) = prefs.getBoolean("cmd_always_$project", false)
    fun commandsAlwaysAllowed(): Boolean = host.currentProject()?.first?.let(::commandsAlways) ?: false
    fun revokeCommandPermission() { host.currentProject()?.first?.let { prefs.edit().remove("cmd_always_$it").apply() }; revision++ }

    /** Approvals that the current mode and settings make automatic. Dangerous commands and risky actions always ask. */
    fun autoDecision(request: ApprovalRequest, projectId: String?): Boolean = when (request) {
        is ApprovalRequest.Edit -> autoApplyEdits || mode == AgentMode.Autopilot
        is ApprovalRequest.Delete -> mode == AgentMode.Autopilot
        is ApprovalRequest.Command -> !request.dangerous && (mode == AgentMode.Autopilot || commandPolicy == CommandPolicy.Always ||
            (projectId != null && commandsAlways(projectId)) || (commandPolicy == CommandPolicy.Safe && request.safe))
        is ApprovalRequest.Action -> !request.risky && mode == AgentMode.Autopilot
    }

    // ---- chats -----------------------------------------------------------------------------------------

    private fun projectDir(projectId: String) = File(chatsRoot, projectId)

    /** Loads the most recent chat of the open project. */
    fun bind(projectId: String?) {
        if (projectId == boundProject) return
        stop()
        boundProject = projectId
        resetChat()
        if (projectId == null) { chats.clear(); return }
        scope.launch {
            val metas = withContext(Dispatchers.IO) { migrate(projectId); loadMetas() }
            if (boundProject != projectId) return@launch
            chats.clear(); chats.addAll(metas)
            metas.firstOrNull { it.projectId == projectId }?.let { openChat(it) }
        }
    }

    private fun resetChat() {
        messages.clear(); chatId = null; chatTitle = "New chat"; streaming = ""; lastUsage = null; totalTokens = 0
        plan.clear(); planTitle = ""; planAwaitingApproval = false; lastChanges.clear(); liveShot = null; usingBrowser = false
        attachedImages.clear(); attachedContext.clear()
        mode = defaultMode
    }

    fun newChat() { stop(); resetChat() }

    fun openChat(meta: ChatMeta) {
        if (meta.projectId != boundProject) return
        stop(); resetChat()
        chatId = meta.id; chatTitle = meta.title
        mode = runCatching { AgentMode.valueOf(meta.mode) }.getOrDefault(defaultMode)
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { ChatHistoryJson.decode(File(projectDir(meta.projectId), "${meta.id}.json").readText()) }.getOrDefault(emptyList())
            }
            if (chatId == meta.id) {
                messages.clear(); messages.addAll(loaded)
                loaded.lastOrNull { it.toolName == "update_plan" }?.detail?.let(PlanJson::decode)?.let { (t, s) -> planTitle = t; plan.clear(); plan.addAll(s) }
            }
        }
    }

    fun deleteChat(meta: ChatMeta) {
        File(projectDir(meta.projectId), "${meta.id}.json").delete()
        chats.remove(meta)
        saveMetas(meta.projectId)
        if (chatId == meta.id) newChat()
    }

    fun projectName(id: String) = host.projectName(id) ?: "Project"

    private fun loadMetas(): List<ChatMeta> = chatsRoot.listFiles().orEmpty().filter { it.isDirectory }.flatMap { dir ->
        runCatching {
            val a = JSONArray(File(dir, "index.json").readText())
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                ChatMeta(it.getString("id"), dir.name, it.optString("title"), it.optLong("updated"), it.optString("mode", "Agent"),
                    it.optInt("files"), it.optString("status"))
            }
        }.getOrDefault(emptyList())
    }.sortedByDescending { it.updated }

    private fun saveMetas(projectId: String) {
        val list = chats.filter { it.projectId == projectId }
        val dir = projectDir(projectId).apply { mkdirs() }
        File(dir, "index.json").writeText(JSONArray().apply {
            list.forEach { put(JSONObject().put("id", it.id).put("title", it.title).put("updated", it.updated).put("mode", it.mode)
                .put("files", it.filesChanged).put("status", it.status)) }
        }.toString())
    }

    /** One-chat-per-project files from the first version become a chat in the list. */
    private fun migrate(projectId: String) {
        val old = File(chatsRoot, "$projectId.json")
        if (!old.isFile) return
        val id = UUID.randomUUID().toString()
        val dir = projectDir(projectId).apply { mkdirs() }
        old.copyTo(File(dir, "$id.json"), overwrite = true)
        val existing = runCatching { JSONArray(File(dir, "index.json").readText()) }.getOrDefault(JSONArray())
        existing.put(JSONObject().put("id", id).put("title", "Earlier chat").put("updated", old.lastModified()).put("mode", "Agent").put("files", 0).put("status", ""))
        File(dir, "index.json").writeText(existing.toString())
        old.delete()
    }

    private fun persist(status: String = "") {
        val project = boundProject ?: return
        val id = chatId ?: return
        val snapshot = messages.toList().takeLast(400)
        val files = lastChanges.size
        val meta = ChatMeta(id, project, chatTitle, System.currentTimeMillis(), mode.name,
            maxOf(files, chats.firstOrNull { it.id == id }?.filesChanged ?: 0), status.ifEmpty { chats.firstOrNull { it.id == id }?.status.orEmpty() })
        val index = chats.indexOfFirst { it.id == id }
        if (index >= 0) chats.removeAt(index)
        chats.add(0, meta)
        scope.launch(Dispatchers.IO) {
            runCatching {
                projectDir(project).mkdirs()
                File(projectDir(project), "$id.json").writeText(ChatHistoryJson.encode(snapshot))
                saveMetas(project)
            }
        }
    }

    // ---- attachments -----------------------------------------------------------------------------------

    /** Copies an image (downscaled to 1600 px, JPEG) into the agent's folder and attaches it. */
    fun attachImage(uri: Uri) = scope.launch {
        val path = withContext(Dispatchers.IO) {
            runCatching {
                val bitmap = appContext.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return@runCatching null
                saveImage(bitmap)
            }.getOrNull()
        }
        if (path != null) attachedImages += path
    }

    fun attachBitmap(bitmap: Bitmap) = scope.launch { withContext(Dispatchers.IO) { saveImage(bitmap) }.let { attachedImages += it } }

    fun attachBrowserScreenshot() = scope.launch { host.browserScreenshot()?.let { attachedImages += it } }

    fun attachFile(uri: Uri, name: String) = scope.launch {
        val text = withContext(Dispatchers.IO) {
            runCatching { appContext.contentResolver.openInputStream(uri)?.use { it.readBytes().let { b -> if (b.size > 200_000) b.copyOf(200_000) else b } } }
                .getOrNull()?.toString(Charsets.UTF_8)
        }
        if (text != null) attachedContext += ContextItem("📄 $name", "File $name:\n```\n$text\n```")
    }

    fun attachContext(option: ContextOption) = scope.launch {
        val text = runCatching { option.load() }.getOrElse { "Could not load ${option.label}: ${it.message}" }
        attachedContext.removeAll { it.label == option.icon + " " + option.label }
        attachedContext += ContextItem(option.icon + " " + option.label, text)
    }

    fun contextOptions() = host.contextOptions()

    private fun saveImage(bitmap: Bitmap): String {
        val scale = minOf(1f, 1600f / maxOf(bitmap.width, bitmap.height))
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
        val file = File(imagesDir, "img-${System.currentTimeMillis()}-${(Math.random() * 1000).toInt()}.jpg")
        file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return file.absolutePath
    }

    // ---- running -----------------------------------------------------------------------------------------

    fun stop() {
        job?.cancel()
        job = null
        pending?.answer?.cancel()
        pending = null
    }

    fun answer(decision: Decision) { pending?.answer?.complete(decision) }

    fun clear() { newChat() }

    /** Approves the plan from Plan mode and starts building it. */
    fun approvePlan(buildMode: AgentMode = if (defaultMode.canEdit) defaultMode else AgentMode.Agent) {
        planAwaitingApproval = false
        updateMode(buildMode)
        send("The plan is approved. Implement it now, step by step. Keep update_plan current as you go.")
    }

    /** Removes the last error and runs the last request again. */
    fun retry() {
        if (running) return
        while (messages.isNotEmpty() && messages.last().role == Role.Error) messages.removeAt(messages.lastIndex)
        if (messages.none { it.role == Role.User }) return
        start()
    }

    /** Undoes every file change of the last run. */
    fun undoLastRun() = scope.launch {
        val changes = lastChanges.toMap()
        val workspace = host.agentWorkspace() ?: return@launch
        withContext(Dispatchers.IO) {
            changes.forEach { (path, original) ->
                runCatching { if (original == null) { if (workspace.exists(path)) workspace.delete(path) } else workspace.write(path, original) }
            }
        }
        lastChanges.clear()
        messages += ChatMessage(Role.User, "I undid your last changes (${changes.size} files restored to how they were before).",
            detail = "UNDO")
        host.afterAgentChangedFiles()
        persist()
    }

    /** [voice] = the message was spoken; the answer is read aloud, so the model is asked to answer briefly in plain speech. */
    fun send(text: String, voice: Boolean = false) {
        val prompt = text.trim()
        if ((prompt.isEmpty() && attachedImages.isEmpty()) || running) return
        if (host.currentProject() == null) { messages += ChatMessage(Role.Error, "Open a project first."); return }
        if (!ready) { messages += ChatMessage(Role.Error, "Choose a model first (tap the model name at the top)."); return }
        val spec = provider!!
        if (!hasKey(spec)) { messages += ChatMessage(Role.Error, "Add your ${spec.name} API key in Agent settings → Providers."); return }
        val repaired = ChatHistoryJson.repair(messages.toList())
        messages.clear(); messages.addAll(repaired)
        val labels = attachedContext.map { it.label } + if (voice) listOf("🎙 Voice") else emptyList()
        messages += ChatMessage(Role.User, prompt.ifEmpty { "See the attached image." }, images = attachedImages.toList(),
            detail = if (labels.isEmpty()) null else "CTX " + labels.joinToString("|"))
        pendingContext = attachedContext.joinToString("\n\n") { it.text }
        if (voice) pendingContext = (pendingContext + "\n\n" + VoiceText.PROMPT_NOTE).trim()
        attachedImages.clear(); attachedContext.clear()
        if (chatId == null) {
            chatId = UUID.randomUUID().toString()
            chatTitle = prompt.lineSequence().firstOrNull().orEmpty().take(60).ifBlank { "Image task" }
        }
        persist()
        start()
    }

    private var pendingContext = ""

    private fun start() {
        val (projectId, projectName) = host.currentProject() ?: return
        val spec = provider ?: return
        val model = modelId ?: return
        val apiKey = key(spec.id)
        val url = baseUrl(spec)
        val runMode = mode
        val firstTurn = messages.count { it.role == Role.User } == 1
        val extraContext = pendingContext.also { pendingContext = "" }
        running = true
        streaming = ""
        lastChanges.clear()
        planAwaitingApproval = false
        job = scope.launch {
            val approver = Approver { request ->
                if (autoDecision(request, projectId)) return@Approver Decision.Allow
                withContext(Dispatchers.Main) {
                    val deferred = CompletableDeferred<Decision>()
                    pending = PendingApproval(request, deferred)
                    try {
                        val decision = deferred.await()
                        if (decision == Decision.AlwaysAllow && request is ApprovalRequest.Command && !request.dangerous)
                            prefs.edit().putBoolean("cmd_always_$projectId", true).apply()
                        decision
                    } finally { if (pending?.answer === deferred) pending = null }
                }
            }
            val recorder = ChangeRecorder { path, original -> main.post { if (!lastChanges.containsKey(path)) lastChanges[path] = original } }
            var status = "done"
            try {
                host.beforeAgentRun()
                val workspace = host.agentWorkspace() ?: error("Open a project first")
                val vision = canSee(spec.id, model)
                val browserTools = if (browserEnabled) host.browserTools({ vision }) { shot ->
                    main.post { liveShot = shot; usingBrowser = true }
                } else null
                val toolsets = listOfNotNull(
                    GitAccountTools(host.gitBridge, approver),
                    PlanToolset { title, steps -> main.post { planTitle = title; plan.clear(); plan.addAll(steps) } },
                    WebToolset({ internetEnabled }),
                    browserTools)
                val tools = AgentTools(workspace, approver, toolsets, recorder)
                val openFile = if (includeOpenFile) host.openFile() else null
                val system = withContext(Dispatchers.IO) {
                    val files = runCatching { workspace.files() }.getOrDefault(emptyList())
                    val rules = listOf("AGENTS.md", "CODRIOD.md").firstNotNullOfOrNull { name ->
                        if (name in files) runCatching { workspace.read(name) }.getOrNull() else null
                    }
                    AgentPrompt.system(projectName, files, openFile, rules, instructions, runMode, spec.supportsTools,
                        browser = browserTools != null, internet = internetEnabled, vision = vision, extraContext = extraContext)
                }
                val onText: ((String) -> Unit)? = if (streamingEnabled) { piece -> main.post { streaming += piece } } else null
                val backend = ChatBackend { s, m, t, cb -> client.chat(spec, apiKey, url, model, s, m, t, cb) }
                val history = messages.toMutableList()
                withContext(Dispatchers.IO) {
                    AgentEngine(backend, tools, if (runMode == AgentMode.Autopilot) maxOf(maxSteps, 60) else maxSteps).run(
                        history, system, runMode, spec.supportsTools,
                        onChange = { withContext(Dispatchers.Main) { streaming = ""; sync(history.toList()) } },
                        onUsage = { usage -> main.post { lastUsage = usage; totalTokens += usage.input + usage.output } },
                        onFilesChanged = { withContext(Dispatchers.Main) { host.afterAgentChangedFiles() } },
                        onText = onText, visionImages = vision)
                }
                if (runMode == AgentMode.Plan && plan.isNotEmpty()) planAwaitingApproval = true
                if (messages.lastOrNull()?.role == Role.Error) status = "error"
            } catch (error: CancellationException) {
                status = "stopped"
                withContext(NonCancellable + Dispatchers.Main) { messages += ChatMessage(Role.Error, "Stopped.") }
            } catch (error: Exception) {
                status = "error"
                messages += ChatMessage(Role.Error, friendlyError(error))
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    running = false
                    pending = null
                    streaming = ""
                    persist(status)
                    if (firstTurn && status == "done") makeTitle()
                }
            }
        }
    }

    private fun friendlyError(error: Exception): String {
        val m = error.message ?: error.javaClass.simpleName
        return when {
            m.startsWith("HTTP 401") || m.startsWith("HTTP 403") -> "The provider rejected the API key ($m). Check it in Agent settings → Providers."
            m.startsWith("HTTP 429") -> "Rate limit or quota reached ($m). Wait a moment, or switch to another model."
            m.startsWith("HTTP 404") -> "Model or endpoint not found ($m). Pick another model."
            error is java.net.UnknownHostException -> "No internet connection."
            error is java.net.SocketTimeoutException -> "The provider took too long to answer. Try again."
            else -> m
        }
    }

    /** Names the chat with the fast model (falls back to the first message). */
    private fun makeTitle() {
        val ref = fastModel ?: return
        val spec = Providers.byId(ref.provider) ?: return
        if (!hasKey(spec)) return
        val first = messages.firstOrNull { it.role == Role.User }?.text ?: return
        val id = chatId ?: return
        scope.launch {
            runCatching {
                val reply = client.chat(spec, key(spec.id), baseUrl(spec), ref.model,
                    "Write a short title (3 to 6 words, no quotes, no punctuation at the end) for this coding request. Reply with the title only.",
                    listOf(ChatMessage(Role.User, first.take(2000))), emptyList())
                val title = reply.text.lines().firstOrNull { it.isNotBlank() }?.trim()?.trim('"', '\'', '.')?.take(60)
                if (!title.isNullOrBlank() && chatId == id) { chatTitle = title; persist() }
            }
        }
    }

    private fun sync(history: List<ChatMessage>) {
        if (history.size > messages.size) messages.addAll(history.subList(messages.size, history.size))
        persist()
    }
}
