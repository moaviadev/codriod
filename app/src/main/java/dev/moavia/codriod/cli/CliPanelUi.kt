package dev.moavia.codriod.cli

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.moavia.codriod.agent.AgentColors
import dev.moavia.codriod.agent.MarkdownBlock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val C = AgentColors
private val Mono = FontFamily.Monospace

// ---- improved color palette --------------------------------------------------------------------------
private val TextPrimary = Color(0xFFE8E8ED)
private val TextSecondary = Color(0xFFB0B0B8)
private val TextDim = Color(0xFF808090)
private val CodeBg = Color(0xFF141418)
private val CodeBorder = Color(0xFF2E2E36)
private val HeaderBg = Color(0xFF1C1C22)
private val UserBubbleBg = Color(0xFF2D2D38)
private val UserBubbleBorder = Color(0xFF3A3A48)
private val ComposerBg = Color(0xFF1E1E24)
private val ComposerBorder = Color(0xFF404050)
private val StopPillBg = Color(0xFF3A1515)
private val StopPillText = Color(0xFFFF8A8A)

@Composable
private fun Logo(panel: CliPanel, size: Int = 28) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 3.4).dp)).background(Color(panel.cli.color)), contentAlignment = Alignment.Center) {
        Text(panel.cli.name.take(1), color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size * 0.5).sp)
    }
}

@Composable
private fun HeaderChip(text: String, fg: Color = Color.White, bg: Color = Color(0xFF232329), onClick: () -> Unit) {
    Text(text, color = fg, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 150.dp).clip(RoundedCornerShape(9.dp)).background(bg).border(1.dp, C.Line, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 5.dp))
}

private fun permissionColors(p: CliPermission) = when (p) {
    CliPermission.ReadOnly -> Color(0xFF93C5FD) to Color(0xFF1E2A44)
    CliPermission.Auto -> Color(0xFF86EFAC) to Color(0xFF14321F)
    CliPermission.Autopilot -> Color(0xFFFDBA74) to Color(0xFF3B1D12)
}

/** The side panel for one CLI (Codex, Copilot, Gemini…), like Codriod AI's panel. */
@Composable
fun CliPanelView(panel: CliPanel, onClose: () -> Unit, expanded: Boolean, onToggleExpand: () -> Unit) {
    val s = panel.state
    var sheet by remember { mutableStateOf<String?>(null) }
    var modeMenu by remember { mutableStateOf(false) }
    var permissionMenu by remember { mutableStateOf(false) }
    val isCodex = panel.backend is CodexBackend
    Column(Modifier.fillMaxSize().background(C.Panel)) {
        // ---- header
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Logo(panel)
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (s.features.models && s.models.isNotEmpty()) HeaderChip((s.models.firstOrNull { it.id == s.model }?.name ?: s.model ?: "Model") + " ▾") { sheet = "model" }
                else Text(panel.cli.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                if (s.features.effort && s.effort != null) HeaderChip("◆ ${s.effort!!.replaceFirstChar { it.uppercase() }} ▾", Color(0xFFC4B5FD), Color(0xFF2A2340)) { sheet = "usage" }
                if (isCodex) {
                    val (fg, bg) = permissionColors(s.permission)
                    HeaderChip(s.permission.label.substringBefore(" ·") + " ▾", fg, bg) { sheet = "usage" }
                } else {
                    if (s.modes.isNotEmpty()) Box {
                        HeaderChip((s.modes.firstOrNull { it.id == s.mode }?.name ?: "Mode") + " ▾", Color(0xFF93C5FD), Color(0xFF1E2A44)) { modeMenu = true }
                        DropdownMenu(modeMenu, { modeMenu = false }) {
                            s.modes.forEach { m -> DropdownMenuItem({ Column { Text((if (m.id == s.mode) "✓ " else "") + m.name); if (m.description.isNotBlank()) Text(m.description, fontSize = 11.sp, color = C.Dim) } },
                                { modeMenu = false; panel.act { setMode(m.id) } }) }
                        }
                    }
                    Box {
                        val (fg, bg) = permissionColors(s.permission)
                        HeaderChip(when (s.permission) { CliPermission.ReadOnly -> "Ask"; CliPermission.Auto -> "Auto"; else -> "Autopilot" } + " ▾", fg, bg) { permissionMenu = true }
                        DropdownMenu(permissionMenu, { permissionMenu = false }) {
                            CliPermission.entries.forEach { p -> DropdownMenuItem({ Column { Text((if (p == s.permission) "✓ " else "") + p.label); Text(p.hint, fontSize = 11.sp, color = C.Dim) } },
                                { permissionMenu = false; s.permission = p }) }
                        }
                    }
                }
            }
            if (s.features.threads) IconText("🕘") { panel.act { refreshThreads() }; sheet = "threads" }
            IconText("＋") { panel.act { newThread() } }
            IconText("⚙") { sheet = "settings" }
            IconText(if (expanded) "⤡" else "⤢", onToggleExpand)
            IconText("✕", onClose)
        }
        Meter(panel) { sheet = "usage" }
        HorizontalDivider(color = Color(0xFF26262C))
        // ---- body
        val needsLogin = s.account?.signedIn == false || s.login != null
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !s.connected && s.error != null -> StartError(panel)
                !s.connected -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(color = C.Violet); Text(s.status, color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                }
                needsLogin -> LoginView(panel)
                else -> Conversation(panel)
            }
        }
        s.approval?.let { ApprovalView(it) }
        s.error?.takeIf { s.connected }?.let { err ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF2E1515)).padding(11.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(err, color = StopPillText, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
                Text("\u2715", color = TextDim, modifier = Modifier.clip(CircleShape).clickable { s.error = null }.padding(6.dp))
            }
        }
        if (s.connected && !needsLogin) Composer(panel) { sheet = "commands" }
    }
    when (sheet) {
        "model" -> ModelSheet(panel) { sheet = null }
        "usage" -> UsageSheet(panel) { sheet = null }
        "threads" -> ThreadsSheet(panel) { sheet = null }
        "settings" -> SettingsSheet(panel) { sheet = null }
        "commands" -> CommandsSheet(panel) { sheet = null }
    }
}

