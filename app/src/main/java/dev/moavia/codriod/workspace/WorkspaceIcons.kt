package dev.moavia.codriod.workspace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

enum class ToolIcon {
    Folder, File, Search, Terminal, Preview, Plus, Close, Back, Run,
    Undo, Redo, Theme, Refresh, More, Delete, Edit, Check, Chevron, Git,
    Settings, Fullscreen, Paste, Copy, Cut, Clear, History, Keyboard, CodeBrackets,
    Globe, Home, Inspect, Lock, Rotate, Star, Share, Split
}

@Composable
fun WorkspaceIconButton(icon: ToolIcon, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp).semantics { contentDescription = description }) {
        ToolGlyph(icon)
    }
}

@Composable
fun ToolGlyph(kind: ToolIcon, tint: Color = MaterialTheme.colorScheme.onSurface, modifier: Modifier = Modifier) {
    Canvas(modifier.size(20.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val stroke = Stroke(1.8f)
            fun line(x: Float, y: Float, xx: Float, yy: Float) =
                drawLine(tint, Offset(x, y), Offset(xx, yy), 1.8f)
            fun path(vararg points: Pair<Float, Float>) {
                val p = Path()
                points.forEachIndexed { i, v -> if (i == 0) p.moveTo(v.first, v.second) else p.lineTo(v.first, v.second) }
                drawPath(p, tint, style = stroke)
            }
            when (kind) {
                ToolIcon.Folder -> path(3f to 7f, 3f to 20f, 21f to 20f, 21f to 6f, 12f to 6f, 10f to 3f, 3f to 3f, 3f to 7f)
                ToolIcon.File -> { path(5f to 3f, 14f to 3f, 19f to 8f, 19f to 21f, 5f to 21f, 5f to 3f); path(14f to 3f, 14f to 8f, 19f to 8f) }
                ToolIcon.Search -> { drawCircle(tint, 6.5f, Offset(10f, 10f), style = stroke); line(15f, 15f, 21f, 21f) }
                ToolIcon.Terminal -> { path(4f to 6f, 10f to 12f, 4f to 18f); line(12f, 18f, 20f, 18f) }
                ToolIcon.Split -> {
                    drawRoundRect(tint, Offset(3f, 4f), Size(8f, 16f), CornerRadius(1.5f), style = stroke)
                    drawRoundRect(tint, Offset(13f, 4f), Size(8f, 16f), CornerRadius(1.5f), style = stroke)
                }
                ToolIcon.Preview -> { drawRoundRect(tint, Offset(2f, 3f), Size(20f, 15f), CornerRadius(2f), style = stroke); line(12f, 18f, 12f, 22f); line(7f, 22f, 17f, 22f) }
                ToolIcon.Plus -> { line(12f, 4f, 12f, 20f); line(4f, 12f, 20f, 12f) }
                ToolIcon.Close -> { line(5f, 5f, 19f, 19f); line(19f, 5f, 5f, 19f) }
                ToolIcon.Back -> { path(11f to 4f, 3f to 12f, 11f to 20f); line(3f, 12f, 21f, 12f) }
                ToolIcon.Run -> path(6f to 3f, 20f to 12f, 6f to 21f, 6f to 3f)
                ToolIcon.Undo -> { path(8f to 3f, 3f to 8f, 8f to 13f); path(3f to 8f, 15f to 8f, 20f to 12f, 20f to 17f, 16f to 21f) }
                ToolIcon.Redo -> { path(16f to 3f, 21f to 8f, 16f to 13f); path(21f to 8f, 9f to 8f, 4f to 12f, 4f to 17f, 8f to 21f) }
                ToolIcon.Theme -> { drawCircle(tint, 7f, Offset(12f, 12f), style = stroke); line(12f, 1f, 12f, 3f); line(12f, 21f, 12f, 23f); line(1f, 12f, 3f, 12f); line(21f, 12f, 23f, 12f); line(4f, 4f, 6f, 6f); line(18f, 18f, 20f, 20f) }
                ToolIcon.Refresh -> { drawArc(tint, 40f, 290f, false, Offset(4f, 4f), Size(16f, 16f), style = stroke); path(20f to 2f, 20f to 8f, 14f to 8f) }
                ToolIcon.More -> for (y in listOf(5f, 12f, 19f)) drawCircle(tint, 1.8f, Offset(12f, y))
                ToolIcon.Delete -> { path(5f to 6f, 6f to 21f, 18f to 21f, 19f to 6f); line(3f, 6f, 21f, 6f); path(8f to 6f, 8f to 2f, 16f to 2f, 16f to 6f) }
                ToolIcon.Edit -> { path(3f to 21f, 5f to 14f, 17f to 2f, 22f to 7f, 10f to 19f, 3f to 21f); line(15f, 4f, 20f, 9f) }
                ToolIcon.Check -> path(3f to 12f, 9f to 18f, 21f to 5f)
                ToolIcon.Chevron -> path(9f to 5f, 16f to 12f, 9f to 19f)
                ToolIcon.Git -> {
                    line(7f, 5f, 7f, 19f); line(7f, 12f, 17f, 12f); line(17f, 12f, 17f, 6f)
                    drawCircle(tint, 2.2f, Offset(7f, 5f)); drawCircle(tint, 2.2f, Offset(7f, 19f))
                    drawCircle(tint, 2.2f, Offset(17f, 6f)); drawCircle(tint, 2.2f, Offset(7f, 12f))
                }
                ToolIcon.Settings -> {
                    drawCircle(tint, 4.5f, Offset(12f, 12f), style = stroke)
                    for (i in 0..7) {
                        val angle = (i * 45) * (Math.PI / 180.0)
                        val x1 = (12.0 + Math.cos(angle) * 7.5).toFloat()
                        val y1 = (12.0 + Math.sin(angle) * 7.5).toFloat()
                        val x2 = (12.0 + Math.cos(angle) * 10.5).toFloat()
                        val y2 = (12.0 + Math.sin(angle) * 10.5).toFloat()
                        line(x1, y1, x2, y2)
                    }
                }
                ToolIcon.Fullscreen -> {
                    path(4f to 9f, 4f to 4f, 9f to 4f)
                    path(15f to 4f, 20f to 4f, 20f to 9f)
                    path(4f to 15f, 4f to 20f, 9f to 20f)
                    path(15f to 20f, 20f to 20f, 20f to 15f)
                }
                ToolIcon.Paste -> {
                    drawRoundRect(tint, Offset(5f, 5f), Size(14f, 16f), CornerRadius(2.5f), style = stroke)
                    drawRoundRect(tint, Offset(8f, 2f), Size(8f, 5f), CornerRadius(1.5f), style = stroke)
                    line(8f, 10f, 16f, 10f); line(8f, 14f, 16f, 14f); line(8f, 17f, 13f, 17f)
                }
                ToolIcon.Copy -> {
                    drawRoundRect(tint, Offset(7f, 7f), Size(12f, 13f), CornerRadius(2f), style = stroke)
                    path(4f to 16f, 4f to 4f, 16f to 4f)
                }
                ToolIcon.Cut -> {
                    drawCircle(tint, 2.6f, Offset(6f, 6f), style = stroke)
                    drawCircle(tint, 2.6f, Offset(6f, 18f), style = stroke)
                    line(8.4f, 7.4f, 20f, 18f)
                    line(8.4f, 16.6f, 20f, 6f)
                }
                ToolIcon.Clear -> {
                    path(6f to 15f, 12f to 9f, 18f to 15f)
                }
                ToolIcon.History -> {
                    drawCircle(tint, 8.5f, Offset(12f, 12f), style = stroke)
                    line(12f, 7f, 12f, 12f)
                    line(12f, 12f, 16f, 14f)
                }
                ToolIcon.Keyboard -> {
                    drawRoundRect(tint, Offset(2f, 5f), Size(20f, 14f), CornerRadius(2.5f), style = stroke)
                    line(5f, 9f, 7f, 9f); line(9f, 9f, 11f, 9f); line(13f, 9f, 15f, 9f); line(17f, 9f, 19f, 9f)
                    line(6f, 12f, 8f, 12f); line(10f, 12f, 14f, 12f); line(16f, 12f, 18f, 12f)
                    line(7f, 15f, 17f, 15f)
                }
                ToolIcon.CodeBrackets -> {
                    // Left bracket <
                    path(8f to 6f, 3f to 12f, 8f to 18f)
                    // Slash /
                    line(14f, 4f, 10f, 20f)
                    // Right bracket >
                    path(16f to 6f, 21f to 12f, 16f to 18f)
                }
                ToolIcon.Globe -> {
                    drawCircle(tint, 9f, Offset(12f, 12f), style = stroke)
                    line(3f, 12f, 21f, 12f)
                    drawOval(tint, Offset(8f, 3f), Size(8f, 18f), style = stroke)
                }
                ToolIcon.Home -> { path(3f to 11f, 12f to 4f, 21f to 11f); path(5f to 10f, 5f to 20f, 19f to 20f, 19f to 10f) }
                ToolIcon.Inspect -> { path(4f to 4f, 11f to 20f, 13f to 13f, 20f to 11f, 4f to 4f); line(13f, 13f, 19f, 19f) }
                ToolIcon.Lock -> {
                    drawRoundRect(tint, Offset(5f, 11f), Size(14f, 10f), CornerRadius(2f), style = stroke)
                    drawArc(tint, 180f, 180f, false, Offset(8f, 4f), Size(8f, 10f), style = stroke)
                }
                ToolIcon.Rotate -> { drawArc(tint, 200f, 260f, false, Offset(4f, 4f), Size(16f, 16f), style = stroke); path(4f to 5f, 5f to 10f, 10f to 9f) }
                ToolIcon.Star -> path(12f to 3f, 14.6f to 9f, 21f to 9.5f, 16f to 13.8f, 17.6f to 20f, 12f to 16.6f, 6.4f to 20f, 8f to 13.8f, 3f to 9.5f, 9.4f to 9f, 12f to 3f)
                ToolIcon.Share -> {
                    drawCircle(tint, 2.5f, Offset(18f, 5f), style = stroke); drawCircle(tint, 2.5f, Offset(6f, 12f), style = stroke)
                    drawCircle(tint, 2.5f, Offset(18f, 19f), style = stroke); line(8.2f, 10.8f, 15.8f, 6.2f); line(8.2f, 13.2f, 15.8f, 17.8f)
                }
            }
        }
    }
}
