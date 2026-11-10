package dev.moavia.codriod.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectFilesSheet(model: WorkspaceModel, onNewFile: () -> Unit, onNewFolder: () -> Unit,
    onRename: (ProjectEntry) -> Unit, onDelete: (ProjectEntry) -> Unit,
    onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    val project = model.project ?: return
    var collapsed by remember(project.id) { mutableStateOf<Set<String>>(emptySet()) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.82f)) {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(project.name, style = MaterialTheme.typography.titleMedium)
                    Text("${model.entries.size} files and folders",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                }
                WorkspaceIconButton(ToolIcon.Plus, "New file", onNewFile)
                TextButton(onClick = onNewFolder, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Folder") }
                WorkspaceIconButton(ToolIcon.Refresh, "Refresh files") { model.refreshFiles() }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                items(model.entries.filter { entry ->
                    val segments = entry.path.split('/')
                    val parents = (1 until segments.size).map { segments.take(it).joinToString("/") }
                    parents.none { it in collapsed }
                }, key = { it.path }) { entry ->
                    val depth = entry.path.count { it == '/' }
                    val name = entry.path.substringAfterLast('/')
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = (depth * 14).dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(if (entry.directory) if (entry.path in collapsed) "›" else "⌄" else " ",
                            Modifier.width(18.dp).clickable(enabled = entry.directory) {
                                collapsed = if (entry.path in collapsed) collapsed - entry.path else collapsed + entry.path
                            }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ToolGlyph(if (entry.directory) ToolIcon.Folder else ToolIcon.File,
                            if (entry.path == model.activePath) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(name, Modifier.weight(1f).clickable(enabled = !entry.directory) { onOpen(entry.path) }
                            .padding(start = 8.dp, top = 10.dp, bottom = 10.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (entry.path == model.activePath) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface, maxLines = 1)
                        WorkspaceIconButton(ToolIcon.Edit, "Rename $name") { onRename(entry) }
                        WorkspaceIconButton(ToolIcon.Delete, "Delete $name") { onDelete(entry) }
                    }
                }
                if (model.entries.isEmpty()) item {
                    Text("This project has no files yet.", Modifier.padding(18.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectSearchSheet(model: WorkspaceModel, onDismiss: () -> Unit) {
    var query by remember(model.project?.id) { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.82f).padding(horizontal = 16.dp)) {
            Text("Search in project", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = query, onValueChange = {
                query = it
                model.search(it)
            }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Find in project files") },
                leadingIcon = { ToolGlyph(ToolIcon.Search, MaterialTheme.colorScheme.onSurfaceVariant) })
            if (model.searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
            LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
                items(model.searchResults, key = { "${it.path}:${it.line}" }) { result ->
                    Column(Modifier.fillMaxWidth().clickable { model.openFile(result.path, result.line) }
                        .padding(vertical = 9.dp)) {
                        Text("${result.path}:${result.line}", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                        Text(result.text, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                if (!model.searching && query.isNotBlank() && model.searchResults.isEmpty())
                    item { Text("No matching lines.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
