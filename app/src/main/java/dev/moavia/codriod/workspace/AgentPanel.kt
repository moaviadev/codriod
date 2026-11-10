package dev.moavia.codriod.workspace

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

/** The small purple AI tab on the editor's right edge. Tap or swipe left to open the agent. */
@Composable
fun BoxScope.AgentEdgeHandle(model: WorkspaceModel) {
    val busy = model.agent.running
    Column(Modifier.align(Alignment.CenterEnd).offset(y = (-40).dp).width(30.dp).height(92.dp)
        .shadow(12.dp, RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp))
        .clip(RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp))
        .background(Brush.verticalGradient(listOf(Color(0xFF7C3AED), Color(0xFF2563EB))))
        .clickable { model.agentPanelOpen = true }
        .pointerInput(Unit) { detectHorizontalDragGestures { _, drag -> if (drag < -12) model.agentPanelOpen = true } },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("‹", color = Color.White, fontSize = 15.sp)
        Text("AI", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(if (busy) "●" else "✦", color = if (busy) Color(0xFF4ADE80) else Color.White, fontSize = 11.sp)
    }
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
