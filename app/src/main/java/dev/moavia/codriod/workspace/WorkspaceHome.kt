package dev.moavia.codriod.workspace

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.moavia.codriod.R
import java.util.Calendar

@Immutable
internal data class HomePalette(
    val dark: Boolean,
    val base: Color,
    val baseBottom: Color,
    val glass: Color,
    val glassStrong: Color,
    val border: Color,
    val text: Color,
    val muted: Color,
    val primary: Color,
    val primary2: Color,
    val accent: Color,
    val success: Color,
    val shadow: Color
)

private val LightHomePalette = HomePalette(
    dark = false,
    base = Color(0xFFF8FBFF),
    baseBottom = Color(0xFFF2EFFF),
    glass = Color(0xD9FFFFFF),
    glassStrong = Color(0xF5FFFFFF),
    border = Color(0xFFDCE6F5),
    text = Color(0xFF101828),
    muted = Color(0xFF667085),
    primary = Color(0xFF4F7DFF),
    primary2 = Color(0xFF8B5CF6),
    accent = Color(0xFF22B8CF),
    success = Color(0xFF16A34A),
    shadow = Color(0x336B7A99)
)

private val DarkHomePalette = HomePalette(
    dark = true,
    base = Color(0xFF0B1020),
    baseBottom = Color(0xFF120F24),
    glass = Color(0xD9151D2E),
    glassStrong = Color(0xF51B2438),
    border = Color(0x1FFFFFFF),
    text = Color(0xFFF4F7FF),
    muted = Color(0xFF9AA7BD),
    primary = Color(0xFF6C8DFF),
    primary2 = Color(0xFFA78BFA),
    accent = Color(0xFF22D3EE),
    success = Color(0xFF4ADE80),
    shadow = Color(0x66000000)
)

internal val LocalHomePalette = staticCompositionLocalOf { LightHomePalette }
private val GreenTile = Color(0xFF2F8F5B)

@Composable
fun WorkspaceHome(
    model: WorkspaceModel,
    onCreate: () -> Unit,
    onOpenFolder: () -> Unit,
    onClone: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenGuide: () -> Unit
) {
    var newProjectInitial by remember { mutableStateOf<String?>(null) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) model.refreshStorage()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val palette = if (LocalCodeColors.current.dark) DarkHomePalette else LightHomePalette
    CompositionLocalProvider(LocalHomePalette provides palette) {
        Box(Modifier.fillMaxSize()) {
            HomeBackdrop()
            val initial = newProjectInitial
            if (initial != null) {
                NewProjectScreen(
                    model = model,
                    initial = initial,
                    onBack = { newProjectInitial = null },
                    onOpenTerminal = onOpenTerminal,
                    onClone = { newProjectInitial = null; onClone() },
                    onCreate = { name, template -> newProjectInitial = null; model.createProject(name, template) }
                )
            } else {
                if (model.projects.isEmpty()) FirstRun(model, onNew = { newProjectInitial = it ?: "Web" }, onOpenFolder, onClone, onOpenTerminal, onOpenGuide)
                else HomeWithProjects(model, onNew = { newProjectInitial = "Web" }, onOpenFolder, onClone, onOpenTerminal, onOpenGuide)
            }
        }
    }
}

@Composable
internal fun HomeBackdrop() {
    val p = LocalHomePalette.current
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(p.base, p.baseBottom)))) {
        Canvas(Modifier.fillMaxSize()) {
            val blueCenter = Offset(size.width * 0.08f, size.height * 0.03f)
            val blueRadius = size.width * 0.92f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(p.primary.copy(alpha = if (p.dark) 0.28f else 0.20f), Color.Transparent),
                    center = blueCenter,
                    radius = blueRadius
                ),
                radius = blueRadius,
                center = blueCenter
            )
            val purpleCenter = Offset(size.width * 0.96f, size.height * 0.30f)
            val purpleRadius = size.width * 0.82f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(p.primary2.copy(alpha = if (p.dark) 0.24f else 0.17f), Color.Transparent),
                    center = purpleCenter,
                    radius = purpleRadius
                ),
                radius = purpleRadius,
                center = purpleCenter
            )
            val wave = Path().apply {
                moveTo(0f, size.height * 0.88f)
                cubicTo(size.width * 0.26f, size.height * 0.80f, size.width * 0.58f, size.height * 0.98f, size.width, size.height * 0.83f)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(
                wave,
                Brush.verticalGradient(
                    listOf(
                        Color.Transparent,
                        p.primary.copy(alpha = if (p.dark) 0.10f else 0.05f),
                        p.primary2.copy(alpha = if (p.dark) 0.14f else 0.08f)
                    )
                )
            )
        }
    }
}

