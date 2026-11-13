package dev.moavia.codriod.workspace

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.runtime.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.moavia.codriod.agent.AgentController
import dev.moavia.codriod.agent.AgentHost
import dev.moavia.codriod.agent.AgentWorkspace
import dev.moavia.codriod.agent.GitAccountBridge
import dev.moavia.codriod.agent.ProjectAgentWorkspace
import dev.moavia.codriod.agent.GitAccountStatus
import dev.moavia.codriod.git.GitAuthException
import dev.moavia.codriod.git.GitHubRepo
import dev.moavia.codriod.git.GitRemoteService
import kotlinx.coroutines.*
import java.io.File

/** Files the editor shows as pictures instead of text. */
object ImageFiles {
    private val raster = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "heic", "heif", "avif", "wbmp")
    fun extension(path: String) = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
    fun isImage(path: String) = extension(path) in raster || extension(path) == "svg"
    fun isSvg(path: String) = extension(path) == "svg"
}

/** An open tab: text (editable) or an image ([image] = true; shown in the image viewer, never saved as text). */
class EditorDocument(val path: String, initial: String, val image: Boolean = false) {
    /** Bumped when an image file changed on disk, so the viewer reloads it. */
    var revision by mutableIntStateOf(0)
    var value by mutableStateOf(TextFieldValue(initial))
    var savedText by mutableStateOf(initial)
    val dirty get() = value.text != savedText
    val undo = ArrayDeque<TextFieldValue>()
    val redo = ArrayDeque<TextFieldValue>()
}