@Composable
private fun IconText(text: String, onClick: () -> Unit) {
    Text(text, color = Color(0xFF9090A0), fontSize = 16.sp, modifier = Modifier.clip(CircleShape).clickable(onClick = onClick).padding(horizontal = 7.dp, vertical = 5.dp))
}

private fun resetText(epoch: Long?): String {
    if (epoch == null) return ""
    val ms = if (epoch < 10_000_000_000L) epoch * 1000 else epoch
    val left = ms - System.currentTimeMillis()
    val time = SimpleDateFormat(if (left > 20 * 3_600_000) "EEE h:mm a" else "h:mm a", Locale.getDefault()).format(Date(ms))
    return if (left <= 0) "resets now" else "resets $time" + if (left < 24 * 3_600_000) " · in ${left / 3_600_000} h ${(left / 60_000) % 60} min" else ""
}

/** Limits under the header: Codex's 5-hour and weekly windows, context left; Copilot's premium requests. */
@Composable
private fun Meter(panel: CliPanel, onClick: () -> Unit) {
    val s = panel.state
    val u = s.usage
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (u.primaryPercent != null || u.secondaryPercent != null) {
            u.primaryPercent?.let { Bar(windowName(u.primaryWindowMins, "5h"), it, C.Blue, Modifier.weight(1f)) }
            u.secondaryPercent?.let { Bar(windowName(u.secondaryWindowMins, "Week"), it, C.Green, Modifier.weight(1f)) }
        } else Text("${panel.cli.name}" + (s.version?.let { " · $it" } ?: "") + if (panel.backend is AcpBackend) " · ACP" else "",
            color = C.Dim, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (s.features.requestsCount) Text("Premium requests: ${u.requestsThisSession}", color = C.Muted, fontSize = 10.5.sp)
        u.contextLeftPercent?.let { Text("ctx $it%", color = if (it < 20) C.Amber else C.Muted, fontSize = 10.5.sp) }
        u.cost?.let { Text(it, color = C.Muted, fontSize = 10.5.sp) }
    }
}

private fun windowName(mins: Long?, fallback: String) = when {
    mins == null -> fallback
    mins >= 7 * 24 * 60 - 60 -> "Week"
    mins >= 24 * 60 -> "${mins / (24 * 60)}d"
    else -> "${(mins + 30) / 60}h"
}

@Composable
private fun Bar(label: String, percent: Int, color: Color, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.Dim, fontSize = 10.5.sp)
        Box(Modifier.padding(horizontal = 6.dp).weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF2F2F36))) {
            Box(Modifier.fillMaxWidth(percent.coerceIn(0, 100) / 100f).fillMaxHeight().background(if (percent >= 90) C.Red else color))
        }
        Text("$percent%", color = Color(0xFFD4D4D8), fontSize = 10.5.sp)
    }
}

