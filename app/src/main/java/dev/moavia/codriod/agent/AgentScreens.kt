package dev.moavia.codriod.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.moavia.codriod.workspace.LocalCodeColors
import dev.moavia.codriod.workspace.ToolGlyph
import dev.moavia.codriod.workspace.ToolIcon
import dev.moavia.codriod.workspace.WorkspaceIconButton

// ---------------------------------------------------------------------------------------------------
// Add-ons
// ---------------------------------------------------------------------------------------------------

private data class AddonRow(val title: String, val subtitle: String, val status: String?, val available: Boolean,
    val onClick: (() -> Unit)? = null)

/** Add-ons: one row per add-on. Codriod Agent is the first; more agents and runtimes will join the list. */
@Composable
fun AddonsScreen(agent: AgentController, onBack: () -> Unit, onOpenAgent: () -> Unit, onOpenGit: () -> Unit) {
    val code = LocalCodeColors.current
    val agentStatus = agent.provider?.let { p -> agent.modelId?.let { "${p.name} · $it" } } ?: "Set up"
    val rows = listOf(
        AddonRow("Codriod Agent", "AI chat and coding agent · ${Providers.all.size} providers, free and pro", agentStatus, true, onOpenAgent),
        AddonRow("Git & GitHub", "Your own GitHub login (SSH key or token), clone, pull and push", "Built in", true, onOpenGit),
        AddonRow("Python", "Python runtime and pip for this phone", null, false),
        AddonRow("Node.js", "Node.js runtime and npm", null, false),
        AddonRow("Java & Kotlin", "JDK, Kotlin and Gradle tools", null, false),
        AddonRow("More AI agents", "Other agent extensions with their own sign-in", null, false)
    )
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().height(52.dp).background(code.header).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            WorkspaceIconButton(ToolIcon.Back, "Back", onBack)
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text("ADD-ONS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Extensions and tools", style = MaterialTheme.typography.titleMedium)
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item { SectionTitle("INSTALLED") }
            items(rows.filter { it.available }) { AddonListRow(it) }
            item { SectionTitle("COMING SOON") }
            items(rows.filterNot { it.available }) { AddonListRow(it) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp))
}

