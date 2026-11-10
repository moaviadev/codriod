package dev.moavia.codriod.agent

import android.content.Intent
import android.graphics.BitmapFactory
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.moavia.codriod.workspace.ToolGlyph
import dev.moavia.codriod.workspace.ToolIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The agent's own dark palette (zinc + violet), the same in light and dark app themes. */
object AgentColors {
    val Panel = Color(0xFF18181B)
    val Card = Color(0xFF202024)
    val Raised = Color(0xFF27272A)
    val Line = Color(0xFF33333A)
    val Text = Color(0xFFE4E4E7)
    val Muted = Color(0xFFA1A1AA)
    val Dim = Color(0xFF71717A)
    val Violet = Color(0xFF7C3AED)
    val VioletSoft = Color(0xFFC4B5FD)
    val Green = Color(0xFF4ADE80)
    val Red = Color(0xFFF87171)
    val Amber = Color(0xFFFBBF24)
    val Blue = Color(0xFF3794FF)
}
private val C = AgentColors
private val Mono = FontFamily.Monospace

private val providerColors = mapOf("anthropic" to 0xFFD97757, "openai" to 0xFF10A37F, "gemini" to 0xFF4285F4, "groq" to 0xFFF55036,
    "openrouter" to 0xFF6D28D9, "mistral" to 0xFFFF7000, "deepseek" to 0xFF4D6BFE, "xai" to 0xFF111111, "cerebras" to 0xFFE8532F,
    "github" to 0xFF24292F, "huggingface" to 0xFFFFB000, "nvidia" to 0xFF76B900, "together" to 0xFF0F6FFF, "fireworks" to 0xFF6720FF,
    "perplexity" to 0xFF20808D, "cohere" to 0xFF39594D, "sambanova" to 0xFFEE7624, "ollama" to 0xFF3F3F46, "lmstudio" to 0xFF4338CA)

@Composable
fun ProviderLogo(spec: ProviderSpec?, size: Int = 18) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 3.2).dp)).background(Color(providerColors[spec?.id] ?: 0xFF52525B)),
        contentAlignment = Alignment.Center) {
        Text((spec?.name ?: "?").take(1), color = Color.White, fontSize = (size * 0.5).sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun Pill(text: String, modifier: Modifier = Modifier, active: Boolean = false, onClick: (() -> Unit)? = null) {
    Text(text, fontSize = 11.5.sp, color = if (active) C.VioletSoft else Color(0xFFD4D4D8), maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(9.dp)).background(if (active) Color(0xFF2E2A45) else Color(0xFF2A2A31))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 9.dp, vertical = 5.dp))
}

private fun modeColor(mode: AgentMode) = when (mode) {
    AgentMode.Ask -> C.Blue to Color(0xFF14263F)
    AgentMode.Plan -> C.Amber to Color(0xFF3A2F12)
    AgentMode.Agent -> C.VioletSoft to Color(0xFF2A2340)
    AgentMode.Autopilot -> C.Green to Color(0xFF14321F)
}

@Composable
private fun rememberImage(path: String, maxSize: Int = 480): ImageBitmap? {
    val state = produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (bounds.outWidth / sample > maxSize * 2) sample *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    return state.value
}

@Composable
private fun Thumb(path: String, w: Int, h: Int, onRemove: (() -> Unit)? = null) {
    val image = rememberImage(path)
    Box(Modifier.size(w.dp, h.dp).clip(RoundedCornerShape(8.dp)).background(C.Raised).border(1.dp, Color(0xFF3F3F46), RoundedCornerShape(8.dp))) {
        if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (onRemove != null) Box(Modifier.align(Alignment.TopEnd).padding(3.dp).size(18.dp).background(Color(0xCC000000), CircleShape)
            .clickable(onClick = onRemove), contentAlignment = Alignment.Center) { Text("✕", fontSize = 9.sp, color = Color.White) }
    }
}

// ===================================================================================================
// Chat
// ===================================================================================================

/**
 * The agent chat used in the editor's side panel and full screen from Add-ons: model and mode switch, plan,
 * steps, diffs, approvals, live browser, undo, attachments and streaming.
 */
@Composable
fun AgentChat(
    agent: AgentController,
    projectName: String?,
    onClose: () -> Unit,
    closeIcon: ToolIcon = ToolIcon.Close,
    expanded: Boolean = true,
    onToggleExpand: (() -> Unit)? = null,
    onOpenSettings: () -> Unit,
    onTakeOver: () -> Unit
) {
    var picker by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    var attach by remember { mutableStateOf(false) }
    var contextSheet by remember { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }

    if (!agent.ready && !agent.onboarded) {
        AgentOnboarding(agent, onClose = onClose)
        return
    }
    Column(Modifier.fillMaxSize().background(C.Panel)) {
        ChatHeader(agent, onModel = { picker = true }, onHistory = { history = true }, onClose = onClose, closeIcon = closeIcon,
            expanded = expanded, onToggleExpand = onToggleExpand, onOpenSettings = onOpenSettings)
        ContextStrip(agent, projectName)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            MessageList(agent, onTakeOver = onTakeOver, onSuggestion = { input = it })
        }
        agent.pending?.let { ApprovalCard(it, agent) }
        if (agent.planAwaitingApproval && !agent.running) PlanApproval(agent, onEdit = { input = "Change the plan: " })
        Composer(agent, input, { input = it }, onAttach = { attach = true }, onContext = { contextSheet = true },
            onSend = { agent.send(input); input = "" })
    }
    if (picker) ModelPickerSheet(agent, onDismiss = { picker = false }, onAddProvider = { picker = false; onOpenSettings() })
    if (history) HistorySheet(agent, onDismiss = { history = false })
    if (attach) AttachSheet(agent, onDismiss = { attach = false })
    if (contextSheet) ContextSheet(agent, onDismiss = { contextSheet = false })
}

@Composable
private fun ChatHeader(agent: AgentController, onModel: () -> Unit, onHistory: () -> Unit, onClose: () -> Unit, closeIcon: ToolIcon,
    expanded: Boolean, onToggleExpand: (() -> Unit)?, onOpenSettings: () -> Unit) {
    var modeMenu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (closeIcon == ToolIcon.Back) HeaderIcon(ToolIcon.Back, "Back", onClose)
        Row(Modifier.weight(1f, fill = false).clip(RoundedCornerShape(10.dp)).background(Color(0xFF232329)).border(1.dp, C.Line, RoundedCornerShape(10.dp))
            .clickable(onClick = onModel).padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            ProviderLogo(agent.provider, 16)
            Spacer(Modifier.width(7.dp))
            Text(agent.modelId ?: "Choose model", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
            Text("  ▾", color = C.Muted, fontSize = 10.sp)
        }
        Spacer(Modifier.width(6.dp))
        Box {
            val (fg, bg) = modeColor(agent.mode)
            Text(agent.mode.label + " ▾", fontSize = 11.sp, color = fg, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(bg).clickable(enabled = !agent.running) { modeMenu = true }
                    .padding(horizontal = 8.dp, vertical = 5.dp))
            ModeMenu(modeMenu, agent.mode, { modeMenu = false }) { agent.updateMode(it) }
        }
        Spacer(Modifier.weight(1f))
        HeaderIcon(ToolIcon.History, "Chats", onHistory)
        HeaderIcon(ToolIcon.Plus, "New chat") { agent.newChat() }
        HeaderIcon(ToolIcon.Settings, "Agent settings", onOpenSettings)
        if (onToggleExpand != null) HeaderIcon(ToolIcon.Fullscreen, if (expanded) "Make smaller" else "Full screen", onToggleExpand)
        if (closeIcon != ToolIcon.Back) HeaderIcon(ToolIcon.Close, "Close", onClose)
    }
    HorizontalDivider(color = Color(0xFF26262C))
}

