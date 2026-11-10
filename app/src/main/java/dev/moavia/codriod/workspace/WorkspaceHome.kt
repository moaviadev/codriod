package dev.moavia.codriod.workspace

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.moavia.codriod.R
import java.util.Calendar

private val Blue = Color(0xFF007ACC)
private val Blue2 = Color(0xFF3794FF)
private val GreenTile = Color(0xFF2F8F5B)
private val AgentBrush = Brush.linearGradient(listOf(Color(0xFF7C4DFF), Color(0xFFC586C0)))

/** Home: continue the last project, four big actions, and all projects. */
@Composable
fun WorkspaceHome(
    model: WorkspaceModel,
    onCreate: () -> Unit,
    onOpenFolder: () -> Unit,
    onClone: () -> Unit,
    onOpenTerminal: () -> Unit
) {
    var newSheet by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (model.projects.isEmpty()) FirstRun(model, onNew = { newSheet = it ?: "Web" }, onOpenFolder, onClone, onOpenTerminal)
        else HomeWithProjects(model, onNew = { newSheet = "Web" }, onOpenFolder, onClone, onOpenTerminal)
    }
    newSheet?.let { template ->
        NewProjectSheet(template, onDismiss = { newSheet = null }, onGitHub = { newSheet = null; onClone() }) { name, chosen ->
            newSheet = null; model.createProject(name, chosen)
        }
    }
}

@Composable
private fun TopBar(model: WorkspaceModel, onOpenTerminal: () -> Unit, showSettings: Boolean = true) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.codriod_logo), "Codriod", Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)), contentScale = ContentScale.Fit)
        Spacer(Modifier.width(10.dp))
        Text("Codriod", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        RoundIcon(ToolIcon.Terminal, "Terminal", onOpenTerminal)
        if (showSettings) Box {
            Spacer(Modifier.width(8.dp))
            RoundIcon(ToolIcon.Settings, "Settings") { menu = true }
            DropdownMenu(menu, { menu = false }) {
                Text("THEME", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                listOf("Dark", "Light", "System").forEach { mode ->
                    DropdownMenuItem({ Text((if (model.themeMode == mode) "✓  " else "     ") + mode) }, { menu = false; model.updateTheme(mode) })
                }
            }
        }
    }
}

@Composable
private fun RoundIcon(icon: ToolIcon, description: String, onClick: () -> Unit) {
    Box(Modifier.padding(start = 8.dp).size(38.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) { ToolGlyph(icon, MaterialTheme.colorScheme.onSurface, Modifier.size(18.dp)) }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning 👋"
    in 12..16 -> "Good afternoon 👋"
    in 17..21 -> "Good evening 👋"
    else -> "Working late? 🌙"
}

@Composable
private fun HomeWithProjects(model: WorkspaceModel, onNew: () -> Unit, onOpenFolder: () -> Unit, onClone: () -> Unit, onOpenTerminal: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }
    var showAll by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<Project?>(null) }
    var delete by remember { mutableStateOf<Project?>(null) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val filtered = model.projects.filter { p ->
        p.name.contains(query.trim(), true) && when (filter) {
            "Phone" -> p.treeUri == null && p.template != "Git"
            "GitHub" -> p.template == "Git"
            "Folders" -> p.treeUri != null
            else -> true
        }
    }
    val shown = if (showAll || query.isNotBlank()) filtered else filtered.take(6)
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            item { TopBar(model, onOpenTerminal) }
            item {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
                    Text(greeting(), fontSize = 12.5.sp, color = muted)
                    Text("What will you build today?", fontSize = 25.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp)
                }
            }
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    ToolGlyph(ToolIcon.Search, muted, Modifier.size(17.dp))
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text("Search projects", color = muted, fontSize = 13.5.sp)
                        BasicTextField(query, { query = it }, singleLine = true, cursorBrush = SolidColor(Blue2),
                            textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = 13.5.sp),
                            modifier = Modifier.fillMaxWidth())
                    }
                    if (query.isNotEmpty()) Text("✕", color = muted, modifier = Modifier.clickable { query = "" }.padding(4.dp))
                }
            }
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionTile("New", ToolIcon.Plus, SolidColor(Blue), Modifier.weight(1f), onNew)
                    ActionTile("Open folder", ToolIcon.Folder, SolidColor(GreenTile), Modifier.weight(1f), onOpenFolder)
                    ActionTile("GitHub", ToolIcon.Git, SolidColor(Color(0xFF333333)), Modifier.weight(1f), onClone)
                    ActionTile("Codriod AI", ToolIcon.CodeBrackets, AgentBrush, Modifier.weight(1f)) { model.agentOpen = true }
                }
            }
            model.continueInfo?.takeIf { query.isBlank() }?.let { info ->
                item { SectionHeader("CONTINUE") }
                item { ContinueCard(info, onOpen = { model.openProject(info.project) }) }
            }
            item {
                SectionHeader("PROJECTS", trailing = if (filtered.size > 6 && query.isBlank()) (if (showAll) "Show less" else "See all") else null) { showAll = !showAll }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("All" to model.projects.size, "Phone" to null, "GitHub" to null, "Folders" to null).forEach { (label, count) ->
                        val on = filter == label
                        Text(if (count != null) "$label $count" else label, fontSize = 12.sp,
                            color = if (on) Color(0xFF9FD0FF) else muted,
                            modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) Color(0xFF0B3A5E) else MaterialTheme.colorScheme.surface)
                                .clickable { filter = label }.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
            }
            if (shown.isEmpty()) item {
                Text(if (query.isNotBlank()) "No projects match “$query”." else "No projects here yet.", Modifier.padding(20.dp), color = muted)
            }
            items(shown, key = { it.id }) { project ->
                ProjectRow(project, onClick = { model.openProject(project) }, onRename = { rename = project }, onDelete = { delete = project })
            }
        }
        ExtendedFloatingActionButton(onClick = onNew, containerColor = Blue, contentColor = Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp),
            icon = { ToolGlyph(ToolIcon.Plus, Color.White, Modifier.size(18.dp)) }, text = { Text("New project", fontWeight = FontWeight.SemiBold) })
    }
    rename?.let { p -> WorkspaceNameDialog("Rename project", "Rename", p.name, onDismiss = { rename = null }) { rename = null; model.renameProject(p, it) } }
    delete?.let { p ->
        AlertDialog(onDismissRequest = { delete = null },
            title = { Text("Delete “${p.name}”?") },
            text = { Text(if (p.treeUri != null) "Codriod forgets this folder. The files on your phone are not deleted."
                else "All files of this project are permanently deleted from this phone.") },
            confirmButton = { TextButton(onClick = { delete = null; model.deleteProject(p) }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("Cancel") } })
    }
}