@Composable
private fun StartError(panel: CliPanel) {
    val err = panel.state.error.orEmpty()
    val isAuthError = err.lowercase().let { it.contains("auth") || it.contains("sign in") || it.contains("login") || it.contains("not authenticated") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("\u2328", fontSize = 34.sp)
        Text("${panel.cli.name} could not start", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
        Text(err, color = TextSecondary, fontSize = 12.5.sp, modifier = Modifier.padding(top = 8.dp))
        Column(Modifier.padding(top = 14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { panel.restart() }, modifier = Modifier.fillMaxWidth(0.7f), colors = ButtonDefaults.buttonColors(containerColor = C.Violet)) { Text("Try again") }
            if (isAuthError) {
                OutlinedButton(onClick = { panel.openTerminal(panel.cli.loginCommand ?: panel.cli.command) }, modifier = Modifier.fillMaxWidth(0.7f)) {
                    Text("Sign in in the terminal")
                }
            }
            OutlinedButton(onClick = { panel.openTerminal() }, modifier = Modifier.fillMaxWidth(0.7f)) { Text("Open in terminal") }
        }
    }
}

// ---- sign-in ---------------------------------------------------------------------------------------------------

@Composable
private fun LoginView(panel: CliPanel) {
    val s = panel.state
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var apiKey by remember { mutableStateOf("") }

    fun openBrowser(url: String) {
        try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Exception) { s.login = s.login?.copy(error = "Could not open browser. Copy the URL and open it manually.") }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Logo(panel, 52)
        Text("Sign in to ${panel.cli.name}", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
        Text(panel.cli.signIn, color = TextSecondary, fontSize = 12.5.sp, modifier = Modifier.padding(top = 4.dp))
        val login = s.login
        if (login != null) {
            Column(Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Card).padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                login.userCode?.let { code ->
                    Text("YOUR ONE-TIME CODE", color = TextDim, fontSize = 10.5.sp, letterSpacing = 1.sp)
                    Text(code, color = Color.White, fontFamily = Mono, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(code)) }) { Text("Copy code") }
                        login.url?.let { url -> Button(onClick = { openBrowser(url) }, colors = ButtonDefaults.buttonColors(containerColor = C.Violet)) { Text("Open page ↗") } }
                    }
                } ?: login.url?.let { url ->
                    Button(onClick = { openBrowser(url) }, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = C.Violet)) { Text("Open sign-in page ↗") }
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(url)) }, modifier = Modifier.padding(top = 6.dp).fillMaxWidth()) { Text("Copy URL") }
                }
                if (login.message.isNotBlank()) Text(login.message, color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                login.error?.let { err ->
                    Text(err, color = C.Red, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    // Show retry button on error
                    if (panel.backend is AcpBackend) Button(onClick = { s.login = null }, modifier = Modifier.padding(top = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = C.Violet)) { Text("Try again") }
                }
                if (login.waiting && login.error == null) Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Text("  Waiting for you to finish…", color = C.Muted, fontSize = 12.sp)
                }
                val acpBackend = panel.backend as? AcpBackend
                if (acpBackend != null && login.error == null) Button(onClick = { panel.act { acpBackend.retryAfterLogin() } }, modifier = Modifier.padding(top = 12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = C.Violet)) { Text("I'm signed in") }
                TextButton(onClick = { s.login = null }) { Text("Cancel", color = C.Dim) }
            }
        } else when (val backend = panel.backend) {
            is CodexBackend -> {
                Spacer(Modifier.height(8.dp))
                Button(onClick = { panel.act { login("chatgpt") } }, modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF111111))) { Text("Sign in with ChatGPT", fontWeight = FontWeight.SemiBold) }
                OutlinedButton(onClick = { panel.act { login("device") } }, modifier = Modifier.padding(top = 8.dp).fillMaxWidth()) { Text("Sign in with a device code") }
                OutlinedTextField(apiKey, { apiKey = it }, label = { Text("…or an OpenAI API key") }, singleLine = true,
                    modifier = Modifier.padding(top = 12.dp).fillMaxWidth(), textStyle = LocalTextStyle.current.copy(color = Color.White))
                if (apiKey.isNotBlank()) Button(onClick = { val k = apiKey; apiKey = ""; panel.act { login("apiKey", k) } }, modifier = Modifier.padding(top = 6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = C.Violet)) { Text("Use API key") }
                Text("Opens OpenAI's own page in your browser. Codex keeps the login on this phone, in Ubuntu.", color = C.Dim, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 10.dp))
            }
            is AcpBackend -> {
                val options = backend.authOptions()
                Spacer(Modifier.height(8.dp))
                if (options.isNotEmpty()) {
                    options.forEach { (id, name) ->
                        Button(onClick = { panel.act { login(id) } }, modifier = Modifier.padding(top = 8.dp).fillMaxWidth().height(46.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = C.Violet)) { Text(name) }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color(0xFF2A2A33))
                }
                // Terminal sign-in: always available, more prominent when no auth methods
                val termCmd = panel.cli.loginCommand ?: panel.cli.command
                if (options.isEmpty()) {
                    // Primary action when no auth buttons
                    Button(onClick = {
                        panel.openTerminal(termCmd)
                        s.login = CliLogin(message = "Finish signing in in the terminal, then come back and tap \u201cI\u2019m signed in\u201d.")
                    }, modifier = Modifier.fillMaxWidth().height(46.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = C.Violet)) { Text("Sign in in the terminal") }
                    Text("Run  $termCmd  to sign in, then come back.", color = C.Muted, fontFamily = Mono, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                } else {
                    OutlinedButton(onClick = {
                        panel.openTerminal(termCmd)
                        s.login = CliLogin(message = "Finish signing in in the terminal, then come back and tap \u201cI\u2019m signed in\u201d.")
                    }, modifier = Modifier.fillMaxWidth()) { Text("Sign in in the terminal ($termCmd)") }
                }
                // Retry / "I'm already signed in" button
                OutlinedButton(onClick = { panel.act { backend.retryAfterLogin() } }, modifier = Modifier.padding(top = 8.dp).fillMaxWidth()) { Text("I'm already signed in") }
                Text("The CLI's own sign-in opens in your browser. Codriod never sees your password.", color = C.Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}

// ---- conversation ------------------------------------------------------------------------------------------------

@Composable
private fun Conversation(panel: CliPanel) {
    val s = panel.state
    val list = rememberLazyListState()
    val last = s.items.lastOrNull()
    val lastSize = when (last) { is CliItem.Agent -> last.text.length; is CliItem.Command -> last.output.length; is CliItem.Reasoning -> last.text.length; else -> 0 }
    LaunchedEffect(s.items.size, lastSize) { if (s.items.isNotEmpty()) list.animateScrollToItem(s.items.size) }
    if (s.items.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Logo(panel, 56)
            Text(panel.cli.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
            Text(s.account?.label?.takeIf { it.isNotBlank() }?.let { "Signed in \u00B7 $it" + (s.account?.plan?.let { p -> " \u00B7 $p" } ?: "") } ?: s.status,
                color = TextSecondary, fontSize = 12.5.sp, modifier = Modifier.padding(top = 4.dp))
            Text("Ask about this project, or tell ${panel.cli.name} what to build.", color = TextDim, fontSize = 13.sp,
                modifier = Modifier.padding(top = 14.dp).widthIn(max = 260.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(s.items, key = { it.id }) { item -> ItemView(item) }
        if (s.running) item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = C.VioletSoft)
                Text("  ${panel.cli.name} is working\u2026", color = TextSecondary, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                Text("\u25A0 Stop", color = StopPillText, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(StopPillBg).clickable { panel.act { interrupt() } }.padding(horizontal = 10.dp, vertical = 5.dp))
            }
        }
    }
}

@Composable
private fun ItemView(item: CliItem) {
    when (item) {
        is CliItem.User -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Column(Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)).background(UserBubbleBg)
                .border(1.dp, UserBubbleBorder, RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)).padding(horizontal = 12.dp, vertical = 9.dp)) {
                Text(item.text, color = TextPrimary, fontSize = 13.5.sp)
                if (item.images.isNotEmpty()) Text("\uD83D\uDDBC ${item.images.size} image" + if (item.images.size > 1) "s" else "",
                    color = TextSecondary, fontSize = 11.5.sp, modifier = Modifier.padding(top = 3.dp))
            }
        }
        is CliItem.Agent -> if (item.text.isNotBlank()) MarkdownBlock(item.text)
        is CliItem.Reasoning -> if (item.text.isNotBlank() || !item.done) {
            var open by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { open = !open }.padding(horizontal = 4.dp, vertical = 3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (open) "\u25BE " else "\u25B8 ", color = TextDim, fontSize = 12.sp)
                    Text("Thinking" + if (!item.done) "\u2026" else "", color = TextDim, fontSize = 12.sp, fontStyle = FontStyle.Italic)
                    if (!item.done) { Spacer(Modifier.width(6.dp)); CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp, color = C.VioletSoft) }
                }
                if (open) Text(item.text, color = TextDim, fontSize = 12.sp, fontStyle = FontStyle.Italic, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
            }
        }
        is CliItem.Command -> CommandView(item)
        is CliItem.FileEdit -> item.changes.forEach { DiffView(it, item.status) }
        is CliItem.Tool -> {
            var detailOpen by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CodeBg).border(1.dp, CodeBorder, RoundedCornerShape(10.dp))
                .clickable(enabled = item.detail.isNotBlank()) { detailOpen = !detailOpen }.padding(horizontal = 10.dp, vertical = 7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(toolIcon(item.kind) + " ", fontSize = 13.sp)
                    Text(toolVerb(item.kind), color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(end = 6.dp))
                    Text(item.title, color = TextPrimary, fontSize = 12.sp, fontFamily = Mono, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(6.dp))
                    StatusMark(item.status)
                }
                if (detailOpen && item.detail.isNotBlank()) Text(item.detail.take(1000), color = TextDim, fontSize = 11.5.sp, fontFamily = Mono, maxLines = 12,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp, top = 6.dp))
            }
        }
        is CliItem.Plan -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF1A1A22))
            .border(1.5.dp, C.Violet.copy(alpha = 0.3f), RoundedCornerShape(10.dp)).padding(12.dp)) {
            Text("Plan", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            item.explanation?.let { Text(it, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
            item.steps.forEach { st ->
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Top) {
                    Text(when (st.status) { "completed" -> "\u2713"; "in_progress" -> "\u25CF"; else -> "\u25CB" }, fontSize = 13.sp,
                        color = when (st.status) { "completed" -> C.Green; "in_progress" -> Color(0xFF93C5FD); else -> Color(0xFF505060) },
                        modifier = Modifier.width(20.dp))
                    Text(st.text, fontSize = 12.5.sp, fontWeight = if (st.status == "in_progress") FontWeight.SemiBold else FontWeight.Normal,
                        color = when (st.status) { "completed" -> TextSecondary; "in_progress" -> TextPrimary; else -> Color(0xFF707080) })
                }
            }
        }
        is CliItem.Notice -> Text(item.text, color = if (item.level == "warning" || item.level == "error") C.Amber else TextSecondary, fontSize = 12.sp)
    }
}