@Composable
private fun HeaderIcon(icon: ToolIcon, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) { ToolGlyph(icon, C.Muted, Modifier.size(17.dp)) }
}

@Composable
private fun ModeMenu(open: Boolean, current: AgentMode, onDismiss: () -> Unit, onPick: (AgentMode) -> Unit) {
    DropdownMenu(open, onDismiss, modifier = Modifier.background(Color(0xFF232329)).widthIn(max = 300.dp)) {
        AgentMode.entries.forEach { m ->
            val (fg, _) = modeColor(m)
            DropdownMenuItem(text = {
                Column {
                    Text((if (m == current) "✓ " else "") + m.label, color = fg, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(m.hint, color = C.Muted, fontSize = 11.sp)
                }
            }, onClick = { onPick(m); onDismiss() })
        }
    }
}

@Composable
private fun ContextStrip(agent: AgentController, projectName: String?) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        projectName?.let { Chip("📁 $it") }
        if (agent.includeOpenFile) Chip("📄 open file") { agent.updateIncludeOpenFile(false) } else Chip("+ open file", dim = true) { agent.updateIncludeOpenFile(true) }
        if (agent.browserEnabled) Chip("🌐 Browser")
        Chip("⌘ Terminal")
        if (agent.totalTokens > 0) Text("${formatTokens(agent.totalTokens)} tokens", fontSize = 10.5.sp, color = C.Dim)
    }
    HorizontalDivider(color = Color(0xFF26262C))
}

private fun formatTokens(n: Int) = if (n >= 1000) String.format("%.1fk", n / 1000.0) else n.toString()

@Composable
private fun Chip(text: String, dim: Boolean = false, onClick: (() -> Unit)? = null) {
    Text(text, fontSize = 10.5.sp, color = if (dim) C.Dim else C.Muted, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(Color(0xFF232329))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 8.dp, vertical = 3.dp))
}

// ---- messages ------------------------------------------------------------------------------------

private sealed class Row2 {
    data class Msg(val index: Int, val message: ChatMessage) : Row2()
    object Streaming : Row2()
    object Live : Row2()
    object Changes : Row2()
    object Empty : Row2()
}

@Composable
private fun MessageList(agent: AgentController, onTakeOver: () -> Unit, onSuggestion: (String) -> Unit) {
    val state = rememberLazyListState()
    val rows = buildList {
        if (agent.messages.isEmpty() && !agent.running) add(Row2.Empty)
        agent.messages.forEachIndexed { i, m ->
            if (m.role == Role.Assistant && m.text.isBlank()) return@forEachIndexed
            add(Row2.Msg(i, m))
        }
        if (agent.running && agent.usingBrowser) add(Row2.Live)
        if (agent.running) add(Row2.Streaming)
        if (!agent.running && agent.lastChanges.isNotEmpty()) add(Row2.Changes)
    }
    LaunchedEffect(rows.size, agent.streaming.length / 200, agent.pending) { if (rows.isNotEmpty()) state.animateScrollToItem(rows.lastIndex) }
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        items(rows.size) { i ->
            when (val row = rows[i]) {
                is Row2.Empty -> EmptyState(agent, onSuggestion)
                is Row2.Msg -> MessageView(agent, row.message, isLast = row.index == agent.messages.lastIndex)
                Row2.Streaming -> StreamingView(agent)
                Row2.Live -> LiveBrowserCard(agent, onTakeOver)
                Row2.Changes -> ChangesCard(agent)
            }
        }
    }
}

@Composable
private fun EmptyState(agent: AgentController, onSuggestion: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(C.Violet, Color(0xFF2563EB)))),
            contentAlignment = Alignment.Center) { Text("✦", color = Color.White, fontSize = 20.sp) }
        Text("What should we build?", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(agent.mode.hint, color = C.Muted, fontSize = 12.5.sp)
        listOf("✨ Explain this project", "🐞 Find and fix bugs in the open file", "🎨 Build a page like the screenshot I attach",
            "🌐 Open my site in the browser and check for errors", "📦 Zip the project and share it", "🔐 Configure GitHub login for my account")
            .forEach { s -> Pill(s, onClick = { onSuggestion(s.substringAfter(' ')) }) }
    }
}

@Composable
private fun MessageView(agent: AgentController, message: ChatMessage, isLast: Boolean) {
    when (message.role) {
        Role.User -> UserBubble(message)
        Role.Assistant -> Column {
            MarkdownBlock(message.text)
            if (!agent.running && isLast) CopyRow(message.text)
        }
        Role.Tool -> ToolRow(message)
        Role.Error -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF2A1515))
            .border(1.dp, Color(0xFF5B2121), RoundedCornerShape(10.dp)).padding(10.dp)) {
            Text(message.text, color = Color(0xFFFCA5A5), fontSize = 12.sp)
            if (isLast && !agent.running) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("↻ Retry", onClick = agent::retry)
                if (message.text.contains("steps")) Pill("Continue", onClick = { agent.send("continue") })
            }
        }
    }
}

@Composable
private fun CopyRow(text: String) {
    val clipboard = LocalClipboardManager.current
    Row(Modifier.padding(top = 4.dp)) {
        Text("Copy", fontSize = 10.5.sp, color = C.Dim, modifier = Modifier.clickable { clipboard.setText(AnnotatedString(text)) }.padding(4.dp))
    }
}

@Composable
private fun UserBubble(message: ChatMessage) {
    val contexts = message.detail?.takeIf { it.startsWith("CTX ") }?.removePrefix("CTX ")?.split('|').orEmpty()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp)).background(Color(0xFF27272F))
            .border(1.dp, C.Line, RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp)).padding(horizontal = 10.dp, vertical = 8.dp)) {
            if (message.images.isNotEmpty()) Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                message.images.take(4).forEach { Thumb(it, 70, 92) }
            }
            if (contexts.isNotEmpty()) Row(Modifier.padding(bottom = 5.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                contexts.forEach { Chip(it) }
            }
            SelectionContainer { Text(message.text, color = C.Text, fontSize = 13.sp, lineHeight = 19.sp) }
        }
    }
}

private fun toolIcon(name: String?) = when {
    name == null -> "•"
    name.startsWith("browser_screenshot") -> "📸"
    name.startsWith("browser") -> "🌐"
    name == "run_command" -> "⌘"
    name == "read_file" || name == "list_files" || name == "search_files" -> "📄"
    name == "write_file" || name == "edit_file" -> "✎"
    name == "delete_file" || name == "rename_file" -> "🗂"
    name == "fetch_url" -> "🔗"
    name == "update_plan" -> "☑"
    name.startsWith("git") || name.startsWith("github") -> "⑂"
    name == "view_image" -> "🖼"
    else -> "•"
}

