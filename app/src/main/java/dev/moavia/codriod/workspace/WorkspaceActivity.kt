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
            WorkspaceTheme(model.themeMode) {
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
    var createDialog by remember { mutableStateOf(false) }
    var newEntry by remember { mutableStateOf<String?>(null) }
    var renameEntry by remember { mutableStateOf<ProjectEntry?>(null) }
    var deleteEntry by remember { mutableStateOf<ProjectEntry?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(model.message) {
        model.message?.let { snackbar.showSnackbar(it, withDismissAction = true); model.message = null }
    }
    BackHandler(model.project != null || model.previewOpen || model.terminalOpen || model.gitOpen) {
        when {
            model.gitOpen -> model.closeGit()
            model.previewOpen -> model.previewOpen = false
            model.terminalOpen -> model.terminalOpen = false
            else -> model.goHome()
        }
    }
    Scaffold(modifier = modifier, containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (model.project != null) WorkspaceNav(model,
                onFiles = { model.closeGit(); model.fileSheet = true },
                onSearch = { model.closeGit(); model.searchSheet = true },
                onGit = model::showGit,
                onComingSoon = { model.message = "Extensions are planned for a later phase." },
                onPreview = { model.showPreview() })
        }
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            when {
                model.project == null -> WorkspaceHome(
                    model = model,
                    onCreate = { createDialog = true },
                    onOpenFolder = { openFolderLauncher.launch(null) },
                    onOpenTerminal = onOpenOriginalTerminal
                )
                model.gitOpen -> GitPanel(model)
                model.previewOpen -> WorkspacePreview(model)
                else -> WorkspaceEditor(model)
            }
        }
    }
    if (createDialog) NewProjectDialog({ createDialog = false }) { name, template ->
        createDialog = false; model.createProject(name, template)
    }
    if (model.fileSheet && model.project != null)
        ProjectFilesSheet(model,
            onNewFile = { model.fileSheet = false; newEntry = "file" },
            onNewFolder = { model.fileSheet = false; newEntry = "folder" },
            onRename = { model.fileSheet = false; renameEntry = it },
            onDelete = { model.fileSheet = false; deleteEntry = it },
            onOpen = { model.openFile(it) },
            onDismiss = { model.fileSheet = false })
    if (model.searchSheet && model.project != null) ProjectSearchSheet(model) { model.searchSheet = false }
    newEntry?.let { kind -> WorkspaceNameDialog(
        title = if (kind == "folder") "New folder" else "New file",
        confirmText = "Create", onDismiss = { newEntry = null },
        onConfirm = { newEntry = null; model.createEntry(it, kind == "folder") }
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
fun WorkspaceNav(model: WorkspaceModel, onFiles: () -> Unit, onSearch: () -> Unit,
    onGit: () -> Unit, onComingSoon: () -> Unit, onPreview: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf(
                Triple("Files", ToolIcon.Folder, onFiles), Triple("Search", ToolIcon.Search, onSearch),
                Triple("Git", ToolIcon.Git, onGit), Triple("Preview", ToolIcon.Preview, onPreview),
                Triple("Add-ons", ToolIcon.Plus, onComingSoon)
            ).forEach { (label, icon, action) ->
                val active = (label == "Preview" && model.previewOpen) ||
                    (label == "Git" && model.gitOpen) ||
                    (label == "Files" && model.fileSheet) || (label == "Search" && model.searchSheet)
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
