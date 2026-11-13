package dev.moavia.codriod.jdk

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.moavia.codriod.linux.AddonCard
import dev.moavia.codriod.linux.AddonLogoBox
import dev.moavia.codriod.linux.AddonProblemCard
import dev.moavia.codriod.linux.AddonProgress
import dev.moavia.codriod.linux.AddonSection
import dev.moavia.codriod.linux.AddonStepsCard
import dev.moavia.codriod.linux.StepStatus
import dev.moavia.codriod.workspace.ToolIcon
import dev.moavia.codriod.workspace.WorkspaceIconButton

private val Green = Color(0xFF22C55E)
private val Red = Color(0xFFEF4444)
private val Amber = Color(0xFFF59E0B)
private val JavaOrange = Color(0xFFF89820)
private val KotlinPurple = Color(0xFF7F52FF)
private val GradleBlue = Color(0xFF02303A)

private data class Tool(val id: String, val name: String, val blurb: String, val size: String, val color: Color)

private val Tools = listOf(
    Tool("java", "JDK", "Temurin 21 from Adoptium (api.adoptium.net), installed at /opt/jdk.", "about 200 MB", JavaOrange),
    Tool("kotlin", "Kotlin compiler", "The official compiler (github.com/JetBrains/kotlin), installed at /opt/kotlin.", "about 90 MB", KotlinPurple),
    Tool("gradle", "Gradle", "The official Gradle distribution (services.gradle.org), installed at /opt/gradle.", "about 130 MB", GradleBlue))