class WorkspaceModel(application: Application) : AndroidViewModel(application) {
    val store = ProjectStore(File(application.filesDir, "workspaces"), application,
        sharedAccess = { StorageAccess.granted(application) }, sharedHome = { StorageAccess.projectsHome() })
    /** True when projects can live in Internal storage/Codriod (All files access / storage permission). */
    var storageGranted by mutableStateOf(StorageAccess.granted(application))
        private set
    /** Older projects still in Codriod's private storage. */
    var privateProjectCount by mutableIntStateOf(0)
        private set
    private val prefs = application.getSharedPreferences("workspace", 0)
    var themeMode by mutableStateOf(prefs.getString("theme", "System") ?: "System")
        private set
    var projects by mutableStateOf<List<Project>>(emptyList())
        private set
    var project by mutableStateOf<Project?>(null)
        private set
    var entries by mutableStateOf<List<ProjectEntry>>(emptyList())
        private set
    val documents = mutableStateListOf<EditorDocument>()
    var activePath by mutableStateOf<String?>(null)
        private set
    val activeDocument get() = documents.firstOrNull { it.path == activePath }
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    var terminalOpen by mutableStateOf(false)
    var terminalFullscreen by mutableStateOf(false)
    var previewOpen by mutableStateOf(false)
    var fileSheet by mutableStateOf(false)
    var searchSheet by mutableStateOf(false)
    /** In-file find/replace bar in the editor. */
    var findOpen by mutableStateOf(false)
    var gitOpen by mutableStateOf(false)
    var gitSnapshot by mutableStateOf(GitSnapshot())
        private set
    var gitDiff by mutableStateOf("")
        private set
    var gitDiffPath by mutableStateOf<String?>(null)
        private set
    var gitDiffCached by mutableStateOf(false)
        private set
    var previewRevision by mutableIntStateOf(0)
        private set
    var terminal by mutableStateOf<WorkspaceTerminalController?>(null)
        private set
    /** Ubuntu (proot) with Node.js and the official AI CLIs. */
    val linux = dev.moavia.codriod.linux.LinuxEnvironment(application, viewModelScope)
    /** Add-ons → Linux & AI CLIs screen. */
    var linuxOpen by mutableStateOf(false)
    /** Add-ons → Java & Kotlin: JDK, Kotlin compiler and Gradle inside Ubuntu. */
    val javaKit = dev.moavia.codriod.jdk.JavaKitEnvironment(application, viewModelScope, linux)
    /** Add-ons → Java & Kotlin screen. */
    var javaOpen by mutableStateOf(false)
    /** Add-ons → More extensions: language servers, debuggers, editor themes. */
    val extensions = dev.moavia.codriod.ext.ExtensionsManager(application, viewModelScope, linux, javaKit)
    /** Add-ons → More extensions screen. */
    var extensionsOpen by mutableStateOf(false)
    /** Vertical position of the editor-edge tabs (dp from the centre), dragged by the user. */
    var edgeOffset by mutableFloatStateOf(application.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE).getFloat("edge_offset", -40f))
        private set
    fun moveEdgeTabs(delta: Float) {
        edgeOffset = (edgeOffset + delta).coerceIn(-320f, 320f)
        getApplication<Application>().getSharedPreferences("ui", android.content.Context.MODE_PRIVATE).edit().putFloat("edge_offset", edgeOffset).apply()
    }
    /** Chat panels for the official CLIs (Codex, Copilot, Gemini…) of the open project. */
    val cliPanels = dev.moavia.codriod.cli.CliPanels(application, viewModelScope, linux, { projectMount() }) { command -> openUbuntuTerminal(command) }
    private fun projectMount(): Pair<java.io.File, String>? = project?.let { p -> runCatching { store.root(p.id) to p.name }.getOrNull() }
    var searchResults by mutableStateOf<List<SearchHit>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set
    private val saveJobs = mutableMapOf<String, Job>()
    private var searchJob: Job? = null
    private val gitService = ProjectGitService()
    /** The person's own Git sign-in (SSH key). Shared by the Git panel and the coding agent. */
    val gitAccount = GitAccountController(application)
    var gitRemote by mutableStateOf<String?>(null)
        private set
    /** The in-app browser (Chrome-style, with DevTools). Serves the open project at localhost:3000. */
    val browser = BrowserController(application,
        files = { project?.let { PreviewFiles(store, it.id) } },
        openInEditor = { path, line -> previewOpen = false; openFile(path, line) })
    var addonsOpen by mutableStateOf(false)
    var agentOpen by mutableStateOf(false)

    /** True while the agent side panel is open over the editor. */
    var agentPanelOpen by mutableStateOf(false)
    var agentPanelExpanded by mutableStateOf(false)
    var agentSettingsOpen by mutableStateOf(false)

    private val agentHost = object : AgentHost {
        override fun currentProject() = project?.let { it.id to it.name }
        override fun agentWorkspace(): AgentWorkspace? = project?.let {
            val context = getApplication<Application>()
            ProjectAgentWorkspace(store, it.id, dev.moavia.codriod.agent.ShellRunner(env = CodriodCommands.env(context),
                preamble = CodriodCommands.sourceLine(context)))
        }
        override fun openFile() = activeDocument?.takeIf { !it.image }?.let { it.path to it.value.text }
        override suspend fun beforeAgentRun() = saveAll()
        override suspend fun afterAgentChangedFiles() = reloadFromDisk()
        override val gitBridge get() = gitAccountBridge
        override fun projectName(id: String) = projects.firstOrNull { it.id == id }?.name
        override fun environmentNotes() = linux.agentNotes(project?.name)
        override fun extraToolsets(approver: dev.moavia.codriod.agent.Approver): List<dev.moavia.codriod.agent.AgentToolset> =
            if (linux.phase == dev.moavia.codriod.linux.LinuxPhase.Unsupported) emptyList()
            else listOf(dev.moavia.codriod.linux.LinuxToolset(linux, approver, ::projectMount))

        override fun browserTools(vision: () -> Boolean, onScreenshot: (String) -> Unit) =
            BrowserAgentTools(browser, { agent.browserEnabled }, vision, java.io.File(getApplication<Application>().cacheDir, "agent-shots"), onScreenshot)

        override suspend fun browserScreenshot(): String? = withContext(Dispatchers.Main) {
            val tab = browser.active?.takeIf { !it.isNewTab } ?: browser.tabs.firstOrNull { it.agent && !it.isNewTab } ?: return@withContext null
            val bitmap = runCatching { browser.capture(tab) }.getOrNull() ?: return@withContext null
            withContext(Dispatchers.IO) {
                val file = java.io.File(getApplication<Application>().cacheDir, "agent-shots/attach-${System.currentTimeMillis()}.jpg")
                file.parentFile?.mkdirs()
                file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
                file.absolutePath
            }
        }

        override fun showAgentBrowser() {
            browser.tabs.firstOrNull { it.agent }?.let { browser.select(it) }
            agentPanelOpen = false
            gitOpen = false; addonsOpen = false; agentOpen = false; githubOpen = false
            previewOpen = true
        }

        override fun contextOptions(): List<dev.moavia.codriod.agent.ContextOption> {
            val current = project ?: return emptyList()
            val options = mutableListOf<dev.moavia.codriod.agent.ContextOption>()
            activeDocument?.let { doc ->
                if (!doc.image) options += dev.moavia.codriod.agent.ContextOption("file", "📄", doc.path, "open in editor") { "File ${doc.path}:\n```\n${doc.value.text.take(60_000)}\n```" }
            }
            documents.filter { it.path != activePath && !it.image }.take(6).forEach { doc ->
                options += dev.moavia.codriod.agent.ContextOption("tab:${doc.path}", "📄", doc.path, "open tab") { "File ${doc.path}:\n```\n${doc.value.text.take(60_000)}\n```" }
            }
            entries.filter { it.directory && it.path.count { c -> c == '/' } == 0 }.take(8).forEach { dir ->
                val files = entries.filter { !it.directory && it.path.startsWith(dir.path + "/") }
                options += dev.moavia.codriod.agent.ContextOption("dir:${dir.path}", "📁", dir.path + "/", "folder · ${files.size} files") {
                    withContext(Dispatchers.IO) {
                        "Folder ${dir.path}/:\n" + files.take(40).joinToString("\n\n") { f ->
                            val text = runCatching { store.read(current.id, f.path) }.getOrNull()
                            if (text == null) "${f.path} (binary)" else "${f.path}:\n```\n${text.take(8000)}\n```"
                        }.take(80_000)
                    }
                }
            }
            browser.active?.takeIf { !it.isNewTab }?.let { tab ->
                options += dev.moavia.codriod.agent.ContextOption("browser", "🌐", "Browser · ${tab.url.removePrefix("https://").removePrefix("http://").take(40)}",
                    "page text, console and network") {
                    val text = withContext(Dispatchers.Main) {
                        kotlinx.coroutines.suspendCancellableCoroutine<String?> { cont ->
                            val view = tab.view
                            if (view == null) cont.resumeWith(Result.success(null)) else view.evaluateJavascript("document.body ? document.body.innerText.slice(0, 8000) : ''") { cont.resumeWith(Result.success(it)) }
                        }
                    }?.let { runCatching { org.json.JSONArray("[$it]").getString(0) }.getOrNull() }.orEmpty()
                    val console = tab.console.takeLast(30).joinToString("\n") { "${it.level}: ${it.text.take(300)}" }
                    "Browser page ${tab.url} (${tab.title}):\n$text\n\nConsole:\n${console.ifBlank { "(empty)" }}"
                }
            }
            terminal?.let { term ->
                options += dev.moavia.codriod.agent.ContextOption("terminal", "⌘", "Terminal output", "last 200 lines") {
                    "Terminal output:\n```\n" + term.transcript().lines().takeLast(200).joinToString("\n") + "\n```"
                }
            }
            if (gitSnapshot.initialized) options += dev.moavia.codriod.agent.ContextOption("git", "⑂", "Git changes", "${gitSnapshot.changedCount} files changed") {
                withContext(Dispatchers.IO) {
                    val root = store.root(current.id)
                    val snap = gitService.snapshot(root)
                    "Git branch ${snap.branch}. Staged: ${snap.staged}. Modified: ${snap.modified}. Untracked: ${snap.untracked}.\n" +
                        (snap.staged + snap.modified).take(8).joinToString("\n\n") { p -> runCatching { gitService.diff(root, p, p in snap.staged) }.getOrDefault("") }.take(40_000)
                }
            }
            return options
        }
    }
    /** Codriod Agent: the side panel in the editor and Add-ons. Its chats are kept per project. */
    val agent: AgentController = AgentController(application, viewModelScope, agentHost)

    init {
        CodriodCommands.install(application)
        CodriodCommands.host = object : CodriodCommands.Host {
            override fun browse(url: String) {
                val tab = browser.newTab()
                browser.navigate(tab, url)
                agentPanelOpen = false; gitOpen = false; addonsOpen = false; agentOpen = false; githubOpen = false
                previewOpen = true
            }
            override fun screenshot(file: java.io.File): String? {
                val tab = browser.active?.takeIf { !it.isNewTab } ?: return "open a page in the browser first"
                val bitmap = browser.capture(tab) ?: return "the browser is not ready"
                file.parentFile?.mkdirs()
                file.outputStream().use { bitmap.compress(if (file.name.endsWith(".jpg", true)) android.graphics.Bitmap.CompressFormat.JPEG
                    else android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
                return null
            }
            override fun openInEditor(file: java.io.File): Boolean {
                val current = project ?: return false
                val root = runCatching { store.root(current.id).canonicalFile }.getOrNull() ?: return false
                val canonical = file.canonicalFile
                if (!canonical.path.startsWith(root.path + java.io.File.separator)) return false
                openFile(canonical.relativeTo(root).path.replace(java.io.File.separatorChar, '/'))
                terminalOpen = false; previewOpen = false
                return true
            }
            override fun installWithAdb(file: java.io.File) = linux.installWithAdb(file)
        }
        task {
            projects = withContext(Dispatchers.IO) { store.projects() }
            refreshContinue()
            val last = prefs.getString("last_project", null)
            projects.firstOrNull { it.id == last }?.let { loadProject(it) }
        }
    }

    /** Called when the app comes back (e.g. from the All files access settings page). */
    fun refreshStorage() {
        storageGranted = StorageAccess.granted(getApplication())
        viewModelScope.launch {
            privateProjectCount = withContext(Dispatchers.IO) { runCatching { store.privateProjects().size }.getOrDefault(0) }
            projects = withContext(Dispatchers.IO) { store.projects() }
        }
    }

    /** Moves older private projects to Internal storage/Codriod so other apps and file managers can see them. */
    fun moveProjectsToStorage() = task {
        val moved = withContext(Dispatchers.IO) { store.privateProjects().map { store.moveToShared(it) } }
        projects = withContext(Dispatchers.IO) { store.projects() }
        privateProjectCount = 0
        message = "Moved ${moved.size} project${if (moved.size == 1) "" else "s"} to ${StorageAccess.displayPath(StorageAccess.projectsHome())}"
    }

    private fun task(block: suspend () -> Unit) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { message = error.message ?: "Operation failed" }
            finally { busy = false }
        }
    }

    fun updateTheme(mode: String) {
        themeMode = mode
        prefs.edit().putString("theme", themeMode).apply()
    }

    /** The "Continue" card on Home: the last project, its open file and Git state. */
    data class ContinueInfo(val project: Project, val file: String?, val branch: String?, val changes: Int)

    var continueInfo by mutableStateOf<ContinueInfo?>(null)
        private set

    fun refreshContinue() {
        val last = projects.maxByOrNull { it.modified } ?: run { continueInfo = null; return }
        viewModelScope.launch {
            val git = withContext(Dispatchers.IO) { runCatching { gitService.snapshot(store.root(last.id)) }.getOrNull() }
            continueInfo = ContinueInfo(last, prefs.getString("active_" + last.id, null),
                git?.takeIf { it.initialized }?.branch, git?.changedCount ?: 0)
        }
    }

    fun renameProject(target: Project, name: String) = task {
        withContext(Dispatchers.IO) { store.renameProject(target, name) }
        projects = withContext(Dispatchers.IO) { store.projects() }
        refreshContinue()
    }

    fun deleteProject(target: Project) = task {
        withContext(Dispatchers.IO) { store.deleteProject(target) }
        prefs.edit().remove("tabs_" + target.id).remove("active_" + target.id).apply()
        projects = withContext(Dispatchers.IO) { store.projects() }
        refreshContinue()
        message = if (target.treeUri != null) "Removed “${target.name}” from Codriod. The folder on your phone was not touched."
            else "Deleted “${target.name}”"
    }

    fun cycleTheme() {
        themeMode = when (themeMode) { "Dark" -> "Light"; "Light" -> "System"; else -> "Dark" }
        prefs.edit().putString("theme", themeMode).apply()
        message = "Theme: $themeMode"
    }

    fun createProject(name: String, template: String) = task {
        val created = withContext(Dispatchers.IO) { store.createProject(name, template) }
        projects = withContext(Dispatchers.IO) { store.projects() }
        loadProject(created)
    }

    fun openFolderUri(uri: android.net.Uri) = task {
        val context = getApplication<Application>()
        val doc = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
        val folderName = doc?.name?.takeIf { it.isNotBlank() } ?: "Folder"
        val project = withContext(Dispatchers.IO) {
            store.openTreeFolder(folderName, uri.toString())
        }
        projects = withContext(Dispatchers.IO) { store.projects() }
        loadProject(project)
    }

    fun openProject(selected: Project) = task { loadProject(selected) }

    private suspend fun loadProject(selected: Project) {
        if (selected.treeUri != null) {
            val helper = SafWorkspaceHelper(getApplication())
            if (!helper.hasPermission(selected.treeUri)) {
                message = "Access to '${selected.name}' was revoked or is unavailable. Please re-open the folder."
                return
            }
        }
        terminal?.close()
        terminal = null
        documents.clear()
        project = withContext(Dispatchers.IO) { store.touch(selected) }
        projects = withContext(Dispatchers.IO) { store.projects() }
        entries = withContext(Dispatchers.IO) {
            runCatching { store.entries(selected.id) }.getOrElse {
                message = it.message ?: "Cannot read folder contents"
                emptyList()
            }
        }
        val paths = prefs.getString("tabs_" + selected.id, null)?.split('\n').orEmpty()
        for (path in paths.take(12)) {
            val text = withContext(Dispatchers.IO) { runCatching { store.read(selected.id, path) }.getOrNull() }
            if (text != null) documents.add(EditorDocument(path, text))
        }
        if (documents.isEmpty()) {
            val initial = entries.firstOrNull { it.path == "index.html" || it.path == "main.sh" }
                ?: entries.firstOrNull { !it.directory }
            if (initial != null) {
                val text = withContext(Dispatchers.IO) { runCatching { store.read(selected.id, initial.path) }.getOrNull() }
                if (text != null) documents.add(EditorDocument(initial.path, text))
            }
        }
        activePath = prefs.getString("active_" + selected.id, null)
            ?.takeIf { path -> documents.any { it.path == path } } ?: documents.firstOrNull()?.path
        prefs.edit().putString("last_project", selected.id).apply()
        terminalOpen = false
        terminalFullscreen = false
        previewOpen = false
        gitOpen = false
        gitSnapshot = GitSnapshot()
        gitDiff = ""
        gitDiffPath = null
        persistTabs()
        agent.bind(selected.id)
        browser.reloadProjectTabs()
    }

    fun showAddons() {
        githubOpen = false
        gitOpen = false
        previewOpen = false
        addonsOpen = true
    }

    fun goHome() = task {
        saveAll()
        cliPanels.stopAll()
        terminal?.close()
        terminal = null
        searchJob?.cancel()
        project = null
        documents.clear()
        activePath = null
        previewOpen = false
        terminalOpen = false
        terminalFullscreen = false
        gitOpen = false
        addonsOpen = false
        agentOpen = false
        agent.bind(null)
        prefs.edit().remove("last_project").apply()
        projects = withContext(Dispatchers.IO) { store.projects() }
        refreshContinue()
    }

    fun select(path: String) {
        activePath = path
        persistTabs()
    }

    fun openFile(path: String, line: Int? = null) = task {
        if (path.endsWith(".apk", true)) { installApk(path); fileSheet = false; return@task }
        openDocument(path, line)
        fileSheet = false
        searchSheet = false
    }

    private suspend fun openDocument(path: String, line: Int? = null) {
        val current = project ?: return
        var doc = documents.firstOrNull { it.path == path }
        if (doc == null) {
            require(documents.size < 12) { "Close a tab before opening another (12 tab limit)" }
            doc = if (ImageFiles.isImage(path)) EditorDocument(path, "", image = true)
                else EditorDocument(path, withContext(Dispatchers.IO) { store.read(current.id, path) })
            documents.add(doc)
        }
        activePath = path
        if (line != null) {
            val offset = doc.value.text.lineSequence().take((line - 1).coerceAtLeast(0)).sumOf { it.length + 1 }
            doc.value = doc.value.copy(selection = TextRange(offset.coerceAtMost(doc.value.text.length)))
        }
        persistTabs()
    }

    /** Installs an APK from the project (Files → tap an .apk); Android asks to confirm unless wireless debugging is on. */
    private fun installApk(path: String) {
        val current = project ?: return
        viewModelScope.launch {
            message = "Installing ${path.substringAfterLast('/')}…"
            val result = withContext(Dispatchers.IO) {
                val file = store.fileOrNull(current.id, path) ?: java.io.File(getApplication<Application>().cacheDir, path.substringAfterLast('/')).also {
                    it.writeBytes(store.readBytes(current.id, path, 300 * 1024 * 1024))
                }
                linux.installWithAdb(file) ?: dev.moavia.codriod.android.ApkInstaller.install(getApplication(), file)
            }
            message = result.second.removePrefix("codriod: ")
        }
    }

    /** Image bytes for the image viewer. */
    suspend fun imageBytes(path: String): ByteArray {
        val current = project ?: error("No project")
        return withContext(Dispatchers.IO) { store.readBytes(current.id, path) }
    }

    /** Opens an SVG (or any image) as text instead, to edit its code. */
    fun openAsText(path: String) = task {
        val current = project ?: return@task
        val index = documents.indexOfFirst { it.path == path }
        val text = withContext(Dispatchers.IO) { store.read(current.id, path) }
        val doc = EditorDocument(path, text)
        if (index >= 0) documents[index] = doc else documents.add(doc)
        activePath = path
    }

    /** Sends an image from the project to Codriod AI as an attachment and opens the panel. */
    fun askAgentAboutImage(path: String) = task {
        val current = project ?: return@task
        val file = store.fileOrNull(current.id, path) ?: withContext(Dispatchers.IO) {
            File(getApplication<Application>().cacheDir, "agent-shots/" + path.substringAfterLast('/')).also {
                it.parentFile?.mkdirs(); it.writeBytes(store.readBytes(current.id, path))
            }
        }
        agent.attachedImages += file.absolutePath
        agentPanelOpen = true
    }

    fun edit(doc: EditorDocument, value: TextFieldValue) {
        if (busy) return
        if (value.text != doc.value.text) {
            doc.undo.addLast(doc.value)
            if (doc.undo.size > 60) doc.undo.removeFirst()
            doc.redo.clear()
        }
        doc.value = value
        if (doc.dirty) scheduleSave(doc)
    }

    fun insert(text: String) {
        val doc = activeDocument ?: return
        val value = doc.value
        val start = value.selection.min
        val end = value.selection.max
        edit(doc, TextFieldValue(value.text.replaceRange(start, end, text), TextRange(start + text.length)))
    }

    fun moveCursor(delta: Int) {
        val doc = activeDocument ?: return
        val selection = doc.value.selection
        val cursor = if (selection.collapsed) selection.end
            else if (delta < 0) selection.min else selection.max
        doc.value = doc.value.copy(selection = TextRange((cursor + delta).coerceIn(0, doc.value.text.length)))
    }

    fun undo() {
        val doc = activeDocument ?: return
        if (busy || doc.undo.isEmpty()) return
        doc.redo.addLast(doc.value)
        doc.value = doc.undo.removeLast()
        scheduleSave(doc)
    }

    fun redo() {
        val doc = activeDocument ?: return
        if (busy || doc.redo.isEmpty()) return
        doc.undo.addLast(doc.value)
        doc.value = doc.redo.removeLast()
        scheduleSave(doc)
    }

    /** Inserts a completion, replacing the typed token and restoring the caret inside the snippet. */
    fun applyCompletion(item: CompletionItem, replaceStart: Int, replaceEnd: Int) {
        val doc = activeDocument ?: return
        val text = doc.value.text
        val start = replaceStart.coerceIn(0, text.length)
        val end = replaceEnd.coerceIn(start, text.length)
        val newText = text.replaceRange(start, end, item.insert)
        val caret = (start + item.cursor).coerceIn(0, newText.length)
        edit(doc, TextFieldValue(newText, TextRange(caret)))
    }

    private fun clipboard(): ClipboardManager =
        getApplication<Application>().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun putClipboard(text: String) {
        clipboard().setPrimaryClip(ClipData.newPlainText("Codriod", text))
    }

    /** Copies the complete text of the active file (not just the visible lines). */
    fun copyAll() {
        val doc = activeDocument ?: return
        val text = doc.value.text
        if (text.isEmpty()) { message = "Nothing to copy"; return }
        putClipboard(text)
        message = "Code copied successfully"
    }

    fun copySelection() {
        val doc = activeDocument ?: return
        val selection = doc.value.selection
        if (selection.collapsed) { message = "Select some code first"; return }
        val text = doc.value.text.substring(selection.min, selection.max)
        if (text.isEmpty()) { message = "Nothing to copy"; return }
        putClipboard(text)
        message = "Selection copied"
    }

    fun cutSelection() {
        val doc = activeDocument ?: return
        val selection = doc.value.selection
        if (selection.collapsed) { message = "Select some code first"; return }
        val text = doc.value.text.substring(selection.min, selection.max)
        if (text.isEmpty()) { message = "Nothing to cut"; return }
        putClipboard(text)
        edit(doc, TextFieldValue(doc.value.text.removeRange(selection.min, selection.max), TextRange(selection.min)))
        message = "Selection cut"
    }

    fun paste() {
        val doc = activeDocument ?: return
        val clip = clipboard().primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(getApplication()).toString() else ""
        if (text.isEmpty()) { message = "Clipboard is empty"; return }
        val value = doc.value
        val start = value.selection.min
        val end = value.selection.max
        edit(doc, TextFieldValue(value.text.replaceRange(start, end, text), TextRange(start + text.length)))
    }

    fun selectAll() {
        val doc = activeDocument ?: return
        doc.value = doc.value.copy(selection = TextRange(0, doc.value.text.length))
    }

    /** Replaces the current selection (when it matches [query]) or all occurrences in the active file. */
    fun replaceInFile(query: String, replacement: String, all: Boolean) {
        val doc = activeDocument ?: return
        if (query.isEmpty()) return
        val text = doc.value.text
        if (all) {
            if (!text.contains(query, ignoreCase = true)) { message = "No matches"; return }
            val updated = text.replace(query, replacement, ignoreCase = true)
            edit(doc, TextFieldValue(updated, TextRange(0)))
            message = "Replaced all occurrences"
        } else {
            val selection = doc.value.selection
            val selected = if (!selection.collapsed) text.substring(selection.min, selection.max) else ""
            if (selected.equals(query, ignoreCase = true)) {
                edit(doc, TextFieldValue(text.replaceRange(selection.min, selection.max, replacement), TextRange(selection.min + replacement.length)))
            } else {
                message = "Select a match to replace"
            }
        }
    }

    private fun scheduleSave(doc: EditorDocument) {
        val current = project ?: return
        saveJobs.remove(doc.path)?.cancel()
        saveJobs[doc.path] = viewModelScope.launch {
            delay(500)
            val text = doc.value.text
            try {
                withContext(Dispatchers.IO) { store.write(current.id, doc.path, text) }
                doc.savedText = text
                previewRevision++
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { message = "Unsaved: " + (error.message ?: doc.path) }
        }
    }

    private suspend fun saveAll() {
        saveJobs.values.forEach { it.cancel() }
        saveJobs.clear()
        val current = project ?: return
        for (doc in documents.filter { it.dirty }) {
            val text = doc.value.text
            withContext(Dispatchers.IO) { store.write(current.id, doc.path, text) }
            doc.savedText = text
        }
        previewRevision++
    }

    fun save() = task { saveAll(); message = "All files saved" }

    fun flushPending() {
        saveJobs.values.forEach { it.cancel() }
        saveJobs.clear()
        val current = project ?: return
        val pending = documents.filter { it.dirty }.map { it to it.value.text }
        if (pending.isEmpty()) return
        viewModelScope.launch {
            for ((doc, text) in pending) {
                try {
                    withContext(Dispatchers.IO) { store.write(current.id, doc.path, text) }
                    doc.savedText = text
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { message = "Unsaved: " + (error.message ?: doc.path) }
            }
        }
    }

    fun closeTab(path: String) = task {
        saveAll()
        documents.removeAll { it.path == path }
        if (activePath == path) activePath = documents.lastOrNull()?.path
        persistTabs()
    }

    private fun persistTabs() {
        val current = project ?: return
        prefs.edit().putString("tabs_" + current.id, documents.joinToString("\n") { it.path })
            .putString("active_" + current.id, activePath).apply()
    }

    fun createEntry(path: String, folder: Boolean) = task {
        val current = project ?: return@task
        withContext(Dispatchers.IO) { store.createEntry(current.id, path.trim(), folder) }
        entries = withContext(Dispatchers.IO) { store.entries(current.id) }
        if (!folder) openDocument(path.trim())
        else message = "Folder created"
    }

    fun renameEntry(old: String, new: String) = task {
        val current = project ?: return@task
        saveAll()
        withContext(Dispatchers.IO) { store.rename(current.id, old, new.trim()) }
        val affected = documents.filter { it.path == old || it.path.startsWith("$old/") }
        val selected = activePath
        for (doc in affected) {
            val path = new.trim() + doc.path.removePrefix(old)
            val replacement = EditorDocument(path, doc.value.text, doc.image).also { it.value = doc.value }
            documents[documents.indexOf(doc)] = replacement
            if (selected == doc.path) activePath = path
        }
        entries = withContext(Dispatchers.IO) { store.entries(current.id) }
        persistTabs()
    }

    fun deleteEntry(path: String) = task {
        val current = project ?: return@task
        saveAll()
        withContext(Dispatchers.IO) { store.delete(current.id, path) }
        documents.removeAll { it.path == path }
        if (activePath == path) activePath = documents.firstOrNull()?.path
        entries = withContext(Dispatchers.IO) { store.entries(current.id) }
        persistTabs()
    }

    fun refreshFiles() = task { reloadFromDisk() }

    /** Re-reads the file list and every open tab without unsaved edits (after Git or the agent changed files). */
    private suspend fun reloadFromDisk() {
        val current = project ?: return
        entries = withContext(Dispatchers.IO) { store.entries(current.id) }
        documents.filter { it.image }.forEach { it.revision++ }
        for (doc in documents.filterNot { it.dirty || it.image }) {
            val disk = withContext(Dispatchers.IO) { runCatching { store.read(current.id, doc.path) }.getOrNull() }
            if (disk != null && disk != doc.value.text) {
                doc.value = TextFieldValue(disk)
                doc.savedText = disk
                doc.undo.clear()
                doc.redo.clear()
            }
        }
        previewRevision++
    }

    fun search(query: String) {
        searchJob?.cancel()
        val current = project ?: return
        searchJob = viewModelScope.launch {
            searching = true
            try {
                delay(250)
                saveAll()
                searchResults = withContext(Dispatchers.IO) { store.search(current.id, query) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { message = error.message }
            finally { searching = false }
        }
    }

    fun showTerminal() {
        val current = project ?: return
        if (terminal == null) terminal = WorkspaceTerminalController(getApplication(), store.root(current.id)) {
            if (linux.ready) dev.moavia.codriod.linux.UbuntuShellBackend(linux, ::projectMount) else null
        }
        terminalOpen = true
    }

    /** Opens the same terminal controller as a full-screen page, keeping every tab and session. */
    fun showTerminalFullscreen() {
        showTerminal()
        if (project != null) terminalFullscreen = true
    }

    /** Opens a CLI's chat panel (Codex, Copilot, Gemini…); CLIs without a chat mode open in the terminal. */
    fun openCli(id: String, login: Boolean = false) {
        if (!linux.ready || id !in linux.installed) { linuxOpen = true; return }
        if (project == null) { message = "Open a project first"; return }
        agentPanelOpen = false
        if (login || !cliPanels.hasPanel(id)) openCliTerminal(id, login) else cliPanels.open(id)
    }

    /** Runs a CLI (or its sign-in) in the Ubuntu terminal in the project folder. */
    fun openCliTerminal(id: String, login: Boolean = false) {
        val cli = dev.moavia.codriod.linux.CliCatalog.byId(id) ?: return
        if (!linux.ready || id !in linux.installed) { linuxOpen = true; return }
        if (project == null) { message = "Open a project first"; return }
        agentPanelOpen = false; cliPanels.openId = null
        showTerminal()
        terminal?.useShell("ubuntu", if (login && cli.loginCommand != null) cli.loginCommand else cli.command)
    }

    /** Opens the agent panel and sends [text] (e.g. an install problem to fix). */
    fun askAgent(text: String) {
        if (project == null) { message = "Open a project, then ask Codriod AI from the editor"; return }
        linuxOpen = false; addonsOpen = false
        agentPanelOpen = true
        agent.send(text)
    }

    /** Opens the terminal in Ubuntu (optionally typing [command]). */
    fun openUbuntuTerminal(command: String? = null) {
        if (project == null) { message = "Open a project first"; return }
        cliPanels.openId = null
        showTerminal()
        terminal?.useShell("ubuntu", command)
    }

    fun runActive() = task {
        val current = project ?: return@task
        val path = activePath ?: return@task
        saveAll()
        when (path.substringAfterLast('.', "").lowercase()) {
            "html", "htm", "css", "js" -> {
                browser.openProject(previewEntry())
                previewOpen = true
            }
            "sh" -> {
                showTerminal()
                terminal?.execute("cd " + shellQuote(store.root(current.id).absolutePath) +
                    " && /system/bin/sh " + shellQuote("./$path") + "\n")
            }
            else -> message = "Run supports shell files (.sh) and static web preview. Other runtimes are not installed."
        }
    }

    /** Opens the browser; the first time in a project with HTML, it opens the project page. */
    fun showPreview() = task {
        saveAll()
        gitOpen = false
        addonsOpen = false
        agentOpen = false
        githubOpen = false
        if (browser.tabs.isEmpty() && entries.any { !it.directory && it.path.endsWith(".html", true) }) browser.openProject(previewEntry())
        previewOpen = true
    }

    fun showGit() = task {
        saveAll()
        addonsOpen = false
        agentOpen = false
        githubOpen = false
        previewOpen = false
        gitOpen = true
        refreshGitState()
    }

    fun closeGit() { gitOpen = false; gitDiffPath = null }

    fun refreshGit() = task {
        saveAll()
        refreshGitState()
    }

    fun initializeGit() = gitTask { root -> gitService.initialize(root) }

    fun stageGitFile(path: String) = gitTask { root -> gitService.stage(root, path) }

    fun stageAllGitFiles() = gitTask { root -> gitService.stageAll(root) }

    fun unstageGitFile(path: String) = gitTask { root -> gitService.unstage(root, path) }

    fun unstageAllGitFiles() = gitTask { root -> gitService.unstageAll(root) }

    fun commitGit(message: String, name: String, email: String) = gitTask { root ->
        gitService.commit(root, message, name, email)
    }

    fun createGitBranch(name: String) = gitTask { root -> gitService.createBranch(root, name) }

    fun checkoutGitBranch(name: String) = gitTask(refreshFilesAfterGit = true) { root ->
        gitService.checkout(root, name)
    }

    fun showGitDiff(path: String, cached: Boolean) = task {
        val current = project ?: return@task
        gitDiffPath = path
        gitDiffCached = cached
        gitDiff = withContext(Dispatchers.IO) { gitService.diff(store.root(current.id), path, cached) }
    }

    private fun gitTask(refreshFilesAfterGit: Boolean = false, operation: suspend (File) -> Unit) = task {
        val current = project ?: return@task
        saveAll()
        withContext(Dispatchers.IO) { operation(store.root(current.id)) }
        if (refreshFilesAfterGit) {
            entries = withContext(Dispatchers.IO) { store.entries(current.id) }
            for (doc in documents.toList()) {
                val disk = withContext(Dispatchers.IO) {
                    runCatching { store.read(current.id, doc.path) }.getOrNull()
                }
                if (disk != null) {
                    doc.value = TextFieldValue(disk)
                    doc.savedText = disk
                    doc.undo.clear()
                    doc.redo.clear()
                }
            }
            val existingPaths = withContext(Dispatchers.IO) {
                documents.mapNotNull { doc -> runCatching { store.read(current.id, doc.path) }.getOrNull()?.let { doc.path } }.toSet()
            }
            documents.removeAll { it.path !in existingPaths }
            if (activePath !in documents.map { it.path }) activePath = documents.firstOrNull()?.path
            persistTabs()
        }
        gitDiffPath = null
        refreshGitState()
    }

    private suspend fun refreshGitState() {
        val current = project ?: return
        gitSnapshot = withContext(Dispatchers.IO) { gitService.snapshot(store.root(current.id)) }
        gitRemote = if (!gitSnapshot.initialized) null else withContext(Dispatchers.IO) {
            runCatching { gitAccount.remote.remoteUrl(store.root(current.id)) }.getOrNull()
        }
        if (gitDiffPath != null) {
            val path = gitDiffPath!!
            val isStillAvailable = path in gitSnapshot.staged || path in gitSnapshot.modified || path in gitSnapshot.untracked
            if (isStillAvailable) {
                gitDiff = withContext(Dispatchers.IO) { gitService.diff(store.root(current.id), path, gitDiffCached) }
            } else {
                gitDiffPath = null
                gitDiff = ""
            }
        }
    }

    fun previewEntry(): String? = activePath?.takeIf { it.endsWith(".html", true) }
        ?: entries.firstOrNull { it.path == "index.html" }?.path
        ?: entries.firstOrNull { it.path.endsWith(".html", true) }?.path

    // ---- Remote Git with the person's own SSH key -----------------------------------------------------

    /** Runs a blocking remote operation; an unknown server is surfaced for the person to confirm. */
    private suspend fun <T> remoteCall(block: () -> T): T = try {
        withContext(Dispatchers.IO) { block() }
    } catch (error: GitAuthException) {
        error.unknownHost?.let { gitAccount.pendingHost = it }
        throw error
    }

    private fun remoteTask(refreshFilesAfterGit: Boolean = false, op: (File) -> String) =
        gitTask(refreshFilesAfterGit) { root ->
            val result = remoteCall { op(root) }
            withContext(Dispatchers.Main) { message = result }
        }

    fun fetchGit() = remoteTask { gitAccount.remote.fetch(it).message }

    fun pullGit() = remoteTask(refreshFilesAfterGit = true) { gitAccount.remote.pull(it).message }

    fun pushGit() = remoteTask { gitAccount.remote.push(it).message }

    fun testGitAccess() = remoteTask { root ->
        val url = gitAccount.remote.remoteUrl(root) ?: error("Set the origin remote URL first")
        gitAccount.remote.testAccess(url).message
    }

    /** GitHub SSH addresses are turned into HTTPS ones so the user's saved token is used. */
    fun updateGitRemote(url: String) = gitTask { root ->
        gitAccount.remote.setRemoteUrl(root, GitRemoteService.githubHttpsUrl(url) ?: url)
    }

    /** 0..100 while a clone runs, else null. */
    var cloneProgress by mutableStateOf<Int?>(null)
        private set

    fun cloneRepository(url: String, name: String, branch: String? = null) = task {
        val target = GitRemoteService.githubHttpsUrl(url) ?: url
        cloneProgress = 0
        try {
            val created = remoteCall {
                store.createFromClone(name) { folder ->
                    gitAccount.remote.clone(target, folder, branch = branch, progress = { cloneProgress = it })
                }
            }
            withContext(Dispatchers.IO) { gitService.ensureMetadataExcluded(store.root(created.id)) }
            projects = withContext(Dispatchers.IO) { store.projects() }
            githubOpen = false
            selectedRepo = null
            loadProject(created)
            message = "Cloned into ${created.name}"
        } finally { cloneProgress = null }
    }

    // ---- GitHub: the user's own access token, their repositories, clone ---------------------------

    var githubOpen by mutableStateOf(false)
    var githubRepos by mutableStateOf<List<GitHubRepo>>(emptyList())
        private set
    var githubLoading by mutableStateOf(false)
        private set
    var githubError by mutableStateOf<String?>(null)
        private set
    var githubProfile by mutableStateOf<dev.moavia.codriod.git.GitHubUser?>(null)
        private set
    /** Clone URL (normalised) of each project already on this phone, to show "Open" instead of "Clone". */
    var localRepos by mutableStateOf<Map<String, Project>>(emptyMap())
        private set
    var selectedRepo by mutableStateOf<GitHubRepo?>(null)
    var repoBranches by mutableStateOf<List<String>>(emptyList())
        private set
    var repoFiles by mutableStateOf<List<dev.moavia.codriod.git.GitHubEntry>?>(null)
        private set
    var repoReadme by mutableStateOf<String?>(null)
        private set
    var repoError by mutableStateOf<String?>(null)
        private set

    private fun repoKey(url: String) = url.trim().lowercase().removeSuffix("/").removeSuffix(".git")

    fun localProjectFor(repo: GitHubRepo): Project? = localRepos[repoKey(repo.cloneUrl)]

    private suspend fun refreshLocalRepos() {
        localRepos = withContext(Dispatchers.IO) {
            projects.mapNotNull { p ->
                runCatching { gitAccount.remote.remoteUrl(store.root(p.id)) }.getOrNull()
                    ?.let { url -> repoKey(GitRemoteService.githubHttpsUrl(url) ?: url) to p }
            }.toMap()
        }
    }

    /** Opens a repository's detail page and loads its branches, files and README. */
    fun openRepo(repo: GitHubRepo, branch: String = repo.defaultBranch) {
        if (selectedRepo?.fullName != repo.fullName) { repoBranches = emptyList() }
        selectedRepo = repo
        repoFiles = null; repoReadme = null; repoError = null
        viewModelScope.launch {
            try {
                if (repoBranches.isEmpty()) repoBranches = withContext(Dispatchers.IO) { gitAccount.githubBranches(repo.fullName) }
                repoFiles = withContext(Dispatchers.IO) { gitAccount.githubFiles(repo.fullName, branch) }
                repoReadme = withContext(Dispatchers.IO) { gitAccount.githubReadme(repo.fullName, branch) } ?: ""
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { repoError = error.message ?: "Could not load the repository" }
        }
    }

    fun showGitHub() {
        gitOpen = false
        previewOpen = false
        addonsOpen = false
        agentOpen = false
        githubOpen = true
        if (gitAccount.githubToken != null) {
            if (githubRepos.isEmpty()) loadGitHubRepos() else viewModelScope.launch { refreshLocalRepos() }
        }
    }

    fun loadGitHubRepos() {
        if (githubLoading) return
        viewModelScope.launch {
            githubLoading = true
            githubError = null
            try {
                githubProfile = withContext(Dispatchers.IO) { gitAccount.githubProfile() }
                githubRepos = withContext(Dispatchers.IO) { gitAccount.githubRepos() }
                refreshLocalRepos()
            }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { githubError = error.message ?: "Could not load repositories" }
            finally { githubLoading = false }
        }
    }

    /** Checks the token the user created on GitHub, saves it on this phone and lists their repositories. */
    fun connectGitHub(token: String) = task {
        val user = withContext(Dispatchers.IO) { gitAccount.saveGitHubToken(token) }
        message = "Connected to GitHub as @$user"
        githubRepos = emptyList()
        loadGitHubRepos()
    }

    fun disconnectGitHub() = task {
        withContext(Dispatchers.IO) { gitAccount.removeGitHubToken() }
        githubRepos = emptyList()
        githubError = null
        githubProfile = null
        selectedRepo = null
        message = "Disconnected. You can also delete the token at github.com/settings/tokens."
    }

    fun cloneGitHubRepo(repo: GitHubRepo, branch: String? = null, name: String = repo.name) =
        cloneRepository(repo.cloneUrl, name, branch?.takeIf { it != repo.defaultBranch })

    fun trustPendingHost() {
        gitAccount.trustPendingHost()
        message = "Server trusted. Try again."
    }

    /** "Take over": shows the browser on the agent's tab. */
    fun showAgentBrowser() = agentHost.showAgentBrowser()

    /** For the coding agent. Every call acts with the user's own token; it never leaves the Git layer. */
    val gitAccountBridge = object : GitAccountBridge {
        private fun projectRoot(): File = project?.let { store.root(it.id) } ?: error("Open a project first")

        override suspend fun status(): GitAccountStatus = withContext(Dispatchers.IO) {
            val root = project?.let { runCatching { store.root(it.id) }.getOrNull() }
            val snapshot = root?.let { runCatching { gitService.snapshot(it) }.getOrNull() }
            val remoteUrl = if (root != null && snapshot?.initialized == true) runCatching { gitAccount.remote.remoteUrl(root) }.getOrNull() else null
            GitAccountStatus(
                toolScan = GitRemoteService.detectTools().summary,
                keyStorage = gitAccount.keyStorage,
                keys = emptyList(),
                httpsToken = gitAccount.tokens.get("github.com")?.let { "connected" + (it.user?.let { u -> " as @$u" } ?: "") },
                gitInitialized = snapshot?.initialized == true,
                remoteUrl = remoteUrl,
                sshRemoteSuggestion = null,
                authorName = snapshot?.authorName?.ifBlank { null },
                authorEmail = snapshot?.authorEmail?.ifBlank { null })
        }

        /** Opens Codriod's GitHub screen and GitHub's token page; the user creates and pastes the token. */
        override suspend fun startGitHubLogin(keyName: String): String {
            withContext(Dispatchers.Main) {
                showGitHub()
                gitAccount.openUrl(GitAccountController.GITHUB_NEW_TOKEN_URL)
            }
            return "Opened GitHub's 'New personal access token' page and Codriod's GitHub screen."
        }

        override suspend fun verifyGitHub(): String = remoteCall { gitAccount.verifyGitHubToken() }

        override suspend fun configure(authorName: String?, authorEmail: String?, remoteUrl: String?, useSshRemote: Boolean): String {
            val root = projectRoot()
            val result = withContext(Dispatchers.IO) {
                require(gitService.snapshot(root).initialized) { "Initialize Git for this project first" }
                gitAccount.remote.setAuthor(root, authorName, authorEmail)
                remoteUrl?.let { gitAccount.remote.setRemoteUrl(root, GitRemoteService.githubHttpsUrl(it) ?: it) }
                "Git configured. Origin: ${gitAccount.remote.remoteUrl(root) ?: "not set"}"
            }
            refreshGitState()
            return result
        }

        override suspend fun testAccess(url: String?): String = remoteCall {
            val target = url ?: gitAccount.remote.remoteUrl(projectRoot()) ?: error("No URL given and origin is not set")
            gitAccount.remote.testAccess(target).message
        }

        override suspend fun sync(action: String): String {
            val root = projectRoot()
            withContext(Dispatchers.Main) { saveAll() }
            val result = remoteCall {
                when (action) {
                    "fetch" -> gitAccount.remote.fetch(root)
                    "pull" -> gitAccount.remote.pull(root)
                    "push" -> gitAccount.remote.push(root)
                    else -> error("Unknown action $action")
                }.message
            }
            if (action == "pull") withContext(Dispatchers.Main) { refreshFiles() }
            refreshGitState()
            return result
        }
    }

    override fun onCleared() {
        cliPanels.stopAll()
        agent.stop()
        agent.releaseVoice()
        terminal?.close()
        super.onCleared()
    }
}

fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
