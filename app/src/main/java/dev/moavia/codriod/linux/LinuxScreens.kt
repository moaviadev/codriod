package dev.moavia.codriod.linux

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.moavia.codriod.workspace.ToolIcon
import dev.moavia.codriod.workspace.WorkspaceIconButton

private val Green = Color(0xFF22C55E)
private val Red = Color(0xFFEF4444)
private val Amber = Color(0xFFF59E0B)

/** Add-ons → Linux & AI CLIs: one-tap Ubuntu, Node.js and official agents, with Codriod Doctor. */
@Composable
fun LinuxHubScreen(
    linux: LinuxEnvironment,
    onBack: () -> Unit,
    onOpenCli: (id: String, login: Boolean) -> Unit,
    onOpenTerminal: () -> Unit,
    onAskAgent: (String) -> Unit
) {
    val defaults = remember { mutableStateListOf("claude", "codex", "gemini", "copilot") }
    var showLog by remember { mutableStateOf(false) }
    var manageMenu by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var doctorPage by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(doctorPage) { doctorPage = false }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            WorkspaceIconButton(ToolIcon.Back, "Back") { if (doctorPage) doctorPage = false else onBack() }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text("LINUX & AI CLIS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(when {
                    doctorPage -> "Codriod Doctor"
                    linux.phase == LinuxPhase.Installing -> "Installing…"
                    else -> "Ubuntu, Node.js and agents"
                }, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            }
            if (linux.ready && !doctorPage) Box {
                TextButton(onClick = { manageMenu = true }) { Text("Manage") }
                DropdownMenu(manageMenu, { manageMenu = false }) {
                    DropdownMenuItem({ Text("Open Ubuntu terminal") }, { manageMenu = false; onOpenTerminal() })
                    DropdownMenuItem({ Text("Codriod Doctor") }, { manageMenu = false; doctorPage = true })
                    DropdownMenuItem({ Text("Update Node.js") }, { manageMenu = false; linux.installNode() })
                    DropdownMenuItem({ Text("Unpack Ubuntu again (keeps logins)") }, { manageMenu = false; linux.reinstall() })
                    DropdownMenuItem({ Text("Remove Ubuntu…", color = Red) }, { manageMenu = false; confirmRemove = true })
                }
            }
        }
        if (doctorPage) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { DoctorCard(linux) }
                linux.problem?.let { problem -> item { ProblemCard(linux, problem, onAskAgent) } }
                item {
                    Text("Codriod AI gets the problem and the full log, and can run linux_run / linux_doctor to find the cause and fix it.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (linux.steps.isNotEmpty() && linux.busy) item { StepsCard(linux, showLog) { showLog = !showLog } }
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { StatusCard(linux, defaults, onOpenTerminal, onDoctor = { doctorPage = true; linux.runChecks() }) }
            if (linux.steps.isNotEmpty()) item { StepsCard(linux, showLog) { showLog = !showLog } }
            linux.problem?.let { problem -> item { ProblemCard(linux, problem, onAskAgent) } }
            if (linux.phase != LinuxPhase.Unsupported) {
                item { Section("AI CLIS · sign in with your own accounts") }
                items(CliCatalog.all, key = { it.id }) { cli ->
                    CliCard(linux, cli, selected = cli.id in defaults, onSelect = { if (cli.id in defaults) defaults.remove(cli.id) else defaults.add(cli.id) },
                        onOpenCli = onOpenCli)
                }
            }
            if (linux.ready) {
                item { Section("CODRIOD DOCTOR") }
                item {
                    Card {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("\uD83E\uDE7A", fontSize = 22.sp)
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text("Health check & repair", fontWeight = FontWeight.SemiBold)
                                Text("Runtime, Ubuntu, DNS, apt, Node.js and every CLI \u00B7 fixes problems without AI",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { doctorPage = true }) { Text("Open \u203A") }
                        }
                    }
                }
            }
            item {
                Text("Everything runs on this phone. CLIs sign in with your own accounts in your browser; their logins stay in Ubuntu's " +
                    "/root folder on this phone and are never sent to Codriod. Ubuntu runs with proot (GPL-2.0, github.com/termux/proot).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
    if (confirmRemove) AlertDialog(onDismissRequest = { confirmRemove = false },
        title = { Text("Remove Ubuntu?") },
        text = { Text("Deletes Ubuntu, Node.js and all CLIs from this phone. Your projects are not touched.") },
        confirmButton = { TextButton(onClick = { confirmRemove = false; linux.uninstall(keepHome = false) }) { Text("Remove all", color = Red) } },
        dismissButton = {
            Row {
                TextButton(onClick = { confirmRemove = false; linux.uninstall(keepHome = true) }) { Text("Keep logins") }
                TextButton(onClick = { confirmRemove = false }) { Text("Cancel") }
            }
        })
}

@Composable
private fun Section(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, start = 2.dp))
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(14.dp)).padding(14.dp), content = content)
}

