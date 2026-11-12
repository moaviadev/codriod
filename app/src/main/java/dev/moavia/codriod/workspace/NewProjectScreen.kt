package dev.moavia.codriod.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class Starter(
    val key: String,
    val title: String,
    val subtitle: String,
    val icon: ToolIcon,
    val tint: Color
)

@Composable
internal fun NewProjectScreen(
    model: WorkspaceModel,
    initial: String,
    onBack: () -> Unit,
    onOpenTerminal: () -> Unit,
    onClone: () -> Unit,
    onCreate: (String, String) -> Unit
) {
    val p = LocalHomePalette.current
    var name by remember { mutableStateOf("") }
    var template by remember(initial) {
        mutableStateOf(if (initial in setOf("Web", "Shell", "Empty", "GitHub")) initial else "Web")
    }
    BackHandler(enabled = true) { onBack() }
    Column(Modifier.fillMaxSize()) {
        TopBar(model, onOpenTerminal, onBack = onBack)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val contentWidth = if (maxWidth > 560.dp) 560.dp else maxWidth
                Column(
                    Modifier.width(contentWidth).fillMaxHeight().align(Alignment.TopCenter)
                        .verticalScroll(rememberScrollState()).imePadding()
                        .padding(horizontal = 22.dp).padding(bottom = 28.dp)
                ) {
                    Column(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CodingIllustration(p)
                        Spacer(Modifier.height(16.dp))
                        Text("Code. Build. Run. Anywhere.", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold,
                            textAlign = TextAlign.Center, lineHeight = 34.sp, color = p.text)
                        Text("Create your first project, open a folder from your device, or clone your code from GitHub.",
                            fontSize = 13.5.sp, color = p.muted, textAlign = TextAlign.Center, lineHeight = 20.sp,
                            modifier = Modifier.padding(top = 8.dp))
                    }
                    Spacer(Modifier.height(24.dp))
                    Text("New Project", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = p.text)
                    Text("Pick a starter. You can change everything later.", fontSize = 12.5.sp, color = p.muted,
                        modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
                    val starters = listOf(
                        Starter("Web", "Website", "HTML, CSS, JS with live preview.", ToolIcon.Globe, p.primary),
                        Starter("Shell", "Shell Script", "Run with the phone's shell.", ToolIcon.Terminal, p.accent),
                        Starter("Empty", "Empty", "Start from a blank folder.", ToolIcon.File, p.primary2),
                        Starter("GitHub", "From GitHub", "Clone one of your repositories.", ToolIcon.Git,
                            if (p.dark) Color(0xFF8B949E) else Color(0xFF24292F))
                    )
                    starters.chunked(2).forEach { row ->
                        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { starter ->
                                StarterCard(starter, selected = template == starter.key, p = p, modifier = Modifier.weight(1f)) {
                                    template = starter.key
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("PROJECT NAME", fontSize = 12.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold, color = p.muted)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth().height(56.dp).glass(p, RoundedCornerShape(16.dp), elevated = false)
                        .padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(p.primary.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center) {
                            ToolGlyph(ToolIcon.Folder, p.primary, Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Box(Modifier.weight(1f)) {
                            if (name.isEmpty()) Text("my-new-site", color = p.muted, fontSize = 14.sp)
                            BasicTextField(name, { name = it.take(64) }, singleLine = true, cursorBrush = SolidColor(p.primary),
                                textStyle = LocalTextStyle.current.copy(color = p.text, fontSize = 14.sp),
                                modifier = Modifier.fillMaxWidth())
                        }
                    }
                    Text("Use 1–64 characters.", fontSize = 11.5.sp, color = p.muted,
                        modifier = Modifier.padding(top = 6.dp, start = 4.dp))
                    Spacer(Modifier.height(18.dp))
                    CreateButton(name = name, template = template, p = p, onCreate = onCreate, onClone = onClone)
                }
            }
        }
    }
}

@Composable
private fun StarterCard(starter: Starter, selected: Boolean, p: HomePalette, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(modifier.height(148.dp)) {
        Column(
            Modifier.fillMaxSize().clip(shape)
                .background(if (selected) p.primary.copy(alpha = 0.08f) else p.glass)
                .then(
                    if (selected) Modifier.border(2.dp, Brush.linearGradient(listOf(p.primary, p.primary2)), shape)
                    else Modifier.border(1.dp, p.border, shape)
                )
                .clickable(onClick = onClick).padding(14.dp)
        ) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(starter.tint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center) {
                ToolGlyph(starter.icon, starter.tint, Modifier.size(21.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(starter.title, color = p.text, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(starter.subtitle, color = p.muted, fontSize = 11.5.sp, lineHeight = 15.sp, maxLines = 3,
                overflow = TextOverflow.Ellipsis)
        }
        if (selected) {
            Box(Modifier.align(Alignment.TopEnd).padding(12.dp).size(22.dp)
                .shadow(6.dp, CircleShape).clip(CircleShape)
                .background(Brush.linearGradient(listOf(p.primary, p.primary2))), contentAlignment = Alignment.Center) {
                ToolGlyph(ToolIcon.Check, Color.White, Modifier.size(13.dp))
            }
        }
    }
}

@Composable
private fun CreateButton(name: String, template: String, p: HomePalette,
    onCreate: (String, String) -> Unit, onClone: () -> Unit) {
    val isGitHub = template == "GitHub"
    val enabled = isGitHub || name.isNotBlank()
    val shape = RoundedCornerShape(18.dp)
    val contentColor = if (enabled) Color.White else p.muted
    Row(
        Modifier.fillMaxWidth().height(56.dp)
            .shadow(if (enabled) 18.dp else 0.dp, shape,
                ambientColor = p.primary.copy(alpha = 0.30f), spotColor = p.primary2.copy(alpha = 0.30f))
            .clip(shape)
            .background(if (enabled) Brush.horizontalGradient(listOf(p.primary, p.primary2))
                else SolidColor(p.muted.copy(alpha = 0.16f)))
            .clickable(enabled = enabled) { if (isGitHub) onClone() else onCreate(name.trim(), template) }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(Color.White.copy(alpha = if (enabled) 0.18f else 0.4f)),
            contentAlignment = Alignment.Center) {
            ToolGlyph(if (isGitHub) ToolIcon.Git else ToolIcon.Plus, contentColor, Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(if (isGitHub) "Continue to GitHub" else "Create Project", color = contentColor,
            fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
        ToolGlyph(ToolIcon.Chevron, contentColor.copy(alpha = 0.9f), Modifier.size(16.dp))
    }
}
