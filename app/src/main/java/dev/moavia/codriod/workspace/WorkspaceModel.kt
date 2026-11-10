package dev.moavia.codriod.workspace

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import java.io.File

class EditorDocument(val path: String, initial: String) {
    var value by mutableStateOf(TextFieldValue(initial))
    var savedText by mutableStateOf(initial)
    val dirty get() = value.text != savedText
    val undo = ArrayDeque<TextFieldValue>()
    val redo = ArrayDeque<TextFieldValue>()
}

class WorkspaceModel(application: Application) : AndroidViewModel(application) {
    val store = ProjectStore(File(application.filesDir, "workspaces"), application)
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
    var previewOpen by mutableStateOf(false)
    var fileSheet by mutableStateOf(false)
    var searchSheet by mutableStateOf(false)
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
    var searchResults by mutableStateOf<List<SearchHit>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set
    private val saveJobs = mutableMapOf<String, Job>()
    private var searchJob: Job? = null
    private val gitService = ProjectGitService()

    init {
        task {
            projects = withContext(Dispatchers.IO) { store.projects() }
            val last = prefs.getString("last_project", null)
            projects.firstOrNull { it.id == last }?.let { loadProject(it) }
        }
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
        previewOpen = false
        gitOpen = false
        gitSnapshot = GitSnapshot()
        gitDiff = ""
        gitDiffPath = null
        persistTabs()
    }

    fun goHome() = task {
        saveAll()
        terminal?.close()
        terminal = null
        searchJob?.cancel()
        project = null
        documents.clear()
        activePath = null
        previewOpen = false
        terminalOpen = false
        gitOpen = false
        prefs.edit().remove("last_project").apply()
        projects = withContext(Dispatchers.IO) { store.projects() }
    }

    fun select(path: String) {
        activePath = path
        persistTabs()
    }

    fun openFile(path: String, line: Int? = null) = task {
        openDocument(path, line)
        fileSheet = false
        searchSheet = false
    }

    private suspend fun openDocument(path: String, line: Int? = null) {
        val current = project ?: return
        var doc = documents.firstOrNull { it.path == path }
        if (doc == null) {
            require(documents.size < 12) { "Close a tab before opening another (12 tab limit)" }
            doc = EditorDocument(path, withContext(Dispatchers.IO) { store.read(current.id, path) })
            documents.add(doc)
        }
        activePath = path
        if (line != null) {
            val offset = doc.value.text.lineSequence().take((line - 1).coerceAtLeast(0)).sumOf { it.length + 1 }
            doc.value = doc.value.copy(selection = TextRange(offset.coerceAtMost(doc.value.text.length)))
        }
        persistTabs()
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
            val replacement = EditorDocument(path, doc.value.text).also { it.value = doc.value }
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

    fun refreshFiles() = task {
        val current = project ?: return@task
        entries = withContext(Dispatchers.IO) { store.entries(current.id) }
        for (doc in documents.filterNot { it.dirty }) {
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
        if (terminal == null) terminal = WorkspaceTerminalController(getApplication(), store.root(current.id))
        terminalOpen = true
    }

    fun runActive() = task {
        val current = project ?: return@task
        val path = activePath ?: return@task
        saveAll()
        when (path.substringAfterLast('.', "").lowercase()) {
            "html", "htm", "css", "js" -> previewOpen = true
            "sh" -> {
                showTerminal()
                terminal?.execute("cd " + shellQuote(store.root(current.id).absolutePath) +
                    " && /system/bin/sh " + shellQuote("./$path") + "\n")
            }
            else -> message = "Run supports shell files (.sh) and static web preview. Other runtimes are not installed."
        }
    }

    fun showPreview() = task {
        saveAll()
        require(entries.any { !it.directory && it.path.endsWith(".html", true) }) {
            "Add an HTML file to use web preview"
        }
        gitOpen = false
        previewOpen = true
    }

    fun showGit() = task {
        saveAll()
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

    override fun onCleared() {
        terminal?.close()
        super.onCleared()
    }
}

fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