@Composable
private fun StatusCard(linux: LinuxEnvironment, selected: List<String>, onOpenTerminal: () -> Unit, onDoctor: () -> Unit) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFFE95420)), contentAlignment = Alignment.Center) {
                Text("🐧", fontSize = 21.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Ubuntu 24.04 LTS", fontWeight = FontWeight.SemiBold)
                Text(when (linux.phase) {
                    LinuxPhase.Unsupported -> "Not available on this phone"
                    LinuxPhase.NotInstalled -> "Not installed · about 1–2 GB with Node.js and CLIs"
                    LinuxPhase.Installing -> {
                        val at = linux.steps.indexOfFirst { it.status == StepStatus.Running || it.status == StepStatus.Fixing }
                        if (at >= 0) "Installing · step ${at + 1} of ${linux.steps.size}" else "Installing…"
                    }
                    LinuxPhase.Ready -> "Ready · ${linux.ubuntuArch} · Node.js ${linux.nodeVersion ?: "not installed"}"
                    LinuxPhase.Broken -> "Needs repair"
                }, style = MaterialTheme.typography.bodySmall, color = when (linux.phase) {
                    LinuxPhase.Ready -> Green; LinuxPhase.Broken -> Red; else -> MaterialTheme.colorScheme.onSurfaceVariant })
            }
        }
        when (linux.phase) {
            LinuxPhase.Unsupported -> Text(linux.unsupportedReason, style = MaterialTheme.typography.bodySmall, color = Amber, modifier = Modifier.padding(top = 10.dp))
            LinuxPhase.NotInstalled, LinuxPhase.Broken -> {
                Text("One tap installs Ubuntu, git, curl, python, Node.js 22 and the CLIs ticked below. Errors are repaired automatically.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp))
                Button(onClick = { linux.install(selected.toList()) }, enabled = !linux.busy, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(46.dp),
                    shape = RoundedCornerShape(12.dp)) {
                    Text(if (linux.phase == LinuxPhase.Broken) "Repair & finish install" else
                        "Install Ubuntu" + if (selected.isEmpty()) "" else " + ${selected.size} CLI" + if (selected.size > 1) "s" else "")
                }
                Text("Needs Wi-Fi or mobile data · keep Codriod open while it installs.", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
            LinuxPhase.Installing -> InstallProgress(linux)
            LinuxPhase.Ready -> if (linux.busy && linux.steps.isNotEmpty() && linux.checks.isEmpty() && !linux.checking) InstallProgress(linux)
            else Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenTerminal, shape = RoundedCornerShape(10.dp)) { Text("Open terminal") }
                OutlinedButton(onClick = onDoctor, enabled = !linux.busy, shape = RoundedCornerShape(10.dp)) { Text("Check health") }
            }
        }
    }
}