/** Add-ons → Java & Kotlin: JDK, Kotlin compiler and Gradle inside Ubuntu, with compile-and-run verification. */
@Composable
fun JavaKitScreen(
    env: JavaKitEnvironment,
    onBack: () -> Unit,
    onOpenLinux: () -> Unit,
    onOpenTerminal: () -> Unit,
    onAskAgent: (String) -> Unit
) {
    var showLog by remember { mutableStateOf(false) }
    var manageMenu by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<String?>(null) }
    var lastTool by remember { mutableStateOf("java") }
    LaunchedEffect(env.linux.ready) { if (env.linux.ready) env.refresh() }
    androidx.activity.compose.BackHandler(confirmRemove != null) { confirmRemove = null }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            WorkspaceIconButton(ToolIcon.Back, "Back") { if (confirmRemove != null) confirmRemove = null else onBack() }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text("JAVA & KOTLIN", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(when {
                    env.inst.busy -> "Installing…"
                    env.verifying -> "Verifying…"
                    else -> "JDK, Kotlin and Gradle"
                }, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            }
            if (env.ready && !env.inst.busy) Box {
                TextButton(onClick = { manageMenu = true }) { Text("Manage") }
                DropdownMenu(manageMenu, { manageMenu = false }) {
                    DropdownMenuItem({ Text("Open Ubuntu terminal") }, { manageMenu = false; onOpenTerminal() })
                    DropdownMenuItem({ Text("Verify installation") }, { manageMenu = false; env.verify() })
                    DropdownMenuItem({ Text("Re-check installed tools") }, { manageMenu = false; env.refresh() })
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { StatusCard(env, onOpenLinux, onOpenTerminal) }
            if (env.inst.steps.isNotEmpty()) item { AddonStepsCard(env.inst, showLog) { showLog = !showLog } }
            env.inst.problem?.let { problem ->
                item {
                    AddonProblemCard(env.inst, "The full log stays in this screen; you can also ask Codriod AI to fix Ubuntu for you.",
                        onRetry = { env.install(lastTool) }, onAskAgent = { onAskAgent(
                            "The Java & Kotlin installer in Codriod failed and Codriod Doctor could not fix it.\n\nProblem: $problem\n\n" +
                            "Log (last lines):\n```\n${env.inst.logText().takeLast(5000)}\n```\nFind the cause and fix it with linux_run / linux_doctor, then retry.") })
                }
            }
            item { AddonSection("TOOLS · inside the Ubuntu environment") }
            items(Tools, key = { it.id }) { tool -> ToolCard(env, tool, active = env.inst.busy && tool.id == lastTool,
                onPick = { lastTool = it }, onRemove = { confirmRemove = tool.id }) }
            if (env.ready) item { VerifyCard(env) }
            item {
                Text("Tools install into the existing Ubuntu environment (/opt/…), the same place Node.js and the AI CLIs live. " +
                    "Nothing is installed into other Android folders, and apt's JDK that came with the Android SDK is kept and reused.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
    confirmRemove?.let { id ->
        val name = Tools.firstOrNull { it.id == id }?.name ?: id
        AlertDialog(onDismissRequest = { confirmRemove = null },
            title = { Text("Remove $name?") },
            text = { Text("Deletes the tool from the Ubuntu environment. Projects and other tools are not touched.") },
            confirmButton = { TextButton(onClick = { confirmRemove = null; env.remove(id) }) { Text("Remove", color = Red) } },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancel") } })
    }
}

@Composable
private fun StatusCard(env: JavaKitEnvironment, onOpenLinux: () -> Unit, onOpenTerminal: () -> Unit) {
    AddonCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AddonLogoBox("J", JavaOrange)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Java & Kotlin toolkit", fontWeight = FontWeight.SemiBold)
                Text(when {
                    !env.linux.ready -> "Needs the Ubuntu environment first"
                    env.inst.busy -> "Installing…"
                    env.versions.isEmpty() -> "Nothing installed yet · about 420 MB with everything"
                    else -> env.versions.entries.joinToString(" · ") { "${it.key.cap()} ${it.value}" }
                }, style = MaterialTheme.typography.bodySmall,
                    color = if (env.versions.isEmpty() && env.ready) MaterialTheme.colorScheme.onSurfaceVariant else Green)
            }
            if (env.checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        when {
            !env.linux.ready -> {
                Text("These tools live inside Ubuntu (the same environment as the AI CLIs). Set it up once, then install the JDK, Kotlin and Gradle here.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp))
                Button(onClick = onOpenLinux, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(46.dp), shape = RoundedCornerShape(12.dp)) {
                    Text("Set up Ubuntu first")
                }
            }
            else -> {
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenTerminal, shape = RoundedCornerShape(10.dp)) { Text("Open terminal") }
                    OutlinedButton(onClick = { env.refresh() }, enabled = !env.inst.busy && !env.checking, shape = RoundedCornerShape(10.dp)) { Text("Re-check") }
                }
            }
        }
    }
}

@Composable
private fun ToolCard(env: JavaKitEnvironment, tool: Tool, active: Boolean, onPick: (String) -> Unit, onRemove: () -> Unit) {
    val version = env.versions[tool.id]
    val fromApt = tool.id == "java" && env.source("java") == "apt"
    val busy = env.inst.busy
    var menu by remember { mutableStateOf(false) }
    AddonCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AddonLogoBox(tool.name.take(1), tool.color)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(tool.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(when {
                    version != null && fromApt -> "installed $version · from Ubuntu (Android SDK)"
                    version != null -> "installed $version"
                    else -> "not installed · ${tool.size}"
                }, style = MaterialTheme.typography.labelSmall,
                    color = if (version != null) Green else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (version != null) Box {
                TextButton(onClick = { menu = true }, enabled = !busy) { Text("⋯", fontSize = 18.sp) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Update (latest release)") }, { menu = false; onPick(tool.id); env.install(tool.id) })
                    if (!fromApt) DropdownMenuItem({ Text("Remove", color = Red) }, { menu = false; onRemove() })
                }
            }
        }
        Text(tool.blurb, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        if (tool.id == "java" && fromApt) {
            Text("Already provided by the Android SDK (OpenJDK 17 via apt). Codriod keeps it; install Temurin 21 with Update if you want a newer JDK.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                busy && active -> { dev.moavia.codriod.linux.AddonBusyRow() }
                version == null -> Button(onClick = { onPick(tool.id); env.install(tool.id) }, enabled = !busy, shape = RoundedCornerShape(10.dp)) {
                    Text("Install ${tool.name}")
                }
                else -> {
                    OutlinedButton(onClick = { onPick(tool.id); env.install(tool.id) }, enabled = !busy, shape = RoundedCornerShape(10.dp)) { Text("Update") }
                    Text(env.short(tool.id)?.let { "v$it" } ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun VerifyCard(env: JavaKitEnvironment) {
    AddonCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🧪", fontSize = 20.sp)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text("Verify installation", fontWeight = FontWeight.SemiBold)
                Text("Compiles and runs a Java and a Kotlin program and runs a small Gradle build.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { env.verify() }, enabled = !env.inst.busy && !env.verifying && env.versions.isNotEmpty(), shape = RoundedCornerShape(10.dp)) {
                Text(if (env.verifyResults.isEmpty()) "Verify now" else "Verify again")
            }
            if (env.verifying) dev.moavia.codriod.linux.AddonBusyRow()
        }
        if (env.verifyResults.isNotEmpty()) {
            env.verifyResults.forEach { (tool, ok) ->
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    dev.moavia.codriod.linux.AddonDot(ok)
                    Text("  ${tool.cap()} ${env.versions[tool].orEmpty()} ${if (ok) "compiles & runs ✓" else "did not pass ✗"}",
                        style = MaterialTheme.typography.bodyMedium, color = if (ok) Green else Red)
                }
            }
        }
        if (env.verifyLog.isNotEmpty()) {
            val state = rememberLazyListState()
            LaunchedEffect(env.verifyLog.size) { if (env.verifyLog.isNotEmpty()) state.scrollToItem(env.verifyLog.lastIndex) }
            SelectionContainer {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 200.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF111114)).padding(8.dp), state = state) {
                    items(env.verifyLog.takeLast(80)) { line ->
                        Text(line, fontFamily = FontFamily.Monospace, fontSize = 10.sp, softWrap = true,
                            color = if (line.endsWith("-ok")) Green else Color(0xFFD4D4D8))
                    }
                }
            }
        }
    }
}

private fun String.cap() = replaceFirstChar { it.uppercase() }