internal fun Modifier.glass(p: HomePalette, shape: Shape, elevated: Boolean = true): Modifier {
    val base = if (elevated) shadow(18.dp, shape, ambientColor = p.shadow, spotColor = p.shadow) else this
    return base.clip(shape).background(p.glass).border(1.dp, p.border, shape)
}

@Composable
internal fun TopBar(model: WorkspaceModel, onOpenTerminal: () -> Unit, showSettings: Boolean = true, onBack: (() -> Unit)? = null) {
    val p = LocalHomePalette.current
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Row(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                RoundIcon(p, ToolIcon.Back, "Back to Home", onBack)
                Spacer(Modifier.width(10.dp))
            }
            Box(Modifier.size(42.dp).glass(p, RoundedCornerShape(14.dp), elevated = false)
                .then(if (onBack != null) Modifier.clickable(onClick = onBack) else Modifier), contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.codriod_logo), "Codriod", Modifier.fillMaxSize().padding(3.dp).clip(RoundedCornerShape(11.dp)),
                    contentScale = ContentScale.Fit)
            }
            Spacer(Modifier.width(11.dp))
            Text("Codriod", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = p.text,
                modifier = Modifier.weight(1f).then(if (onBack != null) Modifier.clickable(onClick = onBack) else Modifier))
            RoundIcon(p, ToolIcon.Terminal, "Terminal", onOpenTerminal)
            if (showSettings) Box {
                Spacer(Modifier.width(8.dp))
                RoundIcon(p, ToolIcon.Theme, "Theme") { menu = true }
                DropdownMenu(menu, { menu = false }) {
                    Text("THEME", fontSize = 11.sp, color = p.muted, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                    listOf("Dark", "Light", "System").forEach { mode ->
                        DropdownMenuItem({ Text((if (model.themeMode == mode) "✓  " else "     ") + mode) }, { menu = false; model.updateTheme(mode) })
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundIcon(p: HomePalette, icon: ToolIcon, description: String, onClick: () -> Unit) {
    Box(Modifier.size(42.dp).glass(p, CircleShape, elevated = false).clickable(onClick = onClick)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        ToolGlyph(icon, p.text, Modifier.size(19.dp))
    }
}

@Composable
fun StorageBanner(model: WorkspaceModel) {
    val p = LocalHomePalette.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val legacy = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { model.refreshStorage() }
    if (model.storageGranted && model.privateProjectCount == 0) return
    Column(Modifier.padding(horizontal = 18.dp, vertical = 6.dp).fillMaxWidth().glass(p, RoundedCornerShape(18.dp)).padding(16.dp)) {
        if (!model.storageGranted) {
            Text("📁  Save projects in your phone's storage", color = p.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text("Projects will be normal folders in Internal storage/Codriod, next to Downloads, so file managers, other apps " +
                "and your computer can open them. Opened folders are edited in place.", color = p.muted, fontSize = 12.sp,
                lineHeight = 17.sp, modifier = Modifier.padding(top = 4.dp))
            Button(onClick = {
                if (StorageAccess.usesSettingsPage) runCatching { context.startActivity(StorageAccess.settingsIntent(context)) }
                    .onFailure { runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } }
                else legacy.launch(StorageAccess.legacyPermissions)
            }, modifier = Modifier.padding(top = 10.dp), shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = p.primary, contentColor = Color.White)) {
                Text(if (StorageAccess.usesSettingsPage) "Allow “All files access”" else "Allow storage")
            }
            if (StorageAccess.usesSettingsPage) Text("In the page that opens, turn on Codriod, then come back.", color = p.muted,
                fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        } else {
            Text("📦  Move ${model.privateProjectCount} older project${if (model.privateProjectCount == 1) "" else "s"} to your phone's storage?",
                color = p.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text("They are still in Codriod's private storage. New projects already go to Internal storage/Codriod.",
                color = p.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            Button(onClick = { model.moveProjectsToStorage() }, enabled = !model.busy, modifier = Modifier.padding(top = 10.dp),
                shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = p.primary, contentColor = Color.White)) {
                Text("Move to Internal storage/Codriod")
            }
        }
    }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning 👋"
    in 12..16 -> "Good afternoon 👋"
    in 17..21 -> "Good evening 👋"
    else -> "Working late? 🌙"
}

@Composable
private fun HomeWithProjects(model: WorkspaceModel, onNew: () -> Unit, onOpenFolder: () -> Unit, onClone: () -> Unit, onOpenTerminal: () -> Unit, onOpenGuide: () -> Unit) {
    val p = LocalHomePalette.current
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }
    var showAll by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<Project?>(null) }
    var delete by remember { mutableStateOf<Project?>(null) }
    val filtered = model.projects.filter { project ->
        project.name.contains(query.trim(), true) && when (filter) {
            "Phone" -> project.treeUri == null && project.template != "Git"
            "GitHub" -> project.template == "Git"
            "Folders" -> project.treeUri != null
            else -> true
        }
    }
    val shown = if (showAll || query.isNotBlank()) filtered else filtered.take(6)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val contentWidth = if (maxWidth > 720.dp) 720.dp else maxWidth
        Box(Modifier.width(contentWidth).fillMaxHeight().align(Alignment.TopCenter)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 110.dp)) {
                item { TopBar(model, onOpenTerminal) }
                item {
                    Column(Modifier.padding(horizontal = 22.dp, vertical = 10.dp)) {
                        Text(greeting(), fontSize = 12.5.sp, color = p.muted, fontWeight = FontWeight.Medium)
                        Text("What will you build today?", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 34.sp, color = p.text)
                    }
                }
                item {
                    Row(Modifier.padding(horizontal = 18.dp, vertical = 6.dp).fillMaxWidth().height(50.dp)
                        .glass(p, RoundedCornerShape(16.dp), elevated = false).padding(horizontal = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                        ToolGlyph(ToolIcon.Search, p.muted, Modifier.size(17.dp))
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.weight(1f)) {
                            if (query.isEmpty()) Text("Search projects", color = p.muted, fontSize = 13.5.sp)
                            BasicTextField(query, { query = it }, singleLine = true, cursorBrush = SolidColor(p.primary),
                                textStyle = LocalTextStyle.current.copy(color = p.text, fontSize = 13.5.sp),
                                modifier = Modifier.fillMaxWidth())
                        }
                        if (query.isNotEmpty()) Text("✕", color = p.muted, modifier = Modifier.clickable { query = "" }.padding(4.dp))
                    }
                }
                item { StorageBanner(model) }
                item {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionTile("New", ToolIcon.Plus, Brush.linearGradient(listOf(p.primary, p.primary2)), Modifier.weight(1f), p, onNew)
                        ActionTile("Open folder", ToolIcon.Folder, SolidColor(p.success), Modifier.weight(1f), p, onOpenFolder)
                        ActionTile("GitHub", ToolIcon.Git, SolidColor(if (p.dark) Color(0xFF30363D) else Color(0xFF24292F)), Modifier.weight(1f), p, onClone)
                        ActionTile("Codriod AI", ToolIcon.CodeBrackets, Brush.linearGradient(listOf(p.primary2, p.accent)), Modifier.weight(1f), p) { model.agentOpen = true }
                    }
                }
                item {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 2.dp).fillMaxWidth().glass(p, RoundedCornerShape(18.dp), elevated = false)
                            .clickable(onClick = onOpenGuide).padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(Brush.linearGradient(listOf(p.primary, p.primary2))), contentAlignment = Alignment.Center) {
                            ToolGlyph(ToolIcon.Book, Color.White, Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Getting Started & Workflow", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("Overview, workflows, tutorials, and ChatGPT help", color = p.muted, fontSize = 11.5.sp)
                        }
                        ToolGlyph(ToolIcon.Chevron, p.muted, Modifier.size(16.dp))
                    }
                }
                model.continueInfo?.takeIf { query.isBlank() }?.let { info ->
                    item { SectionHeader("CONTINUE", p) }
                    item { ContinueCard(info, p, onOpen = { model.openProject(info.project) }) }
                }
                item {
                    SectionHeader("PROJECTS", p, trailing = if (filtered.size > 6 && query.isBlank()) (if (showAll) "Show less" else "See all") else null) { showAll = !showAll }
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("All" to model.projects.size, "Phone" to null, "GitHub" to null, "Folders" to null).forEach { (label, count) ->
                            val on = filter == label
                            val shape = RoundedCornerShape(14.dp)
                            Text(if (count != null) "$label $count" else label, fontSize = 12.sp,
                                color = if (on) Color.White else p.muted,
                                modifier = Modifier.clip(shape)
                                    .background(if (on) Brush.horizontalGradient(listOf(p.primary, p.primary2)) else SolidColor(p.glass))
                                    .border(1.dp, if (on) Color.Transparent else p.border, shape)
                                    .clickable { filter = label }.padding(horizontal = 12.dp, vertical = 6.dp))
                        }
                    }
                }
                if (shown.isEmpty()) item {
                    Text(if (query.isNotBlank()) "No projects match “$query”." else "No projects here yet.",
                        Modifier.padding(20.dp), color = p.muted)
                }
                items(shown, key = { it.id }) { project ->
                    ProjectRow(project, p, onClick = { model.openProject(project) }, onRename = { rename = project }, onDelete = { delete = project })
                }
            }
            GradientFab(p, onNew, Modifier.align(Alignment.BottomEnd).padding(18.dp))
        }
    }
    rename?.let { project -> WorkspaceNameDialog("Rename project", "Rename", project.name, onDismiss = { rename = null }) { rename = null; model.renameProject(project, it) } }
    delete?.let { project ->
        AlertDialog(onDismissRequest = { delete = null },
            title = { Text("Delete “${project.name}”?") },
            text = { Text(if (project.treeUri != null) "Codriod forgets this folder. The files on your phone are not deleted."
                else "All files of this project are permanently deleted from this phone" +
                    (project.path?.let { " (${StorageAccess.displayPath(java.io.File(it))})" } ?: "") + ".") },
            confirmButton = { TextButton(onClick = { delete = null; model.deleteProject(project) }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("Cancel") } })
    }
}

