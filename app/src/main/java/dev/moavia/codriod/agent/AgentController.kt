package dev.moavia.codriod.agent

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import dev.moavia.codriod.workspace.ProjectStore
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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
    override fun search(query: String) = store.search(projectId, query).map { SearchMatch(it.path, it.line, it.text) }
    override fun run(command: String, timeoutSeconds: Int) = shell.run(store.root(projectId), command, timeoutSeconds)
}

class PendingApproval(val request: ApprovalRequest, internal val answer: CompletableDeferred<Decision>)

/**
 * Settings, keys and chat state for Codriod Agent. API keys are the user's own and are kept in this app's
 * private storage on the phone (plain, as chosen for this app); they are sent only to that provider.
 */
class AgentController(context: Context, private val scope: CoroutineScope, private val host: AgentHost) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences("agent", Context.MODE_PRIVATE)
    private val client = LlmClient()
    private val chatDir = File(appContext.filesDir, "agent/chats")

    val messages = mutableStateListOf<ChatMessage>()
    var running by mutableStateOf(false)
        private set
    var pending by mutableStateOf<PendingApproval?>(null)
        private set
    var lastUsage by mutableStateOf<AgentEngine.Usage?>(null)
        private set
    /** Bumped whenever keys or model lists change so the UI re-reads them. */
    var revision by mutableIntStateOf(0)
        private set
    val fetching = mutableStateMapOf<String, Boolean>()
    val fetchErrors = mutableStateMapOf<String, String>()

    var providerId by mutableStateOf(prefs.getString("provider", null))
        private set
    var modelId by mutableStateOf(prefs.getString("model", null))
        private set
    var mode by mutableStateOf(runCatching { AgentMode.valueOf(prefs.getString("mode", "Agent")!!) }.getOrDefault(AgentMode.Agent))
        private set
    var autoApplyEdits by mutableStateOf(prefs.getBoolean("auto_apply", false))
        private set
    var includeOpenFile by mutableStateOf(prefs.getBoolean("include_open_file", true))
        private set
    var instructions by mutableStateOf(prefs.getString("instructions", "") ?: "")
        private set
    var maxSteps by mutableIntStateOf(prefs.getInt("max_steps", 25))
        private set

    private var boundProject: String? = null
    private var job: Job? = null

    val provider get() = Providers.byId(providerId)
    val ready get() = provider != null && !modelId.isNullOrBlank()

    // ---- settings ----------------------------------------------------------------------------------

    fun key(id: String): String = prefs.getString("key_$id", "") ?: ""
    fun hasKey(spec: ProviderSpec) = !spec.needsKey || key(spec.id).isNotBlank()
    fun baseUrl(spec: ProviderSpec): String = prefs.getString("url_${spec.id}", null)?.takeIf { it.isNotBlank() } ?: spec.baseUrl

    fun saveProvider(spec: ProviderSpec, apiKey: String, url: String?) {
        prefs.edit().putString("key_${spec.id}", apiKey.trim()).apply {
            if (spec.editableUrl) putString("url_${spec.id}", url?.trim().orEmpty())
        }.apply()
        revision++
    }

    fun models(spec: ProviderSpec): List<ModelInfo> = prefs.getString("models_${spec.id}", null)?.let { json ->
        runCatching {
            val array = JSONArray(json)
            (0 until array.length()).map { i -> array.getJSONObject(i).let {
                ModelInfo(it.getString("id"), it.optBoolean("free"), it.optInt("ctx")) } }
        }.getOrNull()
    } ?: spec.fallbackModels.map { ModelInfo(it) }

    fun modelsFetched(spec: ProviderSpec) = prefs.contains("models_${spec.id}")

    fun fetchModels(spec: ProviderSpec) {
        if (fetching[spec.id] == true) return
        if (!hasKey(spec)) { fetchErrors[spec.id] = "Add your API key first"; return }
        fetching[spec.id] = true
        fetchErrors.remove(spec.id)
        scope.launch {
            try {
                val list = client.listModels(spec, key(spec.id), baseUrl(spec))
                val json = JSONArray().apply { list.forEach { put(JSONObject().put("id", it.id).put("free", it.free).put("ctx", it.contextLength)) } }
                prefs.edit().putString("models_${spec.id}", json.toString()).apply()
                revision++
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { fetchErrors[spec.id] = error.message ?: "Could not load models" }
            finally { fetching[spec.id] = false }
        }
    }

    fun select(spec: ProviderSpec, model: String) {
        providerId = spec.id; modelId = model.trim()
        prefs.edit().putString("provider", spec.id).putString("model", modelId).apply()
    }

    fun updateMode(value: AgentMode) { mode = value; prefs.edit().putString("mode", value.name).apply() }
    fun updateAutoApply(value: Boolean) { autoApplyEdits = value; prefs.edit().putBoolean("auto_apply", value).apply() }
    fun updateIncludeOpenFile(value: Boolean) { includeOpenFile = value; prefs.edit().putBoolean("include_open_file", value).apply() }
    fun updateInstructions(value: String) { instructions = value; prefs.edit().putString("instructions", value).apply() }
    fun updateMaxSteps(value: Int) { maxSteps = value.coerceIn(5, 60); prefs.edit().putInt("max_steps", maxSteps).apply() }

    private fun commandsAlways(project: String) = prefs.getBoolean("cmd_always_$project", false)
    fun commandsAlwaysAllowed(): Boolean = host.currentProject()?.first?.let(::commandsAlways) ?: false
    fun revokeCommandPermission() { host.currentProject()?.first?.let { prefs.edit().remove("cmd_always_$it").apply() }; revision++ }

    // ---- chat ----------------------------------------------------------------------------------------

    /** Loads the chat for the open project. */
    fun bind(projectId: String?) {
        if (projectId == boundProject) return
        stop()
        boundProject = projectId
        messages.clear()
        lastUsage = null
        if (projectId == null) return
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { ChatHistoryJson.decode(File(chatDir, "$projectId.json").readText()) }.getOrDefault(emptyList())
            }
            if (boundProject == projectId) { messages.clear(); messages.addAll(loaded) }
        }
    }

    private fun persist() {
        val project = boundProject ?: return
        val snapshot = messages.toList().takeLast(300)
        scope.launch(Dispatchers.IO) {
            runCatching { chatDir.mkdirs(); File(chatDir, "$project.json").writeText(ChatHistoryJson.encode(snapshot)) }
        }
    }

    fun clear() {
        stop()
        messages.clear()
        lastUsage = null
        persist()
    }

    fun stop() {
        job?.cancel()
        job = null
        pending?.answer?.cancel()
        pending = null
    }

    fun answer(decision: Decision) { pending?.answer?.complete(decision) }

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || running) return
        val (projectId, projectName) = host.currentProject() ?: return
        val spec = provider
        val model = modelId
        if (spec == null || model.isNullOrBlank()) {
            messages += ChatMessage(Role.Error, "Choose a provider and model first (Providers tab)."); return
        }
        if (!hasKey(spec)) {
            messages += ChatMessage(Role.Error, "Add your ${spec.name} API key in the Providers tab."); return
        }
        val apiKey = key(spec.id)
        val url = baseUrl(spec)
        val repaired = ChatHistoryJson.repair(messages.toList())
        messages.clear(); messages.addAll(repaired)
        messages += ChatMessage(Role.User, prompt)
        persist()
        running = true
        job = scope.launch {
            val approver = Approver { request ->
                withContext(Dispatchers.Main) {
                    val deferred = CompletableDeferred<Decision>()
                    pending = PendingApproval(request, deferred)
                    try { deferred.await() } finally { if (pending?.answer === deferred) pending = null }
                }
            }
            try {
                host.beforeAgentRun()
                val workspace = host.agentWorkspace() ?: error("Open a project first")
                val tools = AgentTools(workspace, approver, { autoApplyEdits }, { commandsAlways(projectId) },
                    { prefs.edit().putBoolean("cmd_always_$projectId", true).apply() },
                    listOf(GitAccountTools(host.gitBridge, approver)))
                val openFile = if (includeOpenFile) host.openFile() else null
                val system = withContext(Dispatchers.IO) {
                    val files = runCatching { workspace.files() }.getOrDefault(emptyList())
                    val rules = listOf("AGENTS.md", "CODRIOD.md").firstNotNullOfOrNull { name ->
                        if (name in files) runCatching { workspace.read(name) }.getOrNull() else null
                    }
                    AgentPrompt.system(projectName, files, openFile, rules, instructions, mode, spec.supportsTools)
                }
                val backend = ChatBackend { s, m, t -> client.chat(spec, apiKey, url, model, s, m, t) }
                val history = messages.toMutableList()
                withContext(Dispatchers.IO) {
                    AgentEngine(backend, tools, maxSteps).run(history, system, mode, spec.supportsTools,
                        onChange = { withContext(Dispatchers.Main) { sync(history.toList()) } },
                        onUsage = { usage -> scope.launch(Dispatchers.Main) { lastUsage = usage } },
                        onFilesChanged = { withContext(Dispatchers.Main) { host.afterAgentChangedFiles() } })
                }
            } catch (error: CancellationException) {
                withContext(NonCancellable + Dispatchers.Main) { messages += ChatMessage(Role.Error, "Stopped.") }
            } catch (error: Exception) {
                messages += ChatMessage(Role.Error, error.message ?: error.javaClass.simpleName)
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    running = false
                    pending = null
                    persist()
                }
            }
        }
    }

    private fun sync(history: List<ChatMessage>) {
        // history only grows during a run; append what the UI does not have yet.
        if (history.size > messages.size) messages.addAll(history.subList(messages.size, history.size))
        persist()
    }
}