@Composable
private fun ToolRow(message: ChatMessage) {
    val detail = message.detail
    when {
        message.toolName == "update_plan" -> PlanJson.decode(detail)?.let { (title, steps) -> PlanCard(title, steps); return }
        detail?.startsWith("DIFF ") == true -> { DiffCard(detail); return }
        detail?.startsWith("IMAGE ") == true -> {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StepLine(message, expandable = false)
                Thumb(detail.removePrefix("IMAGE "), 120, 200)
            }
            return
        }
    }
    StepLine(message, expandable = true)
}

@Composable
private fun StepLine(message: ChatMessage, expandable: Boolean) {
    var open by remember { mutableStateOf(false) }
    val failed = message.text.startsWith("Error") || message.summary.orEmpty().let { it.contains("declined") || it.contains("rejected") || it.contains("blocked") || it.contains("failed") }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Color(0xFF1D1D22)).border(1.dp, Color(0xFF2A2A30), RoundedCornerShape(9.dp))
        .clickable(enabled = expandable) { open = !open }.padding(horizontal = 9.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(toolIcon(message.toolName), fontSize = 11.sp, color = C.Muted, modifier = Modifier.width(18.dp))
            Text(message.summary ?: message.toolName.orEmpty(), color = Color(0xFFD4D4D8), fontSize = 11.5.sp, maxLines = if (open) 4 else 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(if (failed) "✕" else "✓", color = if (failed) C.Red else C.Green, fontSize = 11.sp)
            if (expandable) Text(if (open) " ▴" else " ▾", color = C.Dim, fontSize = 10.sp)
        }
        if (open) SelectionContainer {
            Text((message.detail ?: message.text).take(6000), color = C.Muted, fontFamily = Mono, fontSize = 10.5.sp, lineHeight = 15.sp,
                modifier = Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color(0xFF0F0F12)).padding(8.dp))
        }
    }
}

@Composable
private fun PlanCard(title: String, steps: List<PlanStep>) {
    val done = steps.count { it.status == StepStatus.Done }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Card).border(1.dp, C.Line, RoundedCornerShape(12.dp)).padding(11.dp)) {
        Row {
            Text(title.uppercase(), fontSize = 10.5.sp, color = C.Muted, letterSpacing = 0.6.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$done / ${steps.size}", fontSize = 10.5.sp, color = C.Muted)
        }
        Spacer(Modifier.height(6.dp))
        steps.forEach { step ->
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 1.dp).size(15.dp).clip(RoundedCornerShape(5.dp))
                    .background(if (step.status == StepStatus.Done) C.Green else Color.Transparent)
                    .border(1.5.dp, when (step.status) { StepStatus.Done -> C.Green; StepStatus.InProgress -> C.Blue; else -> Color(0xFF52525B) }, RoundedCornerShape(5.dp)),
                    contentAlignment = Alignment.Center) {
                    if (step.status == StepStatus.Done) Text("✓", fontSize = 9.sp, color = Color(0xFF052E16), fontWeight = FontWeight.Bold)
                    if (step.status == StepStatus.InProgress) Box(Modifier.size(7.dp).background(C.Blue, RoundedCornerShape(2.dp)))
                }
                Spacer(Modifier.width(8.dp))
                Text(step.text, fontSize = 12.sp, color = if (step.status == StepStatus.Done) C.Dim else C.Text,
                    textDecoration = if (step.status == StepStatus.Done) TextDecoration.LineThrough else null)
            }
        }
    }
}

@Composable
private fun DiffCard(detail: String) {
    var open by remember { mutableStateOf(false) }
    val lines = detail.lines()
    val header = lines.first().removePrefix("DIFF ").split(' ')
    val path = header.dropLast(2).joinToString(" ")
    val changed = lines.drop(1).filter { it.startsWith("+") || it.startsWith("-") }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF141417)).border(1.dp, C.Line, RoundedCornerShape(10.dp))) {
        Row(Modifier.fillMaxWidth().background(Color(0xFF1D1D22)).clickable { open = !open }.padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("✎ ", color = C.Muted, fontSize = 11.sp)
            Text(path, color = C.Text, fontFamily = Mono, fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(header.getOrNull(header.size - 2).orEmpty(), color = C.Green, fontSize = 11.sp, fontFamily = Mono)
            Text(" " + header.lastOrNull().orEmpty().replace("-", "−"), color = C.Red, fontSize = 11.sp, fontFamily = Mono)
            Text(if (open) "  ▴" else "  View ▾", color = C.Dim, fontSize = 10.5.sp)
        }
        val shown = if (open) lines.drop(1) else changed.take(4)
        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            shown.take(300).forEach { l ->
                val (bg, fg) = when {
                    l.startsWith("+") -> Color(0x1A4ADE80) to Color(0xFFBBF7D0)
                    l.startsWith("-") -> Color(0x1AF87171) to Color(0xFFFECACA)
                    l.startsWith("…") -> Color.Transparent to C.Blue
                    else -> Color.Transparent to C.Dim
                }
                Text(l.ifEmpty { " " }, color = fg, fontFamily = Mono, fontSize = 10.5.sp, softWrap = false,
                    modifier = Modifier.widthIn(min = 320.dp).background(bg).padding(horizontal = 9.dp))
            }
        }
    }
}

@Composable
private fun StreamingView(agent: AgentController) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (agent.streaming.isNotBlank()) MarkdownBlock(agent.streaming + " ▍")
        if (agent.pending == null) Row(verticalAlignment = Alignment.CenterVertically) {
            val rotation by rememberInfiniteTransition(label = "spin").animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "r")
            Box(Modifier.size(12.dp).rotate(rotation).border(2.dp, Brush.sweepGradient(listOf(Color.Transparent, C.VioletSoft)), CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(when {
                agent.streaming.isNotBlank() -> "Writing…"
                agent.messages.lastOrNull()?.role == Role.Tool -> "Working…"
                else -> "Thinking…"
            }, color = C.Muted, fontSize = 11.5.sp)
            if (agent.mode == AgentMode.Autopilot) Text("  · Autopilot", color = C.Green, fontSize = 11.sp)
        }
    }
}

@Composable
private fun LiveBrowserCard(agent: AgentController, onTakeOver: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF141417)).border(1.dp, Color(0xFF3B4A7A), RoundedCornerShape(12.dp))) {
        Row(Modifier.fillMaxWidth().background(Color(0xFF1C1F2B)).padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(Color(0xFFEF4444), CircleShape))
            Spacer(Modifier.width(7.dp))
            Text("Agent is using the browser", color = Color(0xFFC7D2FE), fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text("Take over", color = Color(0xFFA5B4FC), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { agent.stop(); onTakeOver() }.padding(2.dp))
        }
        agent.liveShot?.let { path ->
            val image = rememberImage(path, 600)
            if (image != null) Image(image, "Browser", Modifier.fillMaxWidth().heightIn(max = 220.dp), contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter)
        } ?: Text("Waiting for the first screenshot…", color = C.Dim, fontSize = 11.sp, modifier = Modifier.padding(10.dp))
    }
}