@Composable
private fun ActionTile(label: String, icon: ToolIcon, brush: Brush, modifier: Modifier, p: HomePalette, onClick: () -> Unit) {
    Column(modifier.heightIn(min = 96.dp).glass(p, RoundedCornerShape(18.dp), elevated = false).clickable(onClick = onClick)
        .padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(brush), contentAlignment = Alignment.Center) {
            ToolGlyph(icon, Color.White, Modifier.size(19.dp))
        }
        Spacer(Modifier.height(7.dp))
        Text(label, color = p.text, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SectionHeader(title: String, p: HomePalette, trailing: String? = null, onTrailing: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 10.dp, top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 12.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold,
            color = p.muted, modifier = Modifier.weight(1f))
        if (trailing != null) TextButton(onClick = onTrailing, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text(trailing, fontSize = 12.sp, color = p.primary)
        }
    }
}

@Composable
private fun ContinueCard(info: WorkspaceModel.ContinueInfo, p: HomePalette, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().shadow(16.dp, shape, ambientColor = p.shadow, spotColor = p.shadow)
        .clip(shape).background(Brush.linearGradient(listOf(p.primary.copy(alpha = 0.14f), p.glassStrong, p.primary2.copy(alpha = 0.12f))))
        .border(1.dp, p.border, shape).clickable(onClick = onOpen).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.size(54.dp).clip(RoundedCornerShape(14.dp)).background(p.glassStrong).padding(7.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.fillMaxWidth(.6f).height(5.dp).background(p.primary, RoundedCornerShape(3.dp)))
            repeat(3) { i ->
                Box(Modifier.fillMaxWidth(listOf(1f, .7f, .5f)[i]).height(5.dp).background(p.muted.copy(alpha = 0.35f), RoundedCornerShape(3.dp)))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(info.project.name, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text((info.file?.substringAfterLast('/')?.let { "$it · " } ?: "") + "edited ${relativeTime(info.project.modified)}",
                color = p.muted, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            info.branch?.let { branch ->
                Text("● $branch · ${if (info.changes == 0) "clean" else "${info.changes} changes"}", color = p.success, fontSize = 11.5.sp)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text("Open", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.5.sp,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Brush.horizontalGradient(listOf(p.primary, p.primary2)))
                .clickable(onClick = onOpen).padding(horizontal = 14.dp, vertical = 8.dp))
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
private fun ProjectRow(project: Project, p: HomePalette, onClick: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val tag = when {
        project.treeUri != null -> "Folder"
        project.template == "Git" -> "GitHub"
        else -> project.template
    }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().glass(p, RoundedCornerShape(18.dp), elevated = false)
        .clickable(onClick = onClick).padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(projectColor(project), projectColor(project).copy(alpha = 0.72f)))),
            contentAlignment = Alignment.Center) {
            Text(initials(project.name), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(project.name, color = p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tag, fontSize = 9.5.sp, color = p.muted,
                    modifier = Modifier.border(1.dp, p.border, RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
                Spacer(Modifier.width(7.dp))
                Text(relativeTime(project.modified), fontSize = 11.5.sp, color = p.muted, maxLines = 1)
            }
        }
        Box {
            IconButton(onClick = { menu = true }) { ToolGlyph(ToolIcon.More, p.muted, Modifier.size(18.dp)) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Open") }, { menu = false; onClick() })
                DropdownMenuItem({ Text("Rename") }, { menu = false; onRename() })
                DropdownMenuItem({ Text("Delete", color = MaterialTheme.colorScheme.error) }, { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun FirstRun(model: WorkspaceModel, onNew: (String?) -> Unit, onOpenFolder: () -> Unit, onClone: () -> Unit, onOpenTerminal: () -> Unit, onOpenGuide: () -> Unit) {
    val p = LocalHomePalette.current
    Column(Modifier.fillMaxSize()) {
        TopBar(model, onOpenTerminal)
        StorageBanner(model)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(top = 10.dp, bottom = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                CodingIllustration(p)
                Spacer(Modifier.height(18.dp))
                Text("Code. Build. Run. Anywhere.", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center,
                    lineHeight = 36.sp, color = p.text)
                Text("Create your first project, open a folder from your phone, or bring your code from GitHub.",
                    fontSize = 13.5.sp, color = p.muted, textAlign = TextAlign.Center, lineHeight = 20.sp,
                    modifier = Modifier.padding(top = 10.dp, bottom = 22.dp))
                GradientButton("New project", ToolIcon.Plus, p) { onNew(null) }
                Spacer(Modifier.height(10.dp))
                GlassActionButton("Open folder", ToolIcon.Folder, p, p.success, onOpenFolder)
                Spacer(Modifier.height(10.dp))
                GlassActionButton("Clone from GitHub", ToolIcon.Git, p, p.primary, onClone)
                Spacer(Modifier.height(10.dp))
                GlassActionButton("Getting Started & Workflow", ToolIcon.Book, p, p.primary2, onOpenGuide)
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuickAction("Website", ToolIcon.Globe, p, p.primary, Modifier.weight(1f)) { onNew("Web") }
                    QuickAction("Shell", ToolIcon.Terminal, p, p.accent, Modifier.weight(1f)) { onNew("Shell") }
                    QuickAction("Empty", ToolIcon.File, p, p.primary2, Modifier.weight(1f)) { onNew("Empty") }
                }
            }
        }
        AgentCard(p) { model.agentOpen = true }
    }
}

@Composable
internal fun CodingIllustration(p: HomePalette) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(172.dp), contentAlignment = Alignment.Center) {
        val cardWidth = minOf(maxWidth * 0.82f, 260.dp)
        Box(Modifier.size(150.dp).background(
            Brush.radialGradient(listOf(p.primary.copy(alpha = 0.18f), Color.Transparent)), CircleShape))
        Column(Modifier.width(cardWidth).height(150.dp).glass(p, RoundedCornerShape(24.dp)).padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(p.primary, p.accent, p.primary2).forEach { color ->
                    Box(Modifier.size(7.dp).background(color, CircleShape))
                }
            }
            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CodeLine(p.primary, 0.62f)
                CodeLine(p.accent, 0.82f, 12.dp)
                CodeLine(p.primary2, 0.48f, 12.dp)
                CodeLine(p.muted.copy(alpha = 0.35f), 0.70f)
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).offset(x = (-18).dp, y = (-6).dp).size(58.dp)
            .shadow(18.dp, RoundedCornerShape(18.dp), ambientColor = p.primary.copy(alpha = 0.35f), spotColor = p.primary2.copy(alpha = 0.35f))
            .clip(RoundedCornerShape(18.dp)).background(Brush.linearGradient(listOf(p.primary, p.primary2))),
            contentAlignment = Alignment.Center) {
            ToolGlyph(ToolIcon.Plus, Color.White, Modifier.size(26.dp))
        }
    }
}

@Composable
private fun CodeLine(color: Color, fraction: Float, indent: Dp = 0.dp) {
    Box(Modifier.fillMaxWidth().padding(start = indent)) {
        Box(Modifier.fillMaxWidth(fraction).height(8.dp).background(color, RoundedCornerShape(4.dp)))
    }
}

@Composable
private fun GradientButton(text: String, icon: ToolIcon, p: HomePalette, onClick: () -> Unit) {
    val shape = RoundedCornerShape(17.dp)
    Row(Modifier.fillMaxWidth().height(56.dp)
        .shadow(18.dp, shape, ambientColor = p.primary.copy(alpha = 0.30f), spotColor = p.primary2.copy(alpha = 0.30f))
        .clip(shape).background(Brush.horizontalGradient(listOf(p.primary, p.primary2))).clickable(onClick = onClick)
        .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            ToolGlyph(icon, Color.White, Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
        ToolGlyph(ToolIcon.Chevron, Color.White.copy(alpha = 0.9f), Modifier.size(16.dp))
    }
}

@Composable
private fun GlassActionButton(text: String, icon: ToolIcon, p: HomePalette, tint: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(17.dp)
    Row(Modifier.fillMaxWidth().height(54.dp).glass(p, shape).clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            ToolGlyph(icon, tint, Modifier.size(19.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(text, color = p.text, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, modifier = Modifier.weight(1f))
        ToolGlyph(ToolIcon.Chevron, p.muted, Modifier.size(16.dp))
    }
}

@Composable
private fun QuickAction(label: String, icon: ToolIcon, p: HomePalette, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.height(82.dp).glass(p, RoundedCornerShape(18.dp), elevated = false).clickable(onClick = onClick)
        .padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            ToolGlyph(icon, tint, Modifier.size(18.dp))
        }
        Spacer(Modifier.height(7.dp))
        Text(label, color = p.text, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AgentCard(p: HomePalette, onClick: () -> Unit) {
    Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp).fillMaxWidth().glass(p, RoundedCornerShape(22.dp))
        .clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(Brush.linearGradient(listOf(p.primary, p.primary2))),
            contentAlignment = Alignment.Center) {
            ToolGlyph(ToolIcon.CodeBrackets, Color.White, Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Codriod Agent", color = p.text, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp)
            Text("Add your AI key in Add-ons to build with AI.", color = p.muted, fontSize = 11.5.sp, lineHeight = 15.sp)
        }
        Box(Modifier.size(34.dp).clip(CircleShape).background(p.primary.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
            ToolGlyph(ToolIcon.Chevron, p.primary, Modifier.size(16.dp))
        }
    }
}

@Composable
private fun GradientFab(p: HomePalette, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Row(modifier.shadow(18.dp, shape, ambientColor = p.primary.copy(alpha = 0.35f), spotColor = p.primary2.copy(alpha = 0.35f))
        .clip(shape).background(Brush.horizontalGradient(listOf(p.primary, p.primary2))).clickable(onClick = onClick)
        .padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        ToolGlyph(ToolIcon.Plus, Color.White, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("New project", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewProjectSheet(initial: String, onDismiss: () -> Unit, onGitHub: () -> Unit, onCreate: (String, String) -> Unit) {
    val p = LocalHomePalette.current
    var name by remember { mutableStateOf("") }
    var template by remember { mutableStateOf(initial) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = if (p.dark) Color(0xFF121826) else Color.White) {
        Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 26.dp)) {
            Text("New project", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = p.text)
            Text("Pick a starter. You can change everything later.", fontSize = 12.5.sp, color = p.muted,
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
                        val shape = RoundedCornerShape(16.dp)
                        Column(Modifier.weight(1f).clip(shape)
                            .background(if (on) p.primary.copy(alpha = 0.12f) else p.glass)
                            .border(if (on) 1.5.dp else 1.dp, if (on) p.primary else p.border, shape)
                            .clickable { if (key == "GitHub") onGitHub() else template = key }.padding(14.dp)) {
                            Text(title.substringBefore(' '), fontSize = 22.sp)
                            Text(title.substringAfter(' '), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp),
                                color = p.text)
                            Text(sub, fontSize = 11.5.sp, color = p.muted)
                        }
                    }
                }
            }
            Button(onClick = { onCreate(name.trim(), template) }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = p.primary, contentColor = Color.White)) {
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
