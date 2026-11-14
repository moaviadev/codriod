package dev.moavia.codriod.workspace

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import dev.moavia.codriod.MainActivity

class WorkspaceActivity : ComponentActivity() {
    private val model: WorkspaceModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WorkspaceTheme(model.themeMode, model.extensions.activeTheme) {
                val darkTheme = !LocalCodeColors.current.dark
                SideEffect {
                    window.statusBarColor = android.graphics.Color.TRANSPARENT
                    window.navigationBarColor = android.graphics.Color.TRANSPARENT
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = darkTheme
                        isAppearanceLightNavigationBars = darkTheme
                    }
                }
                WorkspaceApp(model, { startActivity(Intent(this, MainActivity::class.java)) }, Modifier.fillMaxSize())
            }
        }
    }

    override fun onStop() {
        model.flushPending()
        super.onStop()
    }
}

@Composable
private fun WorkspaceApp(model: WorkspaceModel, onOpenOriginalTerminal: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val openFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            }
            model.openFolderUri(uri)
        }
    }
    var pendingUpload by remember { mutableStateOf<android.webkit.ValueCallback<Array<Uri>>?>(null) }
    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        pendingUpload?.onReceiveValue(uris.toTypedArray())
        pendingUpload = null
    }
    DisposableEffect(Unit) {
        model.browser.hostContext = context
        model.browser.fileChooser = { callback ->
            pendingUpload?.onReceiveValue(null)
            pendingUpload = callback
            runCatching { uploadLauncher.launch("*/*") }.onFailure { callback.onReceiveValue(null); pendingUpload = null }
        }
        onDispose { model.browser.fileChooser = null; model.browser.hostContext = null; model.browser.releaseViews() }
    }
    if (model.terminalFullscreen) {
        TerminalFullscreenPage(model, onBack = { model.terminalFullscreen = false })
        return
    }
    var createDialog by remember { mutableStateOf(false) }
    var newEntry by remember { mutableStateOf<String?>(null) }
    var newEntryParent by remember { mutableStateOf("") }
    var renameEntry by remember { mutableStateOf<ProjectEntry?>(null) }
    var deleteEntry by remember { mutableStateOf<ProjectEntry?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(model.message) {
        model.message?.let { snackbar.showSnackbar(it, withDismissAction = true); model.message = null }
    }
    BackHandler(model.project != null || model.previewOpen || model.terminalOpen || model.gitOpen ||
        model.addonsOpen || model.agentOpen || model.githubOpen || model.agentPanelOpen || model.linuxOpen ||
        model.javaOpen || model.androidOpen || model.extensionsOpen || model.guideOpen || model.cliPanels.openId != null) {
        when {
            model.cliPanels.openId != null -> model.cliPanels.openId = null
            model.guideOpen -> model.guideOpen = false
            model.linuxOpen -> model.linuxOpen = false
            model.javaOpen -> model.javaOpen = false
            model.androidOpen -> model.androidOpen = false
            model.extensionsOpen -> model.extensionsOpen = false
            model.agentPanelOpen -> if (model.agentSettingsOpen) model.agentSettingsOpen = false else model.agentPanelOpen = false
            model.githubOpen -> model.githubOpen = false
            model.agentOpen -> model.agentOpen = false
            model.addonsOpen -> model.addonsOpen = false
            model.gitOpen -> model.closeGit()
            model.previewOpen -> {
                val tab = model.browser.active
                when {
                    model.browser.fullscreen -> model.browser.fullscreen = false
                    model.browser.devOpen -> model.browser.devOpen = false
                    tab != null && tab.canGoBack -> model.browser.back(tab)
                    else -> model.previewOpen = false
                }
            }
            model.terminalOpen -> model.terminalOpen = false
            else -> model.goHome()
        }
    }
    Box(modifier) {
    AgentBrowserHost(model)
    Scaffold(modifier = Modifier.fillMaxSize(), containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (model.project != null && !(model.previewOpen && model.browser.fullscreen)) WorkspaceNav(model,
                onFiles = { model.closeGit(); model.fileSheet = true },
                onSearch = { model.closeGit(); model.searchSheet = true },
                onGit = model::showGit,
                onComingSoon = { model.showAddons() },
                onPreview = { model.showPreview() })
        }
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            when {
                model.guideOpen -> GettingStartedScreen(model, onBack = { model.guideOpen = false })
                model.agentOpen -> dev.moavia.codriod.agent.AgentScreen(model.agent, model.project?.name,
                    onBack = { model.agentOpen = false }, onTakeOver = model::showAgentBrowser)
                model.githubOpen -> GitHubScreen(model, onBack = { model.githubOpen = false })
                model.linuxOpen -> dev.moavia.codriod.linux.LinuxHubScreen(model.linux,
                    onBack = { model.linuxOpen = false },
                    onOpenCli = { id, login -> model.linuxOpen = false; model.addonsOpen = false; model.openCli(id, login) },
                    onOpenTerminal = { model.linuxOpen = false; model.addonsOpen = false; model.openUbuntuTerminal() },
                    onAskAgent = { text -> model.askAgent(text) })
                model.javaOpen -> dev.moavia.codriod.jdk.JavaKitScreen(model.javaKit,
                    onBack = { model.javaOpen = false },
                    onOpenLinux = { model.javaOpen = false; model.linuxOpen = true },
                    onOpenTerminal = { model.javaOpen = false; model.addonsOpen = false; model.openUbuntuTerminal() },
                    onAskAgent = { text -> model.askAgent(text) })
                model.androidOpen -> dev.moavia.codriod.linux.AndroidDevScreen(model.linux,
                    onBack = { model.androidOpen = false },
                    onOpenLinux = { model.androidOpen = false; model.linuxOpen = true },
                    onOpenTerminal = { model.androidOpen = false; model.addonsOpen = false; model.openUbuntuTerminal() })
                model.extensionsOpen -> dev.moavia.codriod.ext.ExtensionsScreen(model.extensions,
                    onBack = { model.extensionsOpen = false },
                    onOpenLinux = { model.extensionsOpen = false; model.linuxOpen = true },
                    onOpenTerminal = { model.extensionsOpen = false; model.addonsOpen = false; model.openUbuntuTerminal() },
                    onAskAgent = { text -> model.askAgent(text) })
                model.project == null -> WorkspaceHome(
                    model = model,
                    onCreate = { createDialog = true },
                    onOpenFolder = { openFolderLauncher.launch(null) },
                    onClone = model::showGitHub,
                    onOpenTerminal = onOpenOriginalTerminal,
                    onOpenGuide = { model.guideOpen = true }
                )
                model.addonsOpen -> dev.moavia.codriod.agent.AddonsScreen(model.agent,
                    onBack = { model.addonsOpen = false },
                    onOpenAgent = { model.agentOpen = true },
                    linuxStatus = when (model.linux.phase) {
                        dev.moavia.codriod.linux.LinuxPhase.Ready -> "Ubuntu \u00B7 ${model.linux.installed.size} CLIs"
                        dev.moavia.codriod.linux.LinuxPhase.Installing -> "Installing\u2026"
                        dev.moavia.codriod.linux.LinuxPhase.Unsupported -> "Not available"
                        else -> "Set up"
                    },
                    onOpenLinux = { model.linuxOpen = true },
                    javaStatus = when (model.linux.phase) {
                        dev.moavia.codriod.linux.LinuxPhase.Unsupported -> "Not available"
                        dev.moavia.codriod.linux.LinuxPhase.Installing -> "Installing\u2026"
                        else -> model.javaKit.summary()
                    },
                    onOpenJava = { model.javaOpen = true },
                    androidStatus = when {
                        model.linux.phase == dev.moavia.codriod.linux.LinuxPhase.Unsupported -> "Not available"
                        !model.linux.ready -> "Needs Ubuntu"
                        model.linux.androidSdk != null -> "Installed" + if (model.linux.adbConnected) " \u00B7 adb" else ""
                        else -> "Set up"
                    },
                    onOpenAndroid = if (model.linux.ready) {{ model.androidOpen = true }} else null,
                    extStatus = if (model.linux.phase == dev.moavia.codriod.linux.LinuxPhase.Unsupported) "Not available"
                        else model.extensions.summary(),
                    onOpenExtensions = { model.extensionsOpen = true })
                model.gitOpen -> GitPanel(model)
                model.previewOpen -> WorkspacePreview(model)
                else -> Box(Modifier.fillMaxSize()) {
                    WorkspaceEditor(model)
                    AgentEdgeHandle(model)
                }
            }
        }
    }
    AgentPanelOverlay(model)
    CliPanelOverlay(model)
    }
    HostTrustDialog(model)
    if (createDialog) NewProjectDialog({ createDialog = false }) { name, template ->
        createDialog = false; model.createProject(name, template)
    }
    if (model.fileSheet && model.project != null)
        ProjectFilesSheet(model,
            onNewFile = { parent -> model.fileSheet = false; newEntryParent = parent; newEntry = "file" },
            onNewFolder = { parent -> model.fileSheet = false; newEntryParent = parent; newEntry = "folder" },
            onRename = { model.fileSheet = false; renameEntry = it },
            onDelete = { model.fileSheet = false; deleteEntry = it },
            onOpen = { model.openFile(it) },
            onDismiss = { model.fileSheet = false })
    if (model.searchSheet && model.project != null) ProjectSearchSheet(model) { model.searchSheet = false }
    newEntry?.let { kind -> WorkspaceNameDialog(
        title = if (kind == "folder") "New folder" else "New file",
        confirmText = "Create", onDismiss = { newEntry = null; newEntryParent = "" },
        onConfirm = { name ->
            val parent = newEntryParent
            newEntry = null
            newEntryParent = ""
            model.createEntry(if (parent.isEmpty()) name else "$parent/$name", kind == "folder")
        }
    ) }
    renameEntry?.let { item -> WorkspaceNameDialog(
        title = "Rename ${item.path.substringAfterLast('/')}", confirmText = "Rename",
        initial = item.path.substringAfterLast('/'), onDismiss = { renameEntry = null },
        onConfirm = { value ->
            renameEntry = null
            val parent = item.path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
            model.renameEntry(item.path, parent + value)
        }
    ) }
    deleteEntry?.let { item -> AlertDialog(onDismissRequest = { deleteEntry = null },
        title = { Text("Delete ${item.path.substringAfterLast('/')}?") },
        text = { Text("This permanently removes ${item.path} from this project.") },
        confirmButton = { TextButton(onClick = { deleteEntry = null; model.deleteEntry(item.path) }) {
            Text("Delete", color = MaterialTheme.colorScheme.error)
        } },
        dismissButton = { TextButton(onClick = { deleteEntry = null }) { Text("Cancel") } }
    ) }
}