@Composable
private fun ChangesCard(agent: AgentController) {
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Card).border(1.dp, C.Line, RoundedCornerShape(12.dp)).padding(11.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${agent.lastChanges.size} file${if (agent.lastChanges.size == 1) "" else "s"} changed", color = Color.White, fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Pill("↶ Undo all", onClick = { confirm = true })
        }
        agent.lastChanges.entries.take(8).forEach { (path, original) ->
            Text((if (original == null) "+ " else "✎ ") + path, color = C.Muted, fontFamily = Mono, fontSize = 10.5.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Undo these changes?") },
        text = { Text("Files the agent changed go back to how they were; files it created are deleted. Command results (e.g. a zip) stay.") },
        confirmButton = { TextButton(onClick = { confirm = false; agent.undoLastRun() }) { Text("Undo", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
}

@Composable
private fun PlanApproval(agent: AgentController, onEdit: () -> Unit) {
    Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF1C1A26))
        .border(1.dp, Color(0xFF4C3F7A), RoundedCornerShape(14.dp)).padding(10.dp)) {
        Text("Plan ready · ${agent.plan.size} steps", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text("Nothing has changed yet. Approve to start building.", color = C.Muted, fontSize = 11.5.sp)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f), shape = RoundedCornerShape(9.dp)) { Text("Edit plan", fontSize = 12.sp) }
            Button(onClick = { agent.approvePlan() }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(9.dp),
                colors = ButtonDefaults.buttonColors(containerColor = C.Violet)) { Text("Approve & build", fontSize = 12.sp) }
        }
        TextButton(onClick = { agent.approvePlan(AgentMode.Autopilot) }, contentPadding = PaddingValues(0.dp)) {
            Text("Approve & run on Autopilot", fontSize = 11.5.sp, color = C.Green)
        }
    }
}