/** Whole-install progress: percent, time left, time so far and the usual total, so nobody gives up half way. */
@Composable
private fun InstallProgress(linux: LinuxEnvironment) {
    Column(Modifier.padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${(linux.overall * 100).toInt()}%", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).padding(bottom = 3.dp)) {
                Text(linux.timeLeft.replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text("Running ${linux.elapsed} · usually ${linux.usualTime}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LinearProgressIndicator(progress = { linux.overall }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(7.dp).clip(RoundedCornerShape(4.dp)))
        if (linux.progressLabel.isNotBlank()) Text(linux.progressLabel, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 5.dp))
        Row(Modifier.padding(top = 10.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)).padding(10.dp)) {
            Text("ℹ", color = MaterialTheme.colorScheme.primary)
            Text("  Keep going: it continues in the background if you switch apps, and the notification shows the time left. " +
                "The first install downloads ~300 MB.", style = MaterialTheme.typography.labelSmall)
        }
        OutlinedButton(onClick = linux::cancel, modifier = Modifier.padding(top = 8.dp)) { Text("Stop") }
    }
}

@Composable
private fun StepsCard(linux: LinuxEnvironment, showLog: Boolean, onToggleLog: () -> Unit) {
    Card {
        linux.steps.forEach { step ->
            val fixing = step.status == StepStatus.Fixing
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(if (fixing) Amber.copy(alpha = 0.12f) else Color.Transparent)
                .padding(vertical = 4.dp, horizontal = if (fixing) 4.dp else 0.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                    when (step.status) {
                        StepStatus.Running, StepStatus.Fixing -> CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp,
                            color = if (step.status == StepStatus.Fixing) Amber else MaterialTheme.colorScheme.primary)
                        StepStatus.Done -> Text("✓", color = Green, fontWeight = FontWeight.Bold)
                        StepStatus.Skipped -> Text("✓", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        StepStatus.Failed -> Text("✗", color = Red, fontWeight = FontWeight.Bold)
                        StepStatus.Waiting -> Text("○", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Column(Modifier.weight(1f).padding(start = 6.dp)) {
                    Text(step.title, style = MaterialTheme.typography.bodyMedium,
                        color = if (step.status == StepStatus.Waiting) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    if (step.detail.isNotBlank()) Text((if (step.status == StepStatus.Fixing) "🩺 " else "") + step.detail,
                        style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = if (step.status == StepStatus.Failed) Red else if (step.status == StepStatus.Fixing) Amber else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (step.status == StepStatus.Running || step.status == StepStatus.Fixing) Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(progress = { step.progress }, modifier = Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)),
                            color = if (step.status == StepStatus.Fixing) Amber else MaterialTheme.colorScheme.primary)
                        Text("  ${(step.progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        TextButton(onClick = onToggleLog, contentPadding = PaddingValues(0.dp)) { Text(if (showLog) "Hide details" else "Show details (log)") }
        if (showLog) {
            val state = rememberLazyListState()
            LaunchedEffect(linux.log.size) { if (linux.log.isNotEmpty()) state.scrollToItem(linux.log.lastIndex) }
            SelectionContainer {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF111114)).padding(8.dp), state = state) {
                    items(linux.log.toList()) { line ->
                        Text(line, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = when {
                            line.startsWith("✗") -> Red; line.startsWith("🩺") -> Amber; line.startsWith("✓") || line.startsWith("▶") -> Green
                            else -> Color(0xFFD4D4D8) }, softWrap = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProblemCard(linux: LinuxEnvironment, problem: String, onAskAgent: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Red.copy(alpha = 0.10f))
        .border(1.dp, Red.copy(alpha = 0.4f), RoundedCornerShape(14.dp)).padding(14.dp)) {
        Text("Codriod Doctor could not fix this automatically", fontWeight = FontWeight.SemiBold, color = Red)
        Text(problem, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { linux.install(linux.steps.filter { it.id.startsWith("cli:") }.map { it.id.removePrefix("cli:") }) }, enabled = !linux.busy) { Text("Retry") }
            OutlinedButton(onClick = {
                onAskAgent("The Ubuntu/CLI installer in Codriod failed and Codriod Doctor could not fix it.\n\nProblem: $problem\n\n" +
                    "Log (last lines):\n```\n${linux.logText().takeLast(5000)}\n```\nFind the cause and fix it with linux_run / linux_doctor, then retry the install.")
            }) { Text("✦ Ask Codriod AI") }
            TextButton(onClick = { clipboard.setText(AnnotatedString(linux.logText())) }) { Text("Copy log") }
        }
    }
}

@Composable
private fun CliCard(linux: LinuxEnvironment, cli: CliSpec, selected: Boolean, onSelect: () -> Unit, onOpenCli: (String, Boolean) -> Unit) {
    val version = linux.installed[cli.id]
    val busy = linux.busyCli == cli.id
    var menu by remember { mutableStateOf(false) }
    if (!linux.ready && version == null) {
        // Before the first install: compact rows with a tick, like a checklist.
        Card {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(enabled = linux.phase != LinuxPhase.Installing, onClick = onSelect)) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(Color(cli.color)), contentAlignment = Alignment.Center) {
                    Text(cli.name.take(1), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
                Column(Modifier.weight(1f).padding(start = 11.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(cli.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        if (cli.experimental) Text("  beta", style = MaterialTheme.typography.labelSmall, color = Amber)
                    }
                    Text("${cli.vendor} · ${cli.signIn}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Checkbox(checked = selected, onCheckedChange = { onSelect() }, enabled = linux.phase != LinuxPhase.Installing)
            }
        }
        return
    }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(Color(cli.color)), contentAlignment = Alignment.Center) {
                Text(cli.name.take(1), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(cli.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    if (cli.experimental) Text("  beta", style = MaterialTheme.typography.labelSmall, color = Amber)
                }
                Text("${cli.vendor} · ${if (version != null) "installed ${version.ifBlank { "" }}".trim() else "not installed"}",
                    style = MaterialTheme.typography.labelSmall, color = if (version != null) Green else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (version != null) Box {
                TextButton(onClick = { menu = true }) { Text("⋯", fontSize = 18.sp) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Update") }, { menu = false; linux.installCli(cli.id) })
                    DropdownMenuItem({ Text(if (cli.id in linux.pinned) "Hide tab on editor edge" else "Show tab on editor edge") },
                        { menu = false; linux.togglePinned(cli.id) })
                    DropdownMenuItem({ Text("Remove", color = Red) }, { menu = false; linux.removeCli(cli.id) })
                }
            }
        }
        Text(cli.description, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        Text("Sign in: ${cli.signIn}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 3.dp))
        if (linux.ready || version != null) Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            when {
                busy -> { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text("Working…", style = MaterialTheme.typography.bodySmall) }
                version == null -> Button(onClick = { linux.installCli(cli.id) }, enabled = !linux.busy, shape = RoundedCornerShape(10.dp)) { Text("Install") }
                else -> {
                    Button(onClick = { onOpenCli(cli.id, false) }, shape = RoundedCornerShape(10.dp)) { Text("Open") }
                    OutlinedButton(onClick = { onOpenCli(cli.id, true) }, shape = RoundedCornerShape(10.dp)) { Text("Sign in") }
                    FilterChipLike(cli.id in linux.pinned) { linux.togglePinned(cli.id) }
                }
            }
        }
    }
}

@Composable
private fun FilterChipLike(on: Boolean, onClick: () -> Unit) {
    Text(if (on) "✓ Edge tab" else "＋ Edge tab", style = MaterialTheme.typography.labelMedium,
        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp))
}

@Composable
private fun DoctorCard(linux: LinuxEnvironment) {
    Card {
        Text("Checks the runtime, Ubuntu, internet & DNS, apt, Node.js and every CLI, and repairs problems without AI.",
            style = MaterialTheme.typography.bodySmall)
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { linux.runChecks(false) }, enabled = !linux.busy && !linux.checking) { Text("Check") }
            Button(onClick = { linux.runChecks(true) }, enabled = !linux.busy && !linux.checking) { Text("🩺 Repair all") }
            if (linux.checking) CircularProgressIndicator(Modifier.size(20.dp).align(Alignment.CenterVertically), strokeWidth = 2.dp)
        }
        linux.checks.forEach { check ->
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (check.ok) Green else Red))
                Text(check.title, Modifier.padding(start = 9.dp).weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(check.detail, style = MaterialTheme.typography.labelSmall, color = if (check.ok) MaterialTheme.colorScheme.onSurfaceVariant else Red,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
            }
        }
    }
}

