package dev.moavia.codriod.workspace

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.moavia.codriod.agent.AgentChat
import dev.moavia.codriod.agent.AgentColors
import dev.moavia.codriod.agent.AgentSettingsScreen

/** Rotates content by -90° and swaps its measured width and height, for vertical tab labels. */
private fun Modifier.verticalText() = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = Constraints.Infinity, maxHeight = Constraints.Infinity))
    layout(placeable.height, placeable.width) {
        placeable.place(-(placeable.width - placeable.height) / 2, (placeable.width - placeable.height) / 2)
    }
}.rotate(-90f)

/**
 * Tabs on the editor's right edge: Codriod (the agent) and every AI CLI the user pinned. Tap or swipe left to open;
 * drag up or down to move them away from the code. They are narrow and slightly see-through so code stays readable.
 */
@Composable
fun BoxScope.AgentEdgeHandle(model: WorkspaceModel) {
    val busy = model.agent.running
    val density = LocalDensity.current
    Column(Modifier.align(Alignment.CenterEnd).offset(y = model.edgeOffset.dp).alpha(0.92f)
        .pointerInput(Unit) { detectVerticalDragGestures { change, drag -> change.consume(); model.moveEdgeTabs(with(density) { drag.toDp().value }) } },
        verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.End) {
        EdgeTab(Brush.verticalGradient(listOf(Color(0xFF7C3AED), Color(0xFF2563EB))), onOpen = { model.agentPanelOpen = true }) {
            Text(if (busy) "●" else "‹", color = if (busy) Color(0xFF4ADE80) else Color.White, fontSize = 11.sp)
            Text("Codriod", color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
                modifier = Modifier.padding(vertical = 2.dp).verticalText())
        }
        model.linux.pinned.mapNotNull { dev.moavia.codriod.linux.CliCatalog.byId(it) }.filter { it.id in model.linux.installed }.forEach { cli ->
            EdgeTab(Brush.verticalGradient(listOf(Color(cli.color), Color(cli.color).copy(alpha = 0.75f))), onOpen = { model.openCli(cli.id) }) {
                Text("›", color = Color.White, fontSize = 10.sp)
                Text(cli.name.substringBefore(' ').take(8), color = Color.White, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, softWrap = false, modifier = Modifier.padding(vertical = 2.dp).verticalText())
            }
        }
    }
}

@Composable
private fun EdgeTab(brush: Brush, onOpen: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.width(22.dp)
        .shadow(8.dp, RoundedCornerShape(topStart = 11.dp, bottomStart = 11.dp))
        .clip(RoundedCornerShape(topStart = 11.dp, bottomStart = 11.dp))
        .background(brush)
        .clickable(onClick = onOpen)
        .pointerInput(Unit) { detectHorizontalDragGestures { _, drag -> if (drag < -12) onOpen() } }
        .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, content = content)
}

/** The agent panel that slides in from the right over the editor. */
@Composable
fun AgentPanelOverlay(model: WorkspaceModel) {
    AnimatedVisibility(model.agentPanelOpen, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(remember { MutableInteractionSource() }, null) { model.agentPanelOpen = false })
    }
    AnimatedVisibility(model.agentPanelOpen, enter = slideInHorizontally { it }, exit = slideOutHorizontally { it }) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            val width = if (model.agentPanelExpanded) maxWidth else minOf(maxWidth * 0.92f, 520.dp)
            Box(Modifier.width(width).fillMaxHeight().background(AgentColors.Panel)
                .clickable(remember { MutableInteractionSource() }, null) {}
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom))
                .imePadding()) {
                if (model.agentSettingsOpen) AgentSettingsScreen(model.agent, onBack = { model.agentSettingsOpen = false })
                else AgentChat(model.agent, model.project?.name, onClose = { model.agentPanelOpen = false },
                    expanded = model.agentPanelExpanded, onToggleExpand = { model.agentPanelExpanded = !model.agentPanelExpanded },
                    onOpenSettings = { model.agentSettingsOpen = true }, onTakeOver = model::showAgentBrowser)
            }
        }
    }
}

/**
 * Keeps the agent's browser tab rendering while the browser screen is closed, so the agent can load pages and
 * take screenshots. It sits behind the app's content at the page's CSS size.
 */
@Composable
fun AgentBrowserHost(model: WorkspaceModel) {
    val browser = model.browser
    val tab = browser.tabs.firstOrNull { it.agent } ?: return
    if (model.previewOpen && browser.active?.id == tab.id) return
    val w = if (browser.mode == DeviceMode.Mobile) 390 else browser.mode.width
    val h = if (browser.mode == DeviceMode.Mobile) 844 else browser.mode.height
    Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(w.dp, h.dp)) {
        key(tab.id) { AndroidView(factory = { browser.webViewFor(tab, it) }, modifier = Modifier.fillMaxSize()) }
    }
}