@Composable
private fun ApprovalCard(pending: PendingApproval, agent: AgentController) {
    val request = pending.request
    val danger = request is ApprovalRequest.Command && request.dangerous
    Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp).fillMaxWidth().heightIn(max = 380.dp).clip(RoundedCornerShape(12.dp))
        .background(if (danger) Color(0xFF2A1515) else Color(0xFF1C1F2B)).border(1.dp, if (danger) Color(0xFF7F1D1D) else Color(0xFF3B4A7A), RoundedCornerShape(12.dp))
        .padding(10.dp)) {
        Text(when (request) {
            is ApprovalRequest.Edit -> (if (request.created) "Create " else "Edit ") + request.path + "?"
            is ApprovalRequest.Delete -> "Delete ${request.path}?"
            is ApprovalRequest.Command -> if (danger) "⚠ Dangerous command" else "Run this command?"
            is ApprovalRequest.Action -> request.title + "?"
        }, color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            when (request) {
                is ApprovalRequest.Edit -> {
                    val (a, r) = LineDiff.stats(request.diff)
                    DiffCard(diffDetail(request.path, request.diff, a, r))
                }
                is ApprovalRequest.Delete -> Text("The file is removed from the project.", color = C.Muted, fontSize = 11.5.sp)
                is ApprovalRequest.Command -> Text("$ ${request.command}", color = C.Text, fontFamily = Mono, fontSize = 11.5.sp,
                    modifier = Modifier.padding(top = 7.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF0F0F12)).padding(8.dp))
                is ApprovalRequest.Action -> Text(request.detail, color = C.Muted, fontSize = 11.5.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (request is ApprovalRequest.Command) Text(if (danger) "This command can destroy data. It always needs your approval, even on Autopilot."
            else "Runs in the project folder with the phone's shell.", color = if (danger) Color(0xFFFCA5A5) else C.Dim, fontSize = 10.5.sp,
            modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { agent.answer(Decision.Deny) }, modifier = Modifier.weight(1f)) {
                Text(if (request is ApprovalRequest.Edit) "Reject" else "Deny", color = Color(0xFFFCA5A5), fontSize = 12.sp)
            }
            if (request is ApprovalRequest.Command && !danger) OutlinedButton(onClick = { agent.answer(Decision.AlwaysAllow) },
                modifier = Modifier.weight(1f), shape = RoundedCornerShape(9.dp), contentPadding = PaddingValues(4.dp)) { Text("Always", fontSize = 12.sp) }
            Button(onClick = { agent.answer(Decision.Allow) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(9.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (danger) Color(0xFFB91C1C) else Color(0xFF2563EB)), contentPadding = PaddingValues(4.dp)) {
                Text(when (request) { is ApprovalRequest.Edit -> "Apply"; is ApprovalRequest.Command -> "Run"; else -> "Allow" }, fontSize = 12.sp)
            }
        }
    }
}

// ---- composer ------------------------------------------------------------------------------------

@Composable
private fun Composer(agent: AgentController, input: String, onInput: (String) -> Unit, onAttach: () -> Unit, onContext: () -> Unit, onSend: () -> Unit) {
    var modeMenu by remember { mutableStateOf(false) }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { onInput((input + " " + it).trim()) }
    }
    Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
        .background(Color(0xFF202026)).border(1.dp, Color(0xFF3A3A42), RoundedCornerShape(16.dp)).padding(horizontal = 10.dp, vertical = 9.dp)) {
        if (agent.attachedImages.isNotEmpty() || agent.attachedContext.isNotEmpty()) Row(Modifier.padding(bottom = 7.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            agent.attachedImages.toList().forEach { path -> Thumb(path, 46, 46) { agent.attachedImages.remove(path) } }
            agent.attachedContext.toList().forEach { item -> Chip(item.label + "  ✕") { agent.attachedContext.remove(item) } }
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 34.dp, max = 160.dp)) {
            if (input.isEmpty()) Text(if (agent.running) "Agent is working… you can type the next message" else "Ask Codriod Agent…", color = C.Dim, fontSize = 13.sp)
            BasicTextField(input, onInput, textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 13.sp, lineHeight = 19.sp),
                cursorBrush = SolidColor(C.VioletSoft), modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()))
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("＋", onClick = onAttach)
            Pill("@ Context", onClick = onContext)
            Box {
                val (fg, _) = modeColor(agent.mode)
                Text(when (agent.mode) { AgentMode.Ask -> "💬 Ask"; AgentMode.Plan -> "🧭 Plan"; AgentMode.Agent -> "⚡ Agent"; AgentMode.Autopilot -> "🚀 Auto" } + " ▾",
                    fontSize = 11.5.sp, color = fg, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(Color(0xFF2A2A31)).clickable(enabled = !agent.running) { modeMenu = true }
                        .padding(horizontal = 9.dp, vertical = 5.dp))
                ModeMenu(modeMenu, agent.mode, { modeMenu = false }) { agent.updateMode(it) }
            }
            Pill("🎤", onClick = {
                runCatching {
                    voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_PROMPT, "Tell Codriod Agent what to do"))
                }
            })
            Spacer(Modifier.weight(1f))
            val canSend = !agent.running && (input.isNotBlank() || agent.attachedImages.isNotEmpty())
            Box(Modifier.size(34.dp).clip(CircleShape).background(if (agent.running) Color(0xFF3F3F46) else if (canSend) Color.White else Color(0xFF3F3F46))
                .clickable(enabled = agent.running || canSend) { if (agent.running) agent.stop() else onSend() }, contentAlignment = Alignment.Center) {
                if (agent.running) Box(Modifier.size(11.dp).background(Color.White, RoundedCornerShape(2.dp)))
                else Text("↑", color = if (canSend) Color(0xFF111111) else C.Dim, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ---- markdown --------------------------------------------------------------------------------------

/** Headings, lists, bold, inline code and fenced code blocks with a Copy button. */
@Composable
fun MarkdownBlock(text: String) {
    val clipboard = LocalClipboardManager.current
    val parts = text.split("```")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        parts.forEachIndexed { index, part ->
            if (index % 2 == 0) {
                if (part.isNotBlank()) SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        part.trim('\n').lines().forEach { line -> MarkdownLine(line) }
                    }
                }
            } else {
                val language = part.substringBefore('\n').trim()
                val body = part.substringAfter('\n', part).trimEnd()
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF0F0F12)).border(1.dp, Color(0xFF2A2A30), RoundedCornerShape(8.dp))) {
                    Row(Modifier.fillMaxWidth().background(Color(0xFF1A1A1E)).padding(start = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(language.ifBlank { "code" }, fontSize = 10.sp, color = C.Dim, modifier = Modifier.weight(1f))
                        Text("Copy", fontSize = 10.5.sp, color = C.Muted, modifier = Modifier.clickable { clipboard.setText(AnnotatedString(body)) }.padding(8.dp))
                    }
                    SelectionContainer {
                        Text(body, fontFamily = Mono, fontSize = 11.sp, color = Color(0xFFE4E4E7), softWrap = false, lineHeight = 16.sp,
                            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(9.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownLine(line: String) {
    val trimmed = line.trimStart()
    when {
        trimmed.isEmpty() -> Spacer(Modifier.height(2.dp))
        trimmed.startsWith("#") -> Text(inline(trimmed.trimStart('#').trim()), color = Color.White, fontWeight = FontWeight.Bold,
            fontSize = if (trimmed.startsWith("##")) 14.sp else 15.5.sp, modifier = Modifier.padding(top = 4.dp))
        Regex("^([-*+]|\\d+\\.)\\s+").containsMatchIn(trimmed) -> {
            val marker = Regex("^([-*+]|\\d+\\.)\\s+").find(trimmed)!!.value
            Row(Modifier.padding(start = ((line.length - trimmed.length) * 4).dp)) {
                Text(if (marker.trim().first().isDigit()) marker.trim() + " " else "•  ", color = C.Muted, fontSize = 13.sp)
                Text(inline(trimmed.removePrefix(marker)), color = C.Text, fontSize = 13.sp, lineHeight = 19.sp)
            }
        }
        trimmed.startsWith(">") -> Text(inline(trimmed.removePrefix(">").trim()), color = C.Muted, fontSize = 12.5.sp,
            modifier = Modifier.border(width = 0.dp, color = Color.Transparent).padding(start = 8.dp))
        else -> Text(inline(line), color = C.Text, fontSize = 13.sp, lineHeight = 19.sp)
    }
}

private fun inline(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) && text.indexOf("**", i + 2) > i -> {
                val end = text.indexOf("**", i + 2)
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) { append(text.substring(i + 2, end)) }
                i = end + 2
            }
            text[i] == '`' && text.indexOf('`', i + 1) > i -> {
                val end = text.indexOf('`', i + 1)
                withStyle(SpanStyle(fontFamily = Mono, background = Color(0xFF27272A), color = Color(0xFFF0ABFC), fontSize = 12.sp)) {
                    append(" " + text.substring(i + 1, end) + " ")
                }
                i = end + 1
            }
            else -> { append(text[i]); i++ }
        }
    }
}

// ===================================================================================================
// Sheets: model picker, attach, context, history
// ===================================================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(agent: AgentController, onDismiss: () -> Unit, onAddProvider: () -> Unit, onPick: ((ProviderSpec, String) -> Unit)? = null) {
    @Suppress("UNUSED_VARIABLE") val rev = agent.revision
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }
    val configured = agent.configured()
    val total = configured.sumOf { agent.models(it).size }
    fun matches(spec: ProviderSpec, m: ModelInfo) = (query.isBlank() || m.id.contains(query.trim(), true) || spec.name.contains(query.trim(), true)) &&
        when (filter) { "Vision" -> ModelCaps.vision(m.id); "Tools" -> spec.supportsTools; "Free" -> m.free || spec.tier == Tier.Free; else -> true }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = C.Panel, dragHandle = { BottomSheetDefaults.DragHandle(color = Color(0xFF52525B)) }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Choose model", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                configured.firstOrNull { it.id == agent.providerId }?.let { spec ->
                    Text(if (agent.fetching[spec.id] == true) "Loading…" else "↻ Refresh", color = C.VioletSoft, fontSize = 12.sp,
                        modifier = Modifier.clickable { configured.forEach { agent.fetchModels(it) } }.padding(6.dp))
                }
            }
            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp).fillMaxWidth().height(40.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFF232329))
                .border(1.dp, C.Line, RoundedCornerShape(11.dp)).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🔍  ", fontSize = 12.sp)
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Search $total models", color = C.Dim, fontSize = 13.sp)
                    BasicTextField(query, { query = it }, singleLine = true, cursorBrush = SolidColor(C.VioletSoft),
                        textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 13.sp), modifier = Modifier.fillMaxWidth())
                }
            }
            Row(Modifier.padding(horizontal = 14.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("All", "Vision", "Tools", "Free").forEach { f ->
                    Pill(when (f) { "Vision" -> "👁 Vision"; "Tools" -> "🔧 Tools"; else -> f }, active = filter == f, onClick = { filter = f })
                }
            }
            if (configured.isEmpty()) Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("No provider yet. Add an API key to see its models.", color = C.Muted, fontSize = 13.sp)
                Button(onClick = onAddProvider, colors = ButtonDefaults.buttonColors(containerColor = C.Violet)) { Text("Add provider") }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 12.dp)) {
                val recent = agent.recentModels().filter { r -> configured.any { it.id == r.provider } }
                if (recent.isNotEmpty() && query.isBlank() && filter == "All") {
                    item { GroupHeader("RECENT", null, null) }
                    items(recent) { r -> Providers.byId(r.provider)?.let { spec -> ModelRow(agent, spec, agent.models(spec).firstOrNull { it.id == r.model } ?: ModelInfo(r.model), onDismiss, onPick) } }
                }
                configured.forEach { spec ->
                    val list = agent.models(spec).filter { matches(spec, it) }
                    if (list.isEmpty()) return@forEach
                    item { GroupHeader(spec.name.uppercase(), spec, if (agent.modelsFetched(spec)) "● ${agent.models(spec).size} models" else "↻ fetch") }
                    items(list.take(150), key = { spec.id + "/" + it.id }) { m -> ModelRow(agent, spec, m, onDismiss, onPick) }
                }
            }
            Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onAddProvider, modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(11.dp)) { Text("＋ Add provider") }
                Button(onClick = onDismiss, modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(11.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = C.Violet)) { Text("Done") }
            }
        }
    }
}

@Composable
private fun GroupHeader(title: String, spec: ProviderSpec?, trailing: String?) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (spec != null) { ProviderLogo(spec, 15); Spacer(Modifier.width(7.dp)) }
        Text(title, fontSize = 10.5.sp, letterSpacing = 0.8.sp, color = C.Dim, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, fontSize = 10.5.sp, color = C.Green)
    }
}