@Composable
private fun AddonListRow(row: AddonRow) {
    Row(Modifier.fillMaxWidth().clickable(enabled = row.onClick != null) { row.onClick?.invoke() }
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).background(if (row.available) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            Text(row.title.take(1), fontWeight = FontWeight.Bold, color = if (row.available)
                MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(row.title, style = MaterialTheme.typography.titleMedium,
                color = if (row.available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(row.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Badge(row.status ?: "Soon", row.available)
        if (row.onClick != null) ToolGlyph(ToolIcon.Chevron, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 64.dp))
}

@Composable
private fun Badge(text: String, accent: Boolean) {
    Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp,
        color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.widthIn(max = 130.dp).border(1.dp, if (accent) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
}

// ---------------------------------------------------------------------------------------------------
// Agent
// ---------------------------------------------------------------------------------------------------

private enum class AgentTab(val title: String) { Chat("Chat"), Providers("Providers"), Settings("Settings") }

@Composable
fun AgentScreen(agent: AgentController, projectName: String?, onBack: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(if (agent.ready) AgentTab.Chat else AgentTab.Providers) }
    var picker by remember { mutableStateOf(false) }
    val code = LocalCodeColors.current
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().height(52.dp).background(code.header).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            WorkspaceIconButton(ToolIcon.Back, "Back to add-ons", onBack)
            Column(Modifier.weight(1f).padding(start = 4.dp).clickable { picker = true }) {
                Text("CODRIOD AGENT" + (projectName?.let { "  ·  $it" } ?: ""), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(agent.provider?.let { "${it.name} · ${agent.modelId}" } ?: "Choose a model ▾",
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (tab == AgentTab.Chat && agent.messages.isNotEmpty())
                WorkspaceIconButton(ToolIcon.Clear, "Clear chat") { agent.clear() }
        }
        Row(Modifier.fillMaxWidth().height(40.dp)) {
            AgentTab.entries.forEach { item ->
                val selected = tab == item
                Column(Modifier.weight(1f).fillMaxHeight().clickable { tab = item },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(item.title, style = MaterialTheme.typography.labelLarge,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.width(if (selected) 28.dp else 0.dp).height(2.dp).background(MaterialTheme.colorScheme.primary))
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                AgentTab.Chat -> ChatTab(agent, projectName != null, onSetup = { tab = AgentTab.Providers })
                AgentTab.Providers -> ProvidersTab(agent)
                AgentTab.Settings -> SettingsTab(agent)
            }
        }
    }
    if (picker) ModelPickerDialog(agent, onDismiss = { picker = false }, onSetup = { picker = false; tab = AgentTab.Providers })
}

// ---- Chat ------------------------------------------------------------------------------------------

@Composable
private fun ChatTab(agent: AgentController, hasProject: Boolean, onSetup: () -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    val visible = agent.messages.filter { it.role != Role.Tool || it.summary != null }
    LaunchedEffect(visible.size, agent.pending) { if (visible.isNotEmpty()) list.animateScrollToItem(visible.size) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (visible.isEmpty()) item { EmptyChat(agent, hasProject, onSetup) { input = it } }
            itemsIndexed(visible) { _, message -> MessageView(message) }
            if (agent.running && agent.pending == null) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Working…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { Spacer(Modifier.height(4.dp)) }
        }
        agent.pending?.let { ApprovalCard(it, agent) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.horizontalScroll(rememberScrollState())) {
                AgentMode.entries.forEach { m ->
                    FilterChip(selected = agent.mode == m, onClick = { agent.updateMode(m) }, enabled = !agent.running,
                        label = { Text(m.label, fontSize = 11.sp) }, modifier = Modifier.padding(end = 4.dp).height(28.dp))
                }
                FilterChip(selected = agent.includeOpenFile, onClick = { agent.updateIncludeOpenFile(!agent.includeOpenFile) },
                    label = { Text("Open file", fontSize = 11.sp) }, modifier = Modifier.padding(end = 4.dp).height(28.dp))
                agent.lastUsage?.let { u ->
                    Text("${u.input} in · ${u.output} out", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp))
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(input, { input = it.take(20_000) }, modifier = Modifier.weight(1f),
                    placeholder = { Text(if (hasProject) "Ask or tell the agent what to do…" else "Open a project to chat") },
                    enabled = hasProject, maxLines = 6, textStyle = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(6.dp))
                if (agent.running) Button(onClick = agent::stop, colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error)) { Text("Stop") }
                else Button(onClick = { agent.send(input); input = "" }, enabled = hasProject && input.isNotBlank()) { Text("Send") }
            }
        }
    }
}

@Composable
private fun EmptyChat(agent: AgentController, hasProject: Boolean, onSetup: () -> Unit, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("What should we build?", style = MaterialTheme.typography.titleLarge)
        Text(agent.mode.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!agent.ready) Button(onClick = onSetup) { Text("Add an API key and choose a model") }
        if (!hasProject) Text("Open a project from Home to let the agent read and edit its files.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        listOf("Explain this project", "Find bugs in the open file", "Configure GitHub login for my account",
            "Add a dark mode toggle to the page").forEach { suggestion ->
            OutlinedButton(onClick = { onPick(suggestion) }, enabled = hasProject) { Text(suggestion, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun MessageView(message: ChatMessage) {
    when (message.role) {
        Role.User -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            SelectionContainer {
                Text(message.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.widthIn(max = 320.dp).background(MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 7.dp))
            }
        }
        Role.Assistant -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (message.text.isNotBlank()) MarkdownText(message.text)
            message.toolCalls.forEach { call ->
                Text("→ ${call.name}", fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Role.Tool -> Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)) {
            val failed = message.text.startsWith("Error") || message.summary.orEmpty().contains("declined") ||
                message.summary.orEmpty().contains("rejected")
            Text(if (failed) "✕" else "✓", fontSize = 11.sp,
                color = if (failed) MaterialTheme.colorScheme.error else LocalCodeColors.current.success)
            Spacer(Modifier.width(6.dp))
            Text(message.summary ?: message.toolName.orEmpty(), fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Role.Error -> Text(message.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(6.dp)).padding(8.dp))
    }
}

/** Plain text with fenced code blocks shown as copyable monospace boxes. */
@Composable
private fun MarkdownText(text: String) {
    val clipboard = LocalClipboardManager.current
    val parts = text.split("```")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        parts.forEachIndexed { index, part ->
            if (index % 2 == 0) {
                if (part.isNotBlank()) SelectionContainer {
                    Text(part.trim('\n'), style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                val language = part.substringBefore('\n').trim()
                val body = part.substringAfter('\n', part).trimEnd()
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(6.dp))) {
                    Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(language.ifBlank { "code" }, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = { clipboard.setText(AnnotatedString(body)) },
                            contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Copy", fontSize = 11.sp) }
                    }
                    SelectionContainer {
                        Text(body, fontFamily = FontFamily.Monospace, fontSize = 11.sp, softWrap = false,
                            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 8.dp, end = 8.dp, bottom = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ApprovalCard(pending: PendingApproval, agent: AgentController) {
    val request = pending.request
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(when (request) {
                is ApprovalRequest.Edit -> (if (request.created) "Create " else "Change ") + request.path
                is ApprovalRequest.Delete -> "Delete ${request.path}"
                is ApprovalRequest.Command -> "Run command"
                is ApprovalRequest.Action -> request.title
            }, style = MaterialTheme.typography.titleMedium)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                when (request) {
                    is ApprovalRequest.Edit -> DiffView(request.diff)
                    is ApprovalRequest.Delete -> Text("The agent wants to delete this file.", style = MaterialTheme.typography.bodySmall)
                    is ApprovalRequest.Command -> Text("$ ${request.command}", fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(8.dp))
                    is ApprovalRequest.Action -> Text(request.detail, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { agent.answer(Decision.Deny) }) {
                    Text(if (request is ApprovalRequest.Edit) "Reject" else "Deny", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.weight(1f))
                if (request is ApprovalRequest.Command)
                    OutlinedButton(onClick = { agent.answer(Decision.AlwaysAllow) }) { Text("Always (project)", fontSize = 12.sp) }
                Button(onClick = { agent.answer(Decision.Allow) }) {
                    Text(when (request) { is ApprovalRequest.Edit -> "Apply"; is ApprovalRequest.Command -> "Run once"; else -> "Allow" })
                }
            }
        }
    }
}

@Composable
private fun DiffView(lines: List<DiffLine>) {
    val success = LocalCodeColors.current.success
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).horizontalScroll(rememberScrollState())
        .padding(6.dp)) {
        lines.take(400).forEach { line ->
            val (prefix, color) = when (line.kind) {
                DiffKind.Added -> "+ " to success
                DiffKind.Removed -> "- " to MaterialTheme.colorScheme.error
                DiffKind.Gap -> "" to MaterialTheme.colorScheme.primary
                DiffKind.Same -> "  " to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(prefix + line.text, color = color, fontFamily = FontFamily.Monospace, fontSize = 10.sp, softWrap = false, maxLines = 1)
        }
        if (lines.size > 400) Text("… ${lines.size - 400} more lines", fontSize = 10.sp)
    }
}

// ---- Providers -------------------------------------------------------------------------------------

@Composable
private fun ProvidersTab(agent: AgentController) {
    @Suppress("UNUSED_VARIABLE") val revision = agent.revision
    var expanded by rememberSaveable { mutableStateOf(agent.providerId) }
    val groups = listOf(
        "FREE & FREE TIER" to Providers.all.filter { it.tier == Tier.Free || it.tier == Tier.FreeTier },
        "PRO (PAID)" to Providers.all.filter { it.tier == Tier.Pro },
        "LOCAL & CUSTOM" to Providers.all.filter { it.tier == Tier.Local })
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Text("Use your own API keys. Keys stay in this app's storage on your phone and are sent only to that provider.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        }
        groups.forEach { (title, specs) ->
            item { SectionTitle(title) }
            items(specs, key = { it.id }) { spec ->
                ProviderRow(agent, spec, expanded == spec.id) { expanded = if (expanded == spec.id) null else spec.id }
            }
        }
    }
}

@Composable
private fun ProviderRow(agent: AgentController, spec: ProviderSpec, open: Boolean, onToggle: () -> Unit) {
    val hasKey = agent.hasKey(spec) && (spec.id != Providers.CUSTOM || agent.baseUrl(spec).isNotBlank())
    val selected = agent.providerId == spec.id
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(when {
                selected -> MaterialTheme.colorScheme.primary
                hasKey && (spec.needsKey || agent.modelsFetched(spec)) -> LocalCodeColors.current.success
                else -> MaterialTheme.colorScheme.outlineVariant
            }, RoundedCornerShape(4.dp)))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(spec.name, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.SemiBold else null)
                Text(when {
                    selected -> "In use · ${agent.modelId}"
                    agent.modelsFetched(spec) -> "${agent.models(spec).size} models"
                    spec.needsKey && !agent.hasKey(spec) -> "No key"
                    else -> "Ready to fetch models"
                }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Badge(if (spec.id == Providers.CUSTOM) "Custom" else spec.tier.label, spec.tier != Tier.Pro)
        }
        if (open) ProviderDetails(agent, spec)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun ProviderDetails(agent: AgentController, spec: ProviderSpec) {
    val uri = LocalUriHandler.current
    var key by remember(spec.id, agent.revision) { mutableStateOf(agent.key(spec.id)) }
    var url by remember(spec.id, agent.revision) { mutableStateOf(agent.baseUrl(spec)) }
    var showKey by remember { mutableStateOf(false) }
    var filter by remember(spec.id) { mutableStateOf("") }
    var manual by remember(spec.id) { mutableStateOf("") }
    var limit by remember(spec.id) { mutableIntStateOf(40) }
    val dirty = key != agent.key(spec.id) || (spec.editableUrl && url != agent.baseUrl(spec))
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (spec.note.isNotBlank()) Text(spec.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (spec.needsKey || spec.id == Providers.CUSTOM) OutlinedTextField(key, { key = it.trim() }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, label = { Text(if (spec.needsKey) "API key" else "API key (optional)") },
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show", fontSize = 11.sp) } },
            textStyle = MaterialTheme.typography.bodySmall)
        if (spec.editableUrl) OutlinedTextField(url, { url = it.trim() }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Base URL") }, placeholder = { Text("https://host/v1") }, textStyle = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            spec.keyUrl?.let { link -> OutlinedButton(onClick = { uri.openUri(link) },
                contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Get key", fontSize = 12.sp) } }
            if (dirty) Button(onClick = { agent.saveProvider(spec, key, url) }, contentPadding = PaddingValues(horizontal = 10.dp)) {
                Text("Save", fontSize = 12.sp) }
            Button(onClick = { if (dirty) agent.saveProvider(spec, key, url); agent.fetchModels(spec) },
                enabled = agent.fetching[spec.id] != true, contentPadding = PaddingValues(horizontal = 10.dp)) {
                Text(if (agent.fetching[spec.id] == true) "Loading…" else "Fetch models", fontSize = 12.sp)
            }
        }
        agent.fetchErrors[spec.id]?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        val models = agent.models(spec)
        if (!agent.modelsFetched(spec) && models.isNotEmpty())
            Text("Suggested models. Fetch to load the full list for your key.", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (models.size > 8) OutlinedTextField(filter, { filter = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text("Search ${models.size} models") }, textStyle = MaterialTheme.typography.bodySmall)
        val shown = models.filter { filter.isBlank() || it.id.contains(filter.trim(), ignoreCase = true) }
        shown.take(limit).forEach { model ->
            val active = agent.providerId == spec.id && agent.modelId == model.id
            Row(Modifier.fillMaxWidth().clickable { agent.select(spec, model.id) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = active, onClick = { agent.select(spec, model.id) }, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text(model.id, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (model.free) Badge("free", true)
            }
        }
        if (shown.size > limit) TextButton(onClick = { limit += 60 }) { Text("Show more (${shown.size - limit})") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(manual, { manual = it.trim() }, modifier = Modifier.weight(1f), singleLine = true,
                placeholder = { Text("Or type a model ID") }, textStyle = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { agent.select(spec, manual); manual = "" }, enabled = manual.isNotBlank()) { Text("Use") }
        }
    }
}

@Composable
private fun ModelPickerDialog(agent: AgentController, onDismiss: () -> Unit, onSetup: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val revision = agent.revision
    val configured = Providers.all.filter { agent.hasKey(it) && (it.needsKey || agent.modelsFetched(it)) }
    var filter by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Choose model") },
        text = {
            Column(Modifier.heightIn(max = 460.dp)) {
                OutlinedTextField(filter, { filter = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search") }, textStyle = MaterialTheme.typography.bodySmall)
                if (configured.isEmpty()) Text("No provider has a key yet.", Modifier.padding(top = 10.dp),
                    style = MaterialTheme.typography.bodySmall)
                LazyColumn {
                    configured.forEach { spec ->
                        val models = agent.models(spec).filter { filter.isBlank() || it.id.contains(filter.trim(), true) ||
                            spec.name.contains(filter.trim(), true) }
                        if (models.isNotEmpty()) item(key = "h_" + spec.id) { SectionTitle(spec.name.uppercase()) }
                        items(models.take(80), key = { spec.id + "/" + it.id }) { model ->
                            Row(Modifier.fillMaxWidth().clickable { agent.select(spec, model.id); onDismiss() }
                                .padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(model.id, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (agent.providerId == spec.id && agent.modelId == model.id) FontWeight.Bold else null)
                                if (model.free) Badge("free", true)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onSetup) { Text("Manage providers") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

// ---- Settings ------------------------------------------------------------------------------------

@Composable
private fun SettingsTab(agent: AgentController) {
    @Suppress("UNUSED_VARIABLE") val revision = agent.revision
    var instructions by remember { mutableStateOf(agent.instructions) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingSwitch("Apply file changes without asking", "Off: every edit shows a diff to Apply or Reject.",
            agent.autoApplyEdits, agent::updateAutoApply)
        SettingSwitch("Send the open file as context", "The file open in the editor is included with each message.",
            agent.includeOpenFile, agent::updateIncludeOpenFile)
        Column {
            Text("Commands", style = MaterialTheme.typography.bodyMedium)
            Text(if (agent.commandsAlwaysAllowed()) "Commands run without asking in this project." else
                "Each command asks Run once / Always (project) / Deny.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (agent.commandsAlwaysAllowed()) TextButton(onClick = agent::revokeCommandPermission,
                contentPadding = PaddingValues(0.dp)) { Text("Ask again for every command") }
        }
        Column {
            Text("Max steps per request: ${agent.maxSteps}", style = MaterialTheme.typography.bodyMedium)
            Slider(value = agent.maxSteps.toFloat(), onValueChange = { agent.updateMaxSteps(it.toInt()) }, valueRange = 5f..60f)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Custom instructions", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(instructions, { instructions = it.take(4_000) }, modifier = Modifier.fillMaxWidth(), minLines = 3,
                placeholder = { Text("e.g. Answer in Urdu. Use Kotlin.") }, textStyle = MaterialTheme.typography.bodySmall)
            if (instructions != agent.instructions) Button(onClick = { agent.updateInstructions(instructions) }) { Text("Save") }
            Text("A project can also have AGENTS.md or CODRIOD.md; the agent reads it as project rules.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = agent::clear, enabled = agent.messages.isNotEmpty()) { Text("Clear this project's chat") }
        Text("API keys are your own and are stored in this app's private storage on this phone. They are only sent to the " +
            "provider they belong to. Git credentials are never shared with the agent.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onChange(!value) }) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = value, onCheckedChange = onChange)
    }
}
