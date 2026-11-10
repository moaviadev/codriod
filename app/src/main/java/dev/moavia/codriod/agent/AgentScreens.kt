package dev.moavia.codriod.agent

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
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
    val onClick: (() -> Unit)? = null, val logo: String = title.take(1), val color: Long = 0xFF3F3F46,
    val gradient: Boolean = false, val isNew: Boolean = false)

/** Add-ons: a card per add-on with its logo and status. Codriod AI first; Linux & AI CLIs is highlighted while new. */
@Composable
fun AddonsScreen(agent: AgentController, onBack: () -> Unit, onOpenAgent: () -> Unit, onOpenGit: () -> Unit,
    linuxStatus: String = "Set up", onOpenLinux: (() -> Unit)? = null) {
    val code = LocalCodeColors.current
    val agentStatus = agent.provider?.let { p -> agent.modelId?.let { "${p.name} · $it" } } ?: "Set up"
    val rows = listOf(
        AddonRow("Codriod AI", "AI chat and coding agent · ${Providers.all.size} providers", agentStatus, true, onOpenAgent,
            logo = "✦", gradient = true),
        AddonRow("GitHub", "Your token, all repos, clone, pull, push", "Built in", true, onOpenGit, logo = "⌥", color = 0xFF24292F),
        AddonRow("Linux & AI CLIs", "Ubuntu, Node.js, Python + Claude Code, Codex, Gemini, Copilot…",
            linuxStatus, onOpenLinux != null, onOpenLinux, logo = "🐧", color = 0xFFE95420, isNew = linuxStatus == "Set up"),
        AddonRow("Java & Kotlin", "JDK, Kotlin and Gradle tools", null, false, logo = "J"),
        AddonRow("More extensions", "Language servers, debuggers and themes", null, false, logo = "⧉")
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
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { SectionTitle("INSTALLED") }
            items(rows.filter { it.available }) { AddonCard(it) }
            item { SectionTitle("COMING SOON") }
            items(rows.filterNot { it.available }) { AddonCard(it) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 2.dp, top = 12.dp))
}

@Composable
private fun AddonCard(row: AddonRow) {
    val accent = Color(row.color)
    val shape = RoundedCornerShape(14.dp)
    Row(Modifier.fillMaxWidth().alpha(if (row.available) 1f else 0.55f).clip(shape)
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
        .border(if (row.isNew) 1.5.dp else 1.dp, if (row.isNew) accent else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), shape)
        .clickable(enabled = row.onClick != null) { row.onClick?.invoke() }
        .padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp))
            .background(if (row.gradient) Brush.linearGradient(listOf(Color(0xFF7C3AED), Color(0xFF2563EB))) else SolidColor(accent)),
            contentAlignment = Alignment.Center) {
            Text(row.logo, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 17.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                if (row.isNew) Text("NEW", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFDBA74),
                    modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF3B1D12)).padding(horizontal = 5.dp, vertical = 1.dp))
            }
            Text(row.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        if (row.onClick != null) Text((row.status ?: "") + " ›", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (row.isNew || row.status == "Set up") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 120.dp))
        else Badge("Soon", false)
    }
}

@Composable
private fun Badge(text: String, accent: Boolean) {
    Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp,
        color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.widthIn(max = 130.dp).border(1.dp, if (accent) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
}


// ---------------------------------------------------------------------------------------------------
// Agent (full screen, from Add-ons)
// ---------------------------------------------------------------------------------------------------

@Composable
fun AgentScreen(agent: AgentController, projectName: String?, onBack: () -> Unit, onTakeOver: () -> Unit = {}) {
    var settings by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(AgentColors.Panel)) {
        if (settings) AgentSettingsScreen(agent, onBack = { settings = false })
        else AgentChat(agent, projectName, onClose = onBack, closeIcon = ToolIcon.Back, onOpenSettings = { settings = true }, onTakeOver = onTakeOver)
    }
}

// ---- Providers -------------------------------------------------------------------------------------

@Composable
internal fun ProvidersTab(agent: AgentController) {
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