@Composable
private fun ModelRow(agent: AgentController, spec: ProviderSpec, m: ModelInfo, onDismiss: () -> Unit, onPick: ((ProviderSpec, String) -> Unit)?) {
    val selected = onPick == null && agent.providerId == spec.id && agent.modelId == m.id
    Row(Modifier.padding(horizontal = 6.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp))
        .background(if (selected) Color(0xFF232335) else Color.Transparent)
        .border(if (selected) 1.dp else 0.dp, if (selected) Color(0xFF3B3B5C) else Color.Transparent, RoundedCornerShape(10.dp))
        .clickable { if (onPick != null) onPick(spec, m.id) else agent.select(spec, m.id); onDismiss() }
        .padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ProviderLogo(spec, 26)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(m.id, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(spec.name + if (m.contextLength > 0) " · ${if (m.contextLength >= 1_000_000) "${m.contextLength / 1_000_000}M" else "${m.contextLength / 1000}K"} context" else "",
                color = C.Dim, fontSize = 10.5.sp, maxLines = 1)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (m.free) Cap("free", C.Green, Color(0xFF14321F))
            if (ModelCaps.vision(m.id)) Cap("👁", C.Muted, C.Raised)
            if (spec.supportsTools) Cap("🔧", C.Muted, C.Raised)
        }
        if (selected) { Spacer(Modifier.width(6.dp)); Box(Modifier.size(18.dp).background(C.Violet, CircleShape), contentAlignment = Alignment.Center) { Text("✓", color = Color.White, fontSize = 10.sp) } }
    }
}

@Composable
private fun Cap(text: String, fg: Color, bg: Color) {
    Text(text, fontSize = 9.5.sp, color = fg, modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(bg).padding(horizontal = 5.dp, vertical = 2.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachSheet(agent: AgentController, onDismiss: () -> Unit) {
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris -> uris.take(6).forEach { agent.attachImage(it) }; onDismiss() }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap -> bitmap?.let { agent.attachBitmap(it) }; onDismiss() }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { agent.attachFile(it, it.lastPathSegment?.substringAfterLast('/') ?: "file") }; onDismiss()
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF1F1F24)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 26.dp)) {
            Text("Add to message", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AttachTile("📷", "Camera", Color(0xFF1E3A5F), Modifier.weight(1f)) { runCatching { camera.launch(null) } }
                AttachTile("🖼", "Photos", Color(0xFF3B2A52), Modifier.weight(1f)) { photos.launch("image/*") }
                AttachTile("🌐", "Browser\nscreenshot", Color(0xFF14321F), Modifier.weight(1f)) { agent.attachBrowserScreenshot(); onDismiss() }
                AttachTile("📄", "File", Color(0xFF3A2F12), Modifier.weight(1f)) { file.launch("*/*") }
            }
            if (!agent.visionModel) Text("The selected model may not see images. Pick a 👁 model to work from screenshots.", color = C.Amber, fontSize = 11.5.sp,
                modifier = Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
private fun AttachTile(icon: String, label: String, bg: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(50.dp).clip(RoundedCornerShape(15.dp)).background(bg), contentAlignment = Alignment.Center) { Text(icon, fontSize = 21.sp) }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color(0xFFD4D4D8), fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 13.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContextSheet(agent: AgentController, onDismiss: () -> Unit) {
    val options = remember { agent.contextOptions() }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF1F1F24)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            Text("@ Add context", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
            if (options.isEmpty()) Text("Open a project to add files, the browser page or terminal output.", color = C.Muted, fontSize = 12.sp)
            options.forEach { option ->
                val added = agent.attachedContext.any { it.label == option.icon + " " + option.label }
                Row(Modifier.fillMaxWidth().clickable { agent.attachContext(option); onDismiss() }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(option.icon, fontSize = 16.sp, modifier = Modifier.width(30.dp))
                    Column(Modifier.weight(1f)) {
                        Text(option.label, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(option.subtitle, color = C.Dim, fontSize = 11.sp, maxLines = 1)
                    }
                    Text(if (added) "✓ added" else "add", color = if (added) C.Green else C.Dim, fontSize = 11.sp)
                }
                HorizontalDivider(color = Color(0xFF2A2A30))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistorySheet(agent: AgentController, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<ChatMeta?>(null) }
    val current = agent.chats.filter { it.title.contains(query.trim(), true) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = C.Panel) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Chats", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Pill("＋ New chat", active = true, onClick = { agent.newChat(); onDismiss() })
            }
            Row(Modifier.padding(14.dp).fillMaxWidth().height(40.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFF232329))
                .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("🔍  Search chats", color = C.Dim, fontSize = 13.sp)
                    BasicTextField(query, { query = it }, singleLine = true, cursorBrush = SolidColor(C.VioletSoft),
                        textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 13.sp), modifier = Modifier.fillMaxWidth())
                }
            }
            LazyColumn(Modifier.weight(1f)) {
                val groups = current.groupBy { it.projectId }
                groups.forEach { (projectId, list) ->
                    item { Text(agent.projectName(projectId).uppercase(), fontSize = 10.5.sp, letterSpacing = 0.8.sp, color = C.Dim,
                        modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp)) }
                    items(list, key = { it.id }) { meta ->
                        val open = meta.id == agent.chatId
                        Row(Modifier.fillMaxWidth().background(if (open) Color(0xFF232335) else Color.Transparent).clickable {
                            agent.openChat(meta); onDismiss()
                        }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF232329)), contentAlignment = Alignment.Center) {
                                Text(when (meta.mode) { "Ask" -> "💬"; "Plan" -> "🧭"; "Autopilot" -> "🚀"; else -> "⚡" }, fontSize = 14.sp)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(meta.title, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${meta.mode}" + (if (meta.filesChanged > 0) " · ${meta.filesChanged} files changed" else "") +
                                    when (meta.status) { "done" -> " · ✓ done"; "error" -> " · ✕ error"; "stopped" -> " · stopped"; else -> "" },
                                    color = C.Dim, fontSize = 11.sp, maxLines = 1)
                            }
                            Text(ago(meta.updated), color = C.Dim, fontSize = 10.5.sp)
                            if (meta.projectId == agent.chats.firstOrNull { it.id == agent.chatId }?.projectId || agent.chatId == null)
                                Text("  ✕", color = C.Dim, fontSize = 11.sp, modifier = Modifier.clickable { confirm = meta }.padding(4.dp))
                        }
                    }
                }
                if (current.isEmpty()) item { Text("No chats yet.", color = C.Muted, modifier = Modifier.padding(20.dp)) }
            }
        }
    }
    confirm?.let { meta ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Delete chat?") }, text = { Text("“${meta.title}” is removed from this phone.") },
            confirmButton = { TextButton(onClick = { agent.deleteChat(meta); confirm = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } })
    }
}

private fun ago(time: Long): String {
    val m = (System.currentTimeMillis() - time).coerceAtLeast(0) / 60_000
    return when { m < 1 -> "now"; m < 60 -> "${m}m"; m < 1440 -> "${m / 60}h"; m < 2880 -> "yesterday"; else -> "${m / 1440}d" }
}

// ===================================================================================================
// First run
// ===================================================================================================