@Composable
private fun TerminalFullscreenPage(model: WorkspaceModel, onBack: () -> Unit) {
    LaunchedEffect(model.project?.id) {
        if (model.terminal == null) model.showTerminal()
    }
    BackHandler { onBack() }
    Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        color = MaterialTheme.colorScheme.background) {
        model.terminal?.let { controller ->
            WorkspaceTerminal(
                controller = controller,
                modifier = Modifier.fillMaxSize(),
                fullscreen = true,
                projectName = model.project?.name,
                onBack = onBack
            )
        }
    }
}

@Composable
fun WorkspaceNav(model: WorkspaceModel, onFiles: () -> Unit, onSearch: () -> Unit,
    onGit: () -> Unit, onComingSoon: () -> Unit, onPreview: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf(
                Triple("Files", ToolIcon.Folder, onFiles), Triple("Search", ToolIcon.Search, onSearch),
                Triple("Git", ToolIcon.Git, onGit), Triple("Browser", ToolIcon.Globe, onPreview),
                Triple("Add-ons", ToolIcon.Plus, onComingSoon)
            ).forEach { (label, icon, action) ->
                val active = (label == "Browser" && model.previewOpen) ||
                    (label == "Git" && model.gitOpen) ||
                    (label == "Files" && model.fileSheet) || (label == "Search" && model.searchSheet) ||
                    (label == "Add-ons" && (model.addonsOpen || model.agentOpen))
                TextButton(onClick = action, modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentPadding = PaddingValues(vertical = 3.dp, horizontal = 2.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor =
                        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)) {
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        ToolGlyph(icon, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun NewProjectDialog(onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var template by remember { mutableStateOf("Web") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Create project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it.take(64) }, singleLine = true,
                    label = { Text("Project name") }, placeholder = { Text("my-first-project") })
                Text("STARTER", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("Web", "Shell", "Empty").forEach { FilterChip(
                        selected = template == it, onClick = { template = it }, label = { Text(it) })
                    }
                }
                Text(when (template) {
                    "Web" -> "HTML, CSS and JavaScript. Preview runs local project files without installing Node.js."
                    "Shell" -> "A runnable Android /system/bin/sh script, not a full Linux distribution."
                    else -> "An empty local workspace with a README."
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onCreate(name, template) },
            enabled = name.isNotBlank()) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun WorkspaceNameDialog(title: String, confirmText: String, initial: String = "",
    onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember(title, initial) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(value, { value = it }, singleLine = true, label = { Text("Name or relative path") }) },
        confirmButton = { TextButton(onClick = { if (value.isNotBlank()) onConfirm(value.trim()) },
            enabled = value.isNotBlank()) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