private fun toolIcon(kind: String) = when (kind) {
    "read" -> "\uD83D\uDCC4"; "search" -> "\uD83D\uDD0D"; "fetch" -> "\uD83C\uDF10"; "think" -> "\uD83D\uDCAD"; "edit", "write" -> "\u270E"; "delete" -> "\uD83D\uDDD1"; "move" -> "\u21C4"; "mcp" -> "\uD83D\uDD0C"; "execute" -> "\u25B6"; else -> "\u2022"
}

private fun toolVerb(kind: String) = when (kind) {
    "read" -> "Read"; "search" -> "Search"; "fetch" -> "Fetch"; "think" -> "Think"; "edit" -> "Edit"; "write" -> "Write"; "delete" -> "Delete"; "move" -> "Move"; "mcp" -> "MCP"; "execute" -> "Run"; else -> ""
}

@Composable
private fun StatusMark(status: ItemStatus) {
    when (status) {
        ItemStatus.Running -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = C.VioletSoft)
        ItemStatus.Done -> Text("\u2713", color = C.Green, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        ItemStatus.Failed -> Text("\u2717", color = C.Red, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        ItemStatus.Declined -> Text("declined", color = C.Amber, fontSize = 11.sp)
    }
}

@Composable
private fun CommandView(item: CliItem.Command) {
    var open by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CodeBg).border(1.dp, CodeBorder, RoundedCornerShape(10.dp))) {
        Row(Modifier.fillMaxWidth().background(HeaderBg).clickable { open = !open }.padding(horizontal = 11.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("\u25B6 ", color = TextSecondary, fontSize = 12.sp)
            Text(item.command, color = TextPrimary, fontFamily = Mono, fontSize = 11.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            when {
                item.status == ItemStatus.Running -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = C.VioletSoft)
                item.status == ItemStatus.Declined -> Text("declined", color = C.Amber, fontSize = 11.sp)
                else -> {
                    val code = item.exitCode ?: if (item.status == ItemStatus.Done) 0 else 1
                    val ok = code == 0 && item.status == ItemStatus.Done
                    Text("exit $code", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        color = if (ok) C.Green else C.Red,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(if (ok) Color(0xFF0E2E1A) else Color(0xFF2E1515)).padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
        }
        if (open && item.output.isNotBlank()) SelectionContainer {
            Text(item.output.lines().takeLast(20).joinToString("\n").takeLast(5000), color = Color(0xFFD8D8DE), fontFamily = Mono, fontSize = 11.5.sp,
                lineHeight = 17.sp, modifier = Modifier.padding(11.dp))
        }
    }
}

@Composable
private fun DiffView(change: FileChange, status: ItemStatus) {
    var open by remember { mutableStateOf(true) }
    val lines = change.diff.lines().filterNot { it.startsWith("---") || it.startsWith("+++") || it.startsWith("diff ") || it.startsWith("index ") }
    val added = lines.count { it.startsWith("+") }; val removed = lines.count { it.startsWith("-") }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CodeBg).border(1.dp, CodeBorder, RoundedCornerShape(10.dp))) {
        Row(Modifier.fillMaxWidth().background(HeaderBg).clickable { open = !open }.padding(horizontal = 11.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(when (change.kind) { "add" -> "\uFF0B "; "delete" -> "\uD83D\uDDD1 "; else -> "\u270E " }, fontSize = 12.sp, color = TextSecondary)
            Text(change.path, color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text("  +$added", color = C.Green, fontSize = 12.sp); Text(" \u2212$removed", color = C.Red, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            StatusMark(status)
        }
        if (open) Column(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
            lines.take(100).forEach { l ->
                val (fg, bg) = when {
                    l.startsWith("+") -> Color(0xFF86EFAC) to Color(0xFF1A2E1A)
                    l.startsWith("-") -> Color(0xFFFCA5A5) to Color(0xFF2E1A1A)
                    l.startsWith("@@") -> Color(0xFF7070A0) to Color.Transparent
                    else -> Color(0xFFD0D0D8) to Color.Transparent
                }
                Text(l.ifEmpty { " " }, color = fg, fontFamily = Mono, fontSize = 11.5.sp, softWrap = false, modifier = Modifier.background(bg).padding(horizontal = 11.dp, vertical = 0.5.dp))
            }
            if (lines.size > 100) Text("\u2026 ${lines.size - 100} more lines", color = TextDim, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 11.dp, vertical = 4.dp))
        }
    }
}

@Composable
private fun ApprovalView(a: CliApproval) {
    Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp).fillMaxWidth().heightIn(max = 360.dp).clip(RoundedCornerShape(12.dp))
        .background(if (a.dangerous) Color(0xFF2A1515) else Color(0xFF1C1F2B))
        .border(1.dp, if (a.dangerous) Color(0xFF7F1D1D) else Color(0xFF3B4A7A), RoundedCornerShape(12.dp)).verticalScroll(rememberScrollState()).padding(10.dp)) {
        Text((if (a.dangerous) "⚠ " else "") + a.title, color = if (a.dangerous) Color(0xFFFCA5A5) else Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        if (a.detail.isNotBlank()) Text(a.detail, color = C.Muted, fontSize = 11.5.sp, modifier = Modifier.padding(top = 3.dp))
        a.command?.let { Text("$ $it", color = C.Text, fontFamily = Mono, fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp).fillMaxWidth()
            .clip(RoundedCornerShape(8.dp)).background(Color(0xFF0F0F12)).padding(8.dp)) }
        a.changes.take(3).forEach { Box(Modifier.padding(top = 6.dp)) { DiffView(it, ItemStatus.Running) } }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            a.options.forEach { (id, label, kind) ->
                when (kind) {
                    "reject" -> TextButton(onClick = { a.answer(id) }, modifier = Modifier.weight(1f)) { Text(label, color = Color(0xFFFCA5A5), fontSize = 12.sp, maxLines = 1) }
                    "always" -> OutlinedButton(onClick = { a.answer(id) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text(label, fontSize = 12.sp, maxLines = 1) }
                    else -> Button(onClick = { a.answer(id) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (a.dangerous) Color(0xFFB91C1C) else Color(0xFF2563EB))) { Text(label, fontSize = 12.sp, maxLines = 1) }
                }
            }
        }
        Text("🎙 Or type “yes” / “no” (ہاں / نہیں)", color = C.VioletSoft, fontSize = 10.5.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun Composer(panel: CliPanel, onCommands: () -> Unit) {
    val s = panel.state
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.take(4).forEach { uri -> panel.attachUri(uri) }
    }
    Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
        .background(ComposerBg).border(1.5.dp, ComposerBorder, RoundedCornerShape(18.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
        if (panel.attachments.isNotEmpty()) Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            panel.attachments.toList().forEach { path ->
                Text("\uD83D\uDDBC ${path.substringAfterLast('/').take(14)} \u2717", color = TextSecondary, fontSize = 11.5.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF2A2A34)).clickable { panel.attachments.remove(path) }.padding(7.dp))
            }
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 40.dp, max = 180.dp)) {
            if (panel.draft.isEmpty()) Text(when {
                s.approval != null -> "Type yes / no\u2026"
                s.running && s.features.steer -> "Steer ${panel.cli.name} while it works\u2026"
                s.running -> "${panel.cli.name} is working\u2026"
                else -> "Ask ${panel.cli.name}\u2026"
            }, color = Color(0xFF606070), fontSize = 14.sp)
            BasicTextField(panel.draft, { panel.draft = it }, textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 14.sp),
                cursorBrush = SolidColor(C.VioletSoft), modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()))
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (s.features.images) Pill("\uD83D\uDDBC Image") { photos.launch("image/*") }
            Pill("/ Commands") { onCommands() }
            Pill("\u2328 Terminal") { panel.openTerminal() }
            Spacer(Modifier.weight(1f))
            val canSend = panel.draft.isNotBlank() || panel.attachments.isNotEmpty()
            val stop = s.running && !canSend
            Box(Modifier.size(40.dp).clip(CircleShape)
                .background(when { stop -> StopPillBg; canSend -> Color.White; else -> Color(0xFF3F3F46) })
                .then(if (canSend) Modifier.border(2.dp, C.Violet, CircleShape) else Modifier)
                .clickable(enabled = stop || canSend) { if (stop) panel.act { interrupt() } else panel.send() }, contentAlignment = Alignment.Center) {
                if (stop) Box(Modifier.size(12.dp).background(Color.White, RoundedCornerShape(2.dp)))
                else Text("\u2191", color = if (canSend) Color(0xFF111111) else C.Dim, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Pill(text: String, onClick: () -> Unit) {
    Text(text, fontSize = 12.5.sp, color = Color(0xFFC0C0C8), modifier = Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF2A2A34))
        .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp))
}