@Composable
fun AgentOnboarding(agent: AgentController, onClose: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = agent.revision
    var step by rememberSaveable { mutableIntStateOf(if (agent.configured().isEmpty()) 1 else 2) }
    var chosen by rememberSaveable { mutableStateOf(agent.configured().firstOrNull()?.id ?: "gemini") }
    var key by rememberSaveable(chosen) { mutableStateOf(agent.key(chosen)) }
    var status by remember { mutableStateOf<String?>(null) }
    var showAll by remember { mutableStateOf(false) }
    val spec = Providers.byId(chosen) ?: Providers.all.first()
    Column(Modifier.fillMaxSize().background(C.Panel)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            (1..3).forEach { Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(if (it <= step) C.Violet else Color(0xFF2A2A30))) }
            Spacer(Modifier.width(6.dp))
            Text("✕", color = C.Muted, modifier = Modifier.clickable(onClick = onClose).padding(4.dp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            when (step) {
                1 -> {
                    Text("Connect an AI provider", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Use your own API key. Free options work great to start; you can add more later.", color = C.Muted, fontSize = 12.5.sp,
                        modifier = Modifier.padding(top = 6.dp, bottom = 14.dp))
                    val featured = listOf("gemini", "groq", "openrouter", "anthropic", "openai").mapNotNull(Providers::byId)
                    val list = if (showAll) Providers.all else featured
                    list.chunked(2).forEach { row ->
                        Row(Modifier.padding(bottom = 9.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            row.forEach { p -> ProviderTile(p, p.id == chosen, Modifier.weight(1f)) { chosen = p.id; key = agent.key(p.id); status = null } }
                            if (row.size == 1) {
                                if (!showAll) ProviderTile(null, false, Modifier.weight(1f)) { showAll = true } else Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    if (!showAll && list.size % 2 == 0) TextButton(onClick = { showAll = true }) { Text("Show all ${Providers.all.size} providers", color = C.VioletSoft) }
                    Spacer(Modifier.height(8.dp))
                    if (spec.needsKey || spec.editableUrl) {
                        Text(if (spec.needsKey) "${spec.name} API key" else "${spec.name} (no key needed)", color = C.Muted, fontSize = 12.sp)
                        if (spec.needsKey) Row(Modifier.padding(top = 6.dp).fillMaxWidth().height(46.dp).clip(RoundedCornerShape(12.dp)).background(C.Card)
                            .border(1.5.dp, C.Violet, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            BasicTextField(key, { key = it.trim(); status = null }, singleLine = true, cursorBrush = SolidColor(C.VioletSoft),
                                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 14.sp), modifier = Modifier.weight(1f))
                            val clipboard = LocalClipboardManager.current
                            Text("Paste", color = C.VioletSoft, fontSize = 12.sp, modifier = Modifier.clickable { clipboard.getText()?.text?.let { key = it.trim() } }.padding(4.dp))
                        }
                        spec.keyUrl?.let { link ->
                            val uri = androidx.compose.ui.platform.LocalUriHandler.current
                            Text("↗ Get a key from ${spec.name}", color = C.VioletSoft, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp).clickable { uri.openUri(link) })
                        }
                    }
                    status?.let {
                        val ok = it.startsWith("✓")
                        Text(it, color = if (ok) Color(0xFF86EFAC) else Color(0xFFFCA5A5), fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp).fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp)).background(if (ok) Color(0xFF14321F) else Color(0xFF2A1515)).padding(10.dp))
                    }
                }
                2 -> {
                    Text("Choose your default model", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Pick a strong model for building apps. You can switch any time from the chip at the top of the chat.", color = C.Muted,
                        fontSize = 12.5.sp, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
                    agent.configured().forEach { p ->
                        Text(p.name.uppercase(), fontSize = 10.5.sp, letterSpacing = 0.8.sp, color = C.Dim, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                        agent.models(p).sortedByDescending { recommendScore(it.id) }.take(8).forEach { m ->
                            val sel = agent.providerId == p.id && agent.modelId == m.id
                            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp).clip(RoundedCornerShape(10.dp)).background(if (sel) Color(0xFF232335) else Color.Transparent)
                                .border(1.dp, if (sel) Color(0xFF3B3B5C) else Color.Transparent, RoundedCornerShape(10.dp)).clickable { agent.select(p, m.id) }
                                .padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                ProviderLogo(p, 24); Spacer(Modifier.width(10.dp))
                                Text(m.id, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (m.free) Cap("free", C.Green, Color(0xFF14321F))
                                if (ModelCaps.vision(m.id)) { Spacer(Modifier.width(4.dp)); Cap("👁", C.Muted, C.Raised) }
                                if (sel) { Spacer(Modifier.width(6.dp)); Text("✓", color = C.VioletSoft) }
                            }
                        }
                    }
                    TextButton(onClick = { step = 1 }) { Text("＋ Add another provider", color = C.VioletSoft) }
                }
                else -> {
                    Text("What may the agent do?", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Risky steps always ask first. You can change this in Agent settings.", color = C.Muted, fontSize = 12.5.sp,
                        modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
                    SettingToggle("Edit files", "Off: every change shows a diff to Apply", agent.autoApplyEdits) { agent.updateAutoApply(it) }
                    SettingToggle("Use the browser", "Open pages, click, type, take screenshots", agent.browserEnabled) { agent.updateBrowserEnabled(it) }
                    SettingToggle("Internet access", "Read web pages and documentation", agent.internetEnabled) { agent.updateInternetEnabled(it) }
                    Text("TERMINAL COMMANDS", fontSize = 10.5.sp, letterSpacing = 0.8.sp, color = C.Dim, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                    CommandPolicy.entries.forEach { p ->
                        Row(Modifier.fillMaxWidth().clickable { agent.updateCommandPolicy(p) }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(agent.commandPolicy == p, { agent.updateCommandPolicy(p) }, colors = RadioButtonDefaults.colors(selectedColor = C.Violet))
                            Column { Text(p.label, color = Color.White, fontSize = 13.sp); Text(p.hint, color = C.Dim, fontSize = 11.sp) }
                        }
                    }
                }
            }
        }
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (step > 1) OutlinedButton(onClick = { step-- }, modifier = Modifier.weight(1f).height(46.dp), shape = RoundedCornerShape(11.dp)) { Text("Back") }
            Button(onClick = {
                when (step) {
                    1 -> {
                        if (spec.needsKey && key.isBlank()) { status = "Paste your API key first"; return@Button }
                        agent.saveProvider(spec, key, null)
                        status = "Checking…"
                        agent.fetchModels(spec) { count, error ->
                            if (count != null) { status = "✓ Key works · $count models found"; if (agent.providerId == null) agent.models(spec).maxByOrNull { recommendScore(it.id) }?.let { agent.select(spec, it.id) }; step = 2 }
                            else status = error ?: "Could not check the key"
                        }
                    }
                    2 -> if (agent.ready) step = 3
                    else -> agent.finishOnboarding()
                }
            }, modifier = Modifier.weight(1.4f).height(46.dp), shape = RoundedCornerShape(11.dp),
                enabled = agent.fetching[chosen] != true && (step != 2 || agent.ready),
                colors = ButtonDefaults.buttonColors(containerColor = C.Violet)) {
                Text(when (step) { 1 -> if (agent.fetching[chosen] == true) "Checking…" else "Check key & continue"; 2 -> "Next"; else -> "Start building" })
            }
        }
    }
}

/** Prefers strong coding models when suggesting a default. */
private fun recommendScore(id: String): Int {
    val s = id.lowercase()
    return listOf("claude-sonnet-4" to 100, "claude-opus-4" to 95, "gpt-5" to 92, "gemini-2.5-pro" to 90, "gpt-4.1" to 85, "grok-4" to 80,
        "qwen3-coder" to 78, "deepseek" to 75, "gemini-2.5-flash" to 74, "llama-3.3-70b" to 70, "codestral" to 68, "gpt-oss-120b" to 66,
        "mistral-large" to 64, "llama-4" to 62).firstOrNull { s.contains(it.first) }?.second ?: if (s.contains("mini") || s.contains("lite")) 20 else 40
}

@Composable
private fun ProviderTile(spec: ProviderSpec?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(13.dp)).background(if (selected) Color(0xFF221D33) else C.Card)
        .border(1.dp, if (selected) C.Violet else Color(0xFF2F2F36), RoundedCornerShape(13.dp)).clickable(onClick = onClick).padding(11.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (spec != null) ProviderLogo(spec, 26) else Box(Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF3F3F46)), contentAlignment = Alignment.Center) { Text("⋯", color = Color.White) }
            Spacer(Modifier.weight(1f))
            if (spec != null) Text(when (spec.tier) { Tier.Free -> "FREE"; Tier.FreeTier -> "FREE TIER"; Tier.Pro -> "PRO"; Tier.Local -> "LOCAL" },
                fontSize = 8.5.sp, color = if (spec.tier == Tier.Pro) C.VioletSoft else C.Green,
                modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(if (spec.tier == Tier.Pro) Color(0xFF2A2340) else Color(0xFF14321F)).padding(horizontal = 5.dp, vertical = 1.dp))
        }
        Text(spec?.name ?: "More providers", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 7.dp),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(spec?.note?.takeIf { it.isNotBlank() }?.take(40) ?: if (spec == null) "Mistral, DeepSeek, xAI, Ollama…" else spec.tier.label,
            color = C.Dim, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ===================================================================================================
// Settings
// ===================================================================================================

@Composable
private fun SettingToggle(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 13.sp)
            Text(subtitle, color = C.Dim, fontSize = 11.sp)
        }
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = C.Violet))
    }
}

