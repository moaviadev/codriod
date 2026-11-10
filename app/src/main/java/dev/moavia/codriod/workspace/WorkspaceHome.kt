package dev.moavia.codriod.workspace

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.moavia.codriod.R

@Composable
fun WorkspaceHome(
    model: WorkspaceModel,
    onCreate: () -> Unit,
    onOpenFolder: () -> Unit,
    onClone: () -> Unit,
    onOpenTerminal: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }
    var exploreOpen by remember { mutableStateOf(false) }
    val matches = model.projects.filter { project ->
        val matchesQuery = project.name.contains(query, true)
        val matchesFilter = when (filter) {
            "Folders" -> project.treeUri != null
            "This phone" -> project.treeUri == null
            "SSH servers" -> false
            else -> true
        }
        matchesQuery && matchesFilter
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Image(painter = painterResource(R.drawable.codriod_logo), contentDescription = "Codriod",
                    modifier = Modifier.size(24.dp), contentScale = ContentScale.Fit)
                Spacer(Modifier.width(7.dp))
                Text("CODRIOD  /  HOME", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onOpenTerminal, contentPadding = PaddingValues(horizontal = 8.dp)) {
                ToolGlyph(ToolIcon.Terminal, MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(5.dp)); Text("Terminal", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = model::cycleTheme, contentPadding = PaddingValues(horizontal = 8.dp)) {
                ToolGlyph(ToolIcon.Theme, MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp)); Text(model.themeMode, style = MaterialTheme.typography.bodySmall)
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text("Your code,\nright here.", style = MaterialTheme.typography.titleLarge, lineHeight = 31.sp)
            Spacer(Modifier.height(5.dp))
            Text("A focused workspace for the ideas you want to build.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("PROJECTS", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            listOf("All", "Folders", "This phone", "SSH servers").forEach { label ->
                val name = when (label) {
                    "This phone" -> "Phone"
                    "Folders" -> "Folders"
                    "SSH servers" -> "SSH"
                    else -> "All"
                }
                FilterChip(selected = filter == label, onClick = { filter = label },
                    label = { Text(name, fontSize = 11.sp, maxLines = 1) },
                    modifier = Modifier.padding(start = 4.dp))
            }
        }
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp), singleLine = true, placeholder = { Text("Search projects") },
            leadingIcon = { ToolGlyph(ToolIcon.Search, MaterialTheme.colorScheme.onSurfaceVariant) },
            textStyle = MaterialTheme.typography.bodyMedium)
        if (model.projects.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(28.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.Start) {
                Text("A new workspace\nstarts with an idea.",
                    style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(10.dp))
                Text("Create a new project or open an existing folder from your phone storage to browse and edit code.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onCreate, modifier = Modifier.height(44.dp)) {
                        ToolGlyph(ToolIcon.Plus, MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(8.dp)); Text("New project")
                    }
                    OutlinedButton(onClick = onOpenFolder, modifier = Modifier.height(44.dp)) {
                        ToolGlyph(ToolIcon.Folder, MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp)); Text("Open folder")
                    }
                }
                TextButton(onClick = onClone, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    ToolGlyph(ToolIcon.Git, MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp)); Text("Clone a Git repository")
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
                items(matches, key = { it.id }) { project ->
                    ProjectRow(project, onClick = { model.openProject(project) })
                }
                if (matches.isEmpty()) item {
                    Text(if (filter == "SSH servers") "SSH workspaces are planned for a later phase."
                        else "No projects match “$query”.",
                        Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 42.dp).clickable { exploreOpen = !exploreOpen }
            .padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (exploreOpen) "⌄" else "›", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text("Explore", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text("STARTER · TIPS", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (exploreOpen) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().clickable(onClick = onCreate).padding(horizontal = 20.dp, vertical = 12.dp)) {
                Column {
                    Text("Start with a Web project", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Text("A tiny HTML, CSS and JavaScript starter you can preview on this phone.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                Text("Tip  ·  Save locally as you type. Run .sh scripts with the Android system shell.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        Surface(color = MaterialTheme.colorScheme.background, tonalElevation = 1.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onCreate, modifier = Modifier.weight(1f).height(44.dp),
                        contentPadding = PaddingValues(8.dp)) {
                        ToolGlyph(ToolIcon.Plus, MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(6.dp)); Text("New project")
                    }
                    OutlinedButton(onClick = onOpenFolder, modifier = Modifier.weight(1f).height(44.dp),
                        contentPadding = PaddingValues(8.dp)) {
                        ToolGlyph(ToolIcon.Folder, MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp)); Text("Open folder")
                    }
                    OutlinedButton(onClick = onClone, modifier = Modifier.weight(0.8f).height(44.dp),
                        contentPadding = PaddingValues(8.dp)) {
                        ToolGlyph(ToolIcon.Git, MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp)); Text("Clone")
                    }
                }
                Spacer(Modifier.height(5.dp))
                Text("Create in private storage, open a device folder or clone a Git repository.",
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun ProjectRow(project: Project, onClick: () -> Unit) {
    val isFolder = project.treeUri != null
    Column {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            ToolGlyph(ToolIcon.Folder, if (isFolder) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(project.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    if (isFolder) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.padding(horizontal = 2.dp)
                        ) {
                            Text(
                                "STORAGE",
                                fontSize = 9.sp,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                val typeLabel = if (isFolder) "External folder" else "${project.template} · Local"
                Text("$typeLabel · ${relativeTime(project.modified)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1)
            }
            ToolGlyph(ToolIcon.Chevron, MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    }
}

private fun relativeTime(timestamp: Long): String {
    val minutes = ((System.currentTimeMillis() - timestamp).coerceAtLeast(0) / 60_000)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} hr ago"
        else -> "${minutes / (24 * 60)} days ago"
    }
}
