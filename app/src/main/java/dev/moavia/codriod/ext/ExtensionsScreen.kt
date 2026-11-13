package dev.moavia.codriod.ext

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.moavia.codriod.linux.AddonBusyRow
import dev.moavia.codriod.linux.AddonCard
import dev.moavia.codriod.linux.AddonLogoBox
import dev.moavia.codriod.linux.AddonProblemCard
import dev.moavia.codriod.linux.AddonProgress
import dev.moavia.codriod.linux.AddonSection
import dev.moavia.codriod.linux.AddonStepsCard
import dev.moavia.codriod.workspace.ToolIcon
import dev.moavia.codriod.workspace.WorkspaceIconButton

private val Green = Color(0xFF22C55E)
private val Red = Color(0xFFEF4444)
private val Amber = Color(0xFFF59E0B)
private val LspColor = Color(0xFF0EA5E9)
private val DebuggerColor = Color(0xFF8B5CF6)
private val ThemeColor = Color(0xFFEC4899)

/** Add-ons → More extensions: language servers (in Ubuntu), bundled debuggers and editor themes. */
@Composable
fun ExtensionsScreen(
    ext: ExtensionsManager,
    onBack: () -> Unit,
    onOpenLinux: () -> Unit,
    onOpenTerminal: () -> Unit,
    onAskAgent: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<ExtCategory?>(null) }
    var showLog by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<String?>(null) }
    var lastInstall by remember { mutableStateOf("pyright") }
    LaunchedEffect(ext.linux.ready) { if (ext.linux.ready) ext.refresh() }
    androidx.activity.compose.BackHandler(confirmRemove != null) { confirmRemove = null }
    val q = query.trim()
    val servers = ExtensionsCatalog.all.filter { it.category == ExtCategory.LanguageServer &&
        (q.isBlank() || it.name.contains(q, true) || it.blurb.contains(q, true)) }
    val debuggers = ExtensionsCatalog.all.filter { it.category == ExtCategory.Debugger &&
        (q.isBlank() || it.name.contains(q, true) || it.blurb.contains(q, true)) }
    val themes = if (category != ExtCategory.Theme && category != null) emptyList()
        else EditorThemes.all.filter { q.isBlank() || it.name.contains(q, true) }
    val showServers = (category == null || category == ExtCategory.LanguageServer) && servers.isNotEmpty()
    val showDebuggers = (category == null || category == ExtCategory.Debugger) && debuggers.isNotEmpty()
    val showThemes = (category == null || category == ExtCategory.Theme) && themes.isNotEmpty()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            WorkspaceIconButton(ToolIcon.Back, "Back") { if (confirmRemove != null) confirmRemove = null else onBack() }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text("MORE EXTENSIONS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(when {
                    ext.inst.busy -> "Installing…"
                    else -> "Language servers, debuggers and themes"
                }, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            }
            if (ext.linux.ready && !ext.inst.busy) Box {
                TextButton(onClick = { ext.refresh() }, enabled = !ext.checking) { Text(if (ext.checking) "Checking…" else "Re-check") }
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { StatusCard(ext, onOpenLinux, onOpenTerminal) }
            if (ext.inst.steps.isNotEmpty()) item { AddonStepsCard(ext.inst, showLog) { showLog = !showLog } }
            ext.inst.problem?.let { problem ->
                item {
                    AddonProblemCard(ext.inst, "The log above shows the exact command output; Codriod AI can also fix Ubuntu for you.",
                        onRetry = { ext.install(lastInstall) }, onAskAgent = { onAskAgent(
                            "A More-extensions installer in Codriod (language server) failed and Codriod Doctor could not fix it.\n\nProblem: $problem\n\n" +
                            "Log (last lines):\n```\n${ext.inst.logText().takeLast(5000)}\n```\nFind the cause and fix it with linux_run / linux_doctor, then retry.") })
                }
            }
            item {
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("Search extensions", style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            }
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChipLike(category == null, "All") { category = null }
                    FilterChipLike(category == ExtCategory.LanguageServer, "Language servers") { category = ExtCategory.LanguageServer }
                    FilterChipLike(category == ExtCategory.Debugger, "Debuggers") { category = ExtCategory.Debugger }
                    FilterChipLike(category == ExtCategory.Theme, "Themes") { category = ExtCategory.Theme }
                }
            }
            if (!showServers && !showDebuggers && !showThemes) {
                item {
                    AddonCard {
                        Text("Nothing matches “${query.trim()}”", fontWeight = FontWeight.SemiBold)
                        Text("Try another word — for example Python, Java, debugger or Dracula.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (showServers) {
                item { AddonSection("LANGUAGE SERVERS · installed into Ubuntu") }
                items(servers, key = { it.id }) { spec -> ExtCard(ext, spec, lastInstall = { lastInstall = it },
                    onRemove = { confirmRemove = spec.id }) }
            }
            if (showDebuggers) {
                item { AddonSection("DEBUGGERS · already bundled with the toolchains") }
                items(debuggers, key = { it.id }) { spec -> DebuggerCard(ext, spec) }
            }
            if (showThemes) {
                item { AddonSection("EDITOR THEMES · built in, apply instantly") }
                item {
                    AddonCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🎨", fontSize = 20.sp)
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text("Default theme", fontWeight = FontWeight.SemiBold)
                                Text("Dark and light follow Codriod's own settings",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (ext.activeThemeId == null) {
                                Text("Active", fontSize = 11.sp, color = Green, fontWeight = FontWeight.SemiBold)
                            } else {
                                TextButton(onClick = { ext.setTheme(null) }) { Text("Reset to default") }
                            }
                        }
                    }
                }
                items(themes, key = { it.id }) { theme -> ThemeCard(ext, theme) }
            }
            item {
                Text("Only extensions with a real installation or integration method are listed here. Language servers install into the " +
                    "Ubuntu environment and are verified by running them; Codriod's built-in editor cannot use them for completions yet — " +
                    "its editing and completion engine is local and has no LSP client. Debuggers are part of the installed toolchains.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
    confirmRemove?.let { id ->
        val name = ExtensionsCatalog.byId(id)?.name ?: id
        AlertDialog(onDismissRequest = { confirmRemove = null },
            title = { Text("Remove $name?") },
            text = { Text("Uninstalls the language server from the Ubuntu environment. Everything else stays.") },
            confirmButton = { TextButton(onClick = { confirmRemove = null; ext.remove(id) }) { Text("Remove", color = Red) } },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancel") } })
    }
}

@Composable
private fun StatusCard(ext: ExtensionsManager, onOpenLinux: () -> Unit, onOpenTerminal: () -> Unit) {
    AddonCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AddonLogoBox("⧉", LspColor)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("More extensions", fontWeight = FontWeight.SemiBold)
                Text(when {
                    !ext.linux.ready -> "Needs the Ubuntu environment (themes work right now)"
                    else -> ext.summary()
                }, style = MaterialTheme.typography.bodySmall,
                    color = if (ext.linux.ready) Green else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (ext.checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        if (!ext.linux.ready) {
            Text("Language servers and the debugger checks run inside Ubuntu (the same environment as the AI CLIs).",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp))
            Button(onClick = onOpenLinux, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(46.dp),
                shape = RoundedCornerShape(12.dp)) { Text("Set up Ubuntu first") }
        } else {
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenTerminal, shape = RoundedCornerShape(10.dp)) { Text("Open terminal") }
                OutlinedButton(onClick = { ext.refresh() }, enabled = !ext.inst.busy && !ext.checking, shape = RoundedCornerShape(10.dp)) { Text("Re-check") }
            }
        }
    }
}

@Composable
private fun ExtCard(ext: ExtensionsManager, spec: ExtSpec, lastInstall: (String) -> Unit, onRemove: () -> Unit) {
    val installed = spec.id in ext.installed
    val enabled = ext.isEnabled(spec.id)
    var menu by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    val busy = ext.inst.busy
    AddonCard {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { details = !details }) {
            AddonLogoBox(spec.name.take(1), LspColor)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(spec.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    if (spec.experimental) Text("  beta", style = MaterialTheme.typography.labelSmall, color = Amber)
                }
                Text(when {
                    installed && enabled -> "installed${ext.installed[spec.id]?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""}"
                    installed -> "installed · disabled"
                    spec.needsJava && !ext.javaKit.hasJava -> "needs the JDK first"
                    else -> "not installed · ${spec.size}"
                }, style = MaterialTheme.typography.labelSmall,
                    color = if (installed && enabled) Green else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(if (details) "⌃" else "⌄", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(spec.blurb, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        if (details) {
            Text("Compatibility", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp))
            Text(spec.integration, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            if (spec.installScript.isNotBlank()) Text("Install method: ${spec.size} download${if (spec.needsJava) " · needs the JDK" else ""}" +
                ", installed into Ubuntu and verified by running it.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp))
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                busy -> AddonBusyRow()
                !installed -> Button(onClick = { lastInstall(spec.id); ext.install(spec.id) }, shape = RoundedCornerShape(10.dp)) {
                    Text(if (spec.needsJava && !ext.javaKit.hasJava) "Install (+ JDK)" else "Install")
                }
                else -> {
                    FilterChipLike(enabled, if (enabled) "✓ Enabled" else "Disabled") { ext.setEnabled(spec.id, !enabled) }
                    Box {
                        TextButton(onClick = { menu = true }) { Text("⋯", fontSize = 18.sp) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text(if (enabled) "Disable" else "Enable") }, { menu = false; ext.setEnabled(spec.id, !enabled) })
                            DropdownMenuItem({ Text("Update (latest release)") }, { menu = false; lastInstall(spec.id); ext.install(spec.id) })
                            DropdownMenuItem({ Text("Remove", color = Red) }, { menu = false; onRemove() })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DebuggerCard(ext: ExtensionsManager, spec: ExtSpec) {
    val (ok, status) = if (ext.linux.ready) ext.debuggerAvailable(spec.id) else false to "Needs the Ubuntu environment"
    AddonCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AddonLogoBox(spec.name.take(1), DebuggerColor)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(spec.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(status, style = MaterialTheme.typography.labelSmall,
                    color = if (ok) Green else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("no install", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp))
        }
        Text(spec.blurb, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        Text(spec.integration, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun ThemeCard(ext: ExtensionsManager, theme: EditorTheme) {
    val active = ext.activeThemeId == theme.id
    AddonCard {
        ThemePreview(theme)
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(theme.name, fontWeight = FontWeight.SemiBold)
                Text(if (theme.dark) "dark" else "light", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (active) Text("Active", fontSize = 11.sp, color = Green, fontWeight = FontWeight.SemiBold)
            else TextButton(onClick = { ext.setTheme(theme.id) }) { Text("Apply") }
        }
    }
}

@Composable
private fun ThemePreview(theme: EditorTheme) {
    val c = theme.code
    val text = buildAnnotatedString {
        EditorThemes.sample.forEach { (token, kind) ->
            withStyle(SpanStyle(color = when (kind) {
                "comment" -> c.comment; "keyword" -> c.keyword; "string" -> c.string; "number" -> c.number
                else -> if (c.dark) Color(0xFFD4D4D4) else Color(0xFF333333)
            })) { append(token) }
        }
    }
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.header).padding(10.dp)) {
        Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, softWrap = true, lineHeight = 16.sp)
    }
}

@Composable
private fun FilterChipLike(on: Boolean, label: String, onClick: () -> Unit) {
    Text(label, style = MaterialTheme.typography.labelMedium,
        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, if (on) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp))
}