@Composable
private fun SettingLink(title: String, subtitle: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 13.sp)
            Text(subtitle, color = C.Dim, fontSize = 11.sp)
        }
        Text("$value ›", color = C.VioletSoft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 170.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 10.5.sp, letterSpacing = 0.8.sp, color = C.Dim, modifier = Modifier.padding(top = 16.dp, bottom = 2.dp))
}

/** Agent settings: models, providers, permissions and behaviour. */
@Composable
fun AgentSettingsScreen(agent: AgentController, onBack: () -> Unit) {
    var page by rememberSaveable { mutableStateOf("main") }
    var defaultPicker by remember { mutableStateOf(false) }
    var fastPicker by remember { mutableStateOf(false) }
    var policyMenu by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    var instructions by remember { mutableStateOf(agent.instructions) }
    Column(Modifier.fillMaxSize().background(C.Panel)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (page == "main") onBack() else page = "main" }) { ToolGlyph(ToolIcon.Back, Color.White, Modifier.size(18.dp)) }
            Text(if (page == "providers") "Providers & API keys" else "Agent settings", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        if (page == "providers") {
            Box(Modifier.weight(1f)) { ProvidersTab(agent) }
            return@Column
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SectionLabel("MODELS")
            SettingLink("Default model", "New chats start with this", agent.modelId ?: "Choose") { defaultPicker = true }
            SettingLink("Fast model", "Chat titles and quick jobs", agent.fastModel?.model ?: "Off") { fastPicker = true }
            SettingLink("Providers & API keys", "${agent.configured().size} connected · ${agent.configured().sumOf { agent.models(it).size }} models", "Manage") { page = "providers" }
            SectionLabel("PERMISSIONS")
            SettingToggle("Apply edits automatically", "Off: review every diff", agent.autoApplyEdits) { agent.updateAutoApply(it) }
            Box {
                SettingLink("Terminal commands", agent.commandPolicy.hint, agent.commandPolicy.label) { policyMenu = true }
                DropdownMenu(policyMenu, { policyMenu = false }) {
                    CommandPolicy.entries.forEach { p -> DropdownMenuItem({ Text((if (p == agent.commandPolicy) "✓ " else "") + p.label) }, { agent.updateCommandPolicy(p); policyMenu = false }) }
                }
            }
            if (agent.commandsAlwaysAllowed()) SettingLink("This project: commands always allowed", "Chosen with “Always” on a command", "Reset") { agent.revokeCommandPermission() }
            SettingToggle("Browser control", "Navigate, click, type, screenshot", agent.browserEnabled) { agent.updateBrowserEnabled(it) }
            SettingToggle("Internet access", "Fetch pages and docs", agent.internetEnabled) { agent.updateInternetEnabled(it) }
            Text("Dangerous commands (like rm -rf /, mkfs, reboot) and Git push always ask, even on Autopilot.", color = C.Dim, fontSize = 11.sp,
                modifier = Modifier.padding(vertical = 4.dp))
            SectionLabel("BEHAVIOUR")
            Box {
                SettingLink("Default mode", "Ask · Plan · Agent · Autopilot", agent.defaultMode.label) { modeMenu = true }
                DropdownMenu(modeMenu, { modeMenu = false }) {
                    AgentMode.entries.forEach { m -> DropdownMenuItem({ Text((if (m == agent.defaultMode) "✓ " else "") + m.label) }, { agent.updateDefaultMode(m); modeMenu = false }) }
                }
            }
            SettingToggle("Stream answers", "Show the reply while it is written", agent.streamingEnabled) { agent.updateStreaming(it) }
            SettingToggle("Send the open file", "The file open in the editor is included as context", agent.includeOpenFile) { agent.updateIncludeOpenFile(it) }
            Text("Max steps per task: ${agent.maxSteps}", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
            Slider(agent.maxSteps.toFloat(), { agent.updateMaxSteps(it.toInt()) }, valueRange = 5f..100f,
                colors = SliderDefaults.colors(thumbColor = C.Violet, activeTrackColor = C.Violet))
            Text("Custom instructions", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            OutlinedTextField(instructions, { instructions = it.take(4000) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), minLines = 3,
                placeholder = { Text("e.g. Reply in Urdu, code in English. Use Kotlin.") }, textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 13.sp))
            if (instructions != agent.instructions) Button(onClick = { agent.updateInstructions(instructions) }, modifier = Modifier.padding(top = 6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = C.Violet)) { Text("Save") }
            Text("A project can also have AGENTS.md or CODRIOD.md; the agent follows it as project rules.", color = C.Dim, fontSize = 11.sp, modifier = Modifier.padding(vertical = 8.dp))
            SectionLabel("PRIVACY")
            Text("API keys are yours, stored in this app's private storage on this phone (not in backups) and sent only to their provider. " +
                "Git tokens are never given to the agent.", color = C.Dim, fontSize = 11.sp, modifier = Modifier.padding(bottom = 24.dp))
        }
    }
    if (defaultPicker) ModelPickerSheet(agent, onDismiss = { defaultPicker = false }, onAddProvider = { defaultPicker = false; page = "providers" })
    if (fastPicker) ModelPickerSheet(agent, onDismiss = { fastPicker = false }, onAddProvider = { fastPicker = false; page = "providers" },
        onPick = { spec, model -> agent.updateFastModel(ModelRef(spec.id, model)) })
}