@Composable
private fun ActionTile(label: String, icon: ToolIcon, brush: Brush, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick)
        .padding(top = 12.dp, bottom = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(brush), contentAlignment = Alignment.Center) {
            ToolGlyph(icon, Color.White, Modifier.size(20.dp))
        }
        Spacer(Modifier.height(7.dp))
        Text(label, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SectionHeader(title: String, trailing: String? = null, onTrailing: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 12.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (trailing != null) TextButton(onClick = onTrailing, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(trailing, fontSize = 12.sp, color = Blue2) }
    }
}

@Composable
private fun ContinueCard(info: WorkspaceModel.ContinueInfo, onOpen: () -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
        .background(Brush.linearGradient(listOf(Color(0xFF0D3A5C), Color(0xFF25234A))))
        .border(1.dp, Color(0xFF1D4F78), RoundedCornerShape(16.dp)).clickable(onClick = onOpen).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.size(54.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFF5F8FC)).padding(7.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.fillMaxWidth(.6f).height(5.dp).background(Blue, RoundedCornerShape(3.dp)))
            repeat(3) { i -> Box(Modifier.fillMaxWidth(listOf(1f, .7f, .5f)[i]).height(5.dp).background(Color(0xFFCFD8E3), RoundedCornerShape(3.dp))) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(info.project.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text((info.file?.substringAfterLast('/')?.let { "$it · " } ?: "") + "edited ${relativeTime(info.project.modified)}",
                color = Color(0xFFA9C3DD), fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            info.branch?.let { branch ->
                Text("● $branch · ${if (info.changes == 0) "clean" else "${info.changes} changes"}", color = Color(0xFF89D185), fontSize = 11.5.sp)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text("Open", color = Color(0xFF0B3556), fontWeight = FontWeight.Bold, fontSize = 12.5.sp,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White).clickable(onClick = onOpen).padding(horizontal = 14.dp, vertical = 8.dp))
    }
}

private fun projectColor(project: Project): Color = when {
    project.template == "Git" -> Color(0xFF3A3A3D)
    project.treeUri != null -> GreenTile
    project.template == "Shell" -> Color(0xFF8A6D2B)
    project.template == "Web" -> Color(0xFF0E639C)
    else -> Color(0xFF5A4FCF)
}

private fun initials(name: String): String {
    val words = name.split(Regex("[\\s_.-]+")).filter { it.isNotEmpty() }
    return when {
        words.size >= 2 -> "${words[0][0]}${words[1][0]}"
        name.length >= 2 -> name.take(2)
        else -> name.ifEmpty { "?" }
    }.lowercase()
}

@Composable
private fun ProjectRow(project: Project, onClick: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val tag = when {
        project.treeUri != null -> "Folder"
        project.template == "Git" -> "GitHub"
        else -> project.template
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 18.dp, end = 6.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(projectColor(project)), contentAlignment = Alignment.Center) {
            Text(initials(project.name), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(project.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tag, fontSize = 9.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
                Spacer(Modifier.width(7.dp))
                Text(relativeTime(project.modified), fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        Box {
            IconButton(onClick = { menu = true }) { ToolGlyph(ToolIcon.More, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(18.dp)) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Open") }, { menu = false; onClick() })
                DropdownMenuItem({ Text("Rename") }, { menu = false; onRename() })
                DropdownMenuItem({ Text("Delete", color = MaterialTheme.colorScheme.error) }, { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun FirstRun(model: WorkspaceModel, onNew: (String?) -> Unit, onOpenFolder: () -> Unit, onClone: () -> Unit, onOpenTerminal: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopBar(model, onOpenTerminal)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Spacer(Modifier.height(20.dp))
            Box(Modifier.size(170.dp, 124.dp)) {
                Column(Modifier.size(150.dp, 110.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Color(0xFF569CD6) to .55f, Color(0xFFCE9178) to .8f, Color(0xFF4EC9B0) to .4f, Color(0xFF3A3A3F) to .65f).forEach { (c, w) ->
                        Box(Modifier.fillMaxWidth(w).height(7.dp).background(c, RoundedCornerShape(4.dp)))
                    }
                }
                Box(Modifier.align(Alignment.BottomEnd).size(48.dp).shadow(10.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(Blue),
                    contentAlignment = Alignment.Center) { ToolGlyph(ToolIcon.Plus, Color.White, Modifier.size(22.dp)) }
            }
            Spacer(Modifier.height(18.dp))
            Text("Code. Build. Run. Anywhere.", fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text("Create your first project, open a folder from your phone, or bring your code from GitHub.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
            BigButton("New project", ToolIcon.Plus, primary = true) { onNew(null) }
            BigButton("Open folder", ToolIcon.Folder, primary = false, onOpenFolder)
            BigButton("Clone from GitHub", ToolIcon.Git, primary = false, onClone)
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("🌐 Website" to "Web", "⚡ Shell" to "Shell", "📄 Empty" to "Empty").forEach { (label, template) ->
                    Text(label, fontSize = 11.5.sp, modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.surface)
                        .clickable { onNew(template) }.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
        }
        Row(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, Color(0xFF33334A), RoundedCornerShape(16.dp)).clickable { model.agentOpen = true }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(AgentBrush), contentAlignment = Alignment.Center) {
                ToolGlyph(ToolIcon.CodeBrackets, Color.White, Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Codriod Agent", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("Add your AI key in Add-ons to build with AI.", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun BigButton(text: String, icon: ToolIcon, primary: Boolean, onClick: () -> Unit) {
    Row(Modifier.padding(bottom = 9.dp).fillMaxWidth().height(48.dp).clip(RoundedCornerShape(13.dp))
        .background(if (primary) Blue else MaterialTheme.colorScheme.surface)
        .border(if (primary) 0.dp else 1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(13.dp))
        .clickable(onClick = onClick), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        ToolGlyph(icon, if (primary) Color.White else MaterialTheme.colorScheme.onSurface, Modifier.size(18.dp))
        Spacer(Modifier.width(9.dp))
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = if (primary) Color.White else MaterialTheme.colorScheme.onSurface)
    }
}

/** Bottom sheet with big starter cards. "From GitHub" opens the GitHub screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewProjectSheet(initial: String, onDismiss: () -> Unit, onGitHub: () -> Unit, onCreate: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var template by remember { mutableStateOf(initial) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 26.dp)) {
            Text("New project", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text("Pick a starter. You can change everything later.", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
            OutlinedTextField(name, { name = it.take(64) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("my-new-site") }, shape = RoundedCornerShape(12.dp))
            Spacer(Modifier.height(14.dp))
            val cards = listOf(Triple("Web", "🌐 Website", "HTML, CSS, JS with live preview"), Triple("Shell", "⚡ Shell script", "Run with the phone's shell"),
                Triple("Empty", "📄 Empty", "Start from a blank folder"), Triple("GitHub", "🐙 From GitHub", "Clone one of your repos"))
            cards.chunked(2).forEach { row ->
                Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (key, title, sub) ->
                        val on = template == key
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                            .background(if (on) Color(0xFF0B3A5E) else MaterialTheme.colorScheme.background)
                            .border(if (on) 1.5.dp else 1.dp, if (on) Blue else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                            .clickable { if (key == "GitHub") onGitHub() else template = key }.padding(14.dp)) {
                            Text(title.substringBefore(' '), fontSize = 22.sp)
                            Text(title.substringAfter(' '), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp),
                                color = if (on) Color.White else MaterialTheme.colorScheme.onSurface)
                            Text(sub, fontSize = 11.5.sp, color = if (on) Color(0xFFA9C3DD) else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Button(onClick = { onCreate(name.trim(), template) }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(13.dp), colors = ButtonDefaults.buttonColors(containerColor = Blue)) {
                Text("Create project", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun relativeTime(timestamp: Long): String {
    val minutes = ((System.currentTimeMillis() - timestamp).coerceAtLeast(0) / 60_000)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        minutes < 48 * 60 -> "yesterday"
        else -> "${minutes / (24 * 60)} days ago"
    }
}