// ---- sheets ------------------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Sheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF1F1F24)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 26.dp).verticalScroll(rememberScrollState()), content = content)
    }
}

@Composable
private fun SheetTitle(text: String) { Text(text, color = C.Dim, fontSize = 10.5.sp, letterSpacing = 0.8.sp, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)) }

@Composable
private fun Choice(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(11.dp)).background(if (selected) Color(0xFF221D33) else Color.Transparent)
        .border(1.dp, if (selected) C.Violet else Color(0xFF2F2F36), RoundedCornerShape(11.dp)).clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(16.dp).clip(CircleShape).border(if (selected) 5.dp else 2.dp, if (selected) Color(0xFFA78BFA) else Color(0xFF5A5A62), CircleShape))
        Column(Modifier.padding(start = 10.dp)) {
            Text(title, color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle.isNotBlank()) Text(subtitle, color = C.Dim, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ModelSheet(panel: CliPanel, onDismiss: () -> Unit) {
    val s = panel.state
    Sheet(onDismiss) {
        Text("Model", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        s.models.forEach { m ->
            Choice(m.name, listOf(m.description.take(90), if (m.efforts.isNotEmpty()) "effort: ${m.efforts.joinToString(" · ")}" else "").filter { it.isNotBlank() }.joinToString("\n"),
                m.id == s.model) { panel.act { setModel(m.id) }; onDismiss() }
        }
    }
}

/** Account, plan, limits with reset times, tokens, reasoning effort and permissions. */
@Composable
private fun UsageSheet(panel: CliPanel, onDismiss: () -> Unit) {
    val s = panel.state
    val u = s.usage
    LaunchedEffect(Unit) { panel.act { refreshUsage() } }
    Sheet(onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Logo(panel, 34)
            Column(Modifier.padding(start = 10.dp)) {
                Text(panel.cli.name + (s.account?.plan?.let { " · ${it.replaceFirstChar { c -> c.uppercase() }}" } ?: ""), color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(s.account?.label ?: "", color = C.Dim, fontSize = 11.sp)
            }
        }
        if (u.primaryPercent != null || u.secondaryPercent != null) {
            SheetTitle("USAGE LIMITS")
            u.primaryPercent?.let { LimitRow("${windowName(u.primaryWindowMins, "5-hour")} window", it, resetText(u.primaryResetsAt), C.Blue) }
            u.secondaryPercent?.let { LimitRow(if (windowName(u.secondaryWindowMins, "Week") == "Week") "Weekly" else windowName(u.secondaryWindowMins, ""), it, resetText(u.secondaryResetsAt), C.Green) }
        }
        if (s.features.requestsCount) {
            SheetTitle("PREMIUM REQUESTS")
            Text("This session: ${u.requestsThisSession} (each prompt uses one)", color = C.Text, fontSize = 12.5.sp)
            val context = LocalContext.current
            Text("Monthly usage on GitHub ↗", color = C.Blue, fontSize = 12.5.sp, modifier = Modifier.padding(top = 6.dp).clickable {
                runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/settings/copilot"))) }
            })
        }
        if (u.tokensUsed != null) {
            SheetTitle("THIS CHAT")
            Text("${"%,d".format(u.tokensUsed)} tokens" + (u.contextLeftPercent?.let { " · context $it% left" } ?: "") + (u.cost?.let { " · $it" } ?: ""),
                color = C.Text, fontSize = 12.5.sp)
            if (s.features.compact) TextButton(onClick = { panel.act { compact() }; onDismiss() }) { Text("Compact the conversation") }
        }
        val model = s.models.firstOrNull { it.id == s.model }
        if (s.features.effort && !model?.efforts.isNullOrEmpty()) {
            SheetTitle("REASONING EFFORT")
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF141417)).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                model!!.efforts.forEach { e ->
                    Text(e.replaceFirstChar { it.uppercase() }, color = if (e == s.effort) Color.White else C.Muted, fontSize = 11.5.sp,
                        fontWeight = if (e == s.effort) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (e == s.effort) Color(0xFF2E2A45) else Color.Transparent)
                            .clickable { panel.act { setEffort(e) } }.padding(vertical = 7.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
        SheetTitle("PERMISSIONS")
        CliPermission.entries.forEach { p -> Choice(p.label, p.hint, p == s.permission) { s.permission = p; if (panel.backend is CodexBackend) panel.act { setMode(p.name) } } }
    }
}

@Composable
private fun LimitRow(title: String, percent: Int, reset: String, color: Color) {
    Column(Modifier.padding(vertical = 5.dp)) {
        Row { Text(title, color = C.Text, fontSize = 12.5.sp, modifier = Modifier.weight(1f)); Text("$percent% used", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold) }
        Box(Modifier.padding(vertical = 5.dp).fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF2F2F36))) {
            Box(Modifier.fillMaxWidth(percent.coerceIn(0, 100) / 100f).fillMaxHeight().background(if (percent >= 90) C.Red else color))
        }
        if (reset.isNotBlank()) Text(reset.replaceFirstChar { it.uppercase() }, color = C.Dim, fontSize = 11.sp)
    }
}

@Composable
private fun ThreadsSheet(panel: CliPanel, onDismiss: () -> Unit) {
    val s = panel.state
    Sheet(onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Chats", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (s.features.fork && s.threadId != null) TextButton(onClick = { panel.act { forkThread() }; onDismiss() }) { Text("Fork current") }
        }
        if (s.threads.isEmpty()) Text("No earlier chats in this project.", color = C.Muted, fontSize = 12.5.sp, modifier = Modifier.padding(vertical = 10.dp))
        s.threads.forEach { t ->
            var menu by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (t.id == s.threadId) Color(0xFF232335) else Color.Transparent)
                .clickable { panel.act { openThread(t.id) }; onDismiss() }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.title, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (t.updatedAt > 0) Text(SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(t.updatedAt)), color = C.Dim, fontSize = 11.sp)
                }
                if (s.features.archive) Box {
                    Text("⋯", color = C.Muted, modifier = Modifier.clickable { menu = true }.padding(8.dp))
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Open") }, { menu = false; panel.act { openThread(t.id) }; onDismiss() })
                        DropdownMenuItem({ Text("Archive") }, { menu = false; panel.act { archiveThread(t.id) } })
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandsSheet(panel: CliPanel, onDismiss: () -> Unit) {
    val s = panel.state
    val builtIn = buildList {
        add("/new" to "Start a new chat")
        if (s.features.review) add("/review" to "Review uncommitted changes")
        if (s.features.compact) add("/compact" to "Summarize older messages to free context")
        if (s.features.fork) add("/fork" to "Copy this chat to try another way")
    }
    Sheet(onDismiss) {
        Text("Commands", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        (builtIn + s.commands.map { "/${it.first}" to it.second }).distinctBy { it.first }.forEach { (name, description) ->
            Row(Modifier.fillMaxWidth().clickable {
                if (name in builtIn.map { it.first }) { panel.draft = name; panel.send() } else panel.draft = "$name "
                onDismiss()
            }.padding(vertical = 9.dp)) {
                Text(name, color = C.VioletSoft, fontFamily = Mono, fontSize = 12.5.sp, modifier = Modifier.width(110.dp))
                Text(description, color = C.Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SettingsSheet(panel: CliPanel, onDismiss: () -> Unit) {
    val s = panel.state
    var showLog by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Sheet(onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Logo(panel, 30)
            Column(Modifier.padding(start = 10.dp)) {
                Text("${panel.cli.name} settings", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(listOfNotNull(s.version, if (panel.backend is CodexBackend) "app-server" else "ACP").joinToString(" · "), color = C.Dim, fontSize = 11.sp)
            }
        }
        SheetTitle("ACCOUNT")
        Text(if (s.account?.signedIn == true) "Signed in · ${s.account?.label}" + (s.account?.plan?.let { " · $it" } ?: "") else "Not signed in", color = C.Text, fontSize = 12.5.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (panel.backend is CodexBackend && s.account?.signedIn == true) TextButton(onClick = { panel.act { logout() }; onDismiss() }) { Text("Sign out", color = C.Red) }
            if (s.account?.signedIn != true) TextButton(onClick = { s.account = CliAccount(false); s.login = null; onDismiss() }) { Text("Sign in", color = C.Violet) }
            TextButton(onClick = { panel.openTerminal(panel.cli.loginCommand); onDismiss() }) { Text("Sign in in the terminal") }
        }
        SheetTitle("PERMISSIONS")
        CliPermission.entries.forEach { p -> Choice(p.label, p.hint, p == s.permission) { s.permission = p; if (panel.backend is CodexBackend) panel.act { setMode(p.name) } } }
        SheetTitle("CLI")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { panel.openTerminal(); onDismiss() }) { Text("⌨ Open in terminal") }
            OutlinedButton(onClick = { panel.restart(); onDismiss() }) { Text("⟳ Restart") }
        }
        TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Hide log" else "Show log (${panel.log.size})") }
        if (showLog) {
            Text(panel.log.takeLast(120).joinToString("\n"), color = Color(0xFFD4D4D8), fontFamily = Mono, fontSize = 9.5.sp,
                modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF111114))
                    .verticalScroll(rememberScrollState()).padding(8.dp))
            TextButton(onClick = { clipboard.setText(AnnotatedString(panel.log.joinToString("\n"))) }) { Text("Copy log") }
        }
    }
}
