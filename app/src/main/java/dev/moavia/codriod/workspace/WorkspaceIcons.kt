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

enum class ToolIcon { Folder, File, Search, Terminal, Preview, Plus, Close, Back, Run,
    Undo, Redo, Theme, Refresh, More, Delete, Edit, Check, Chevron, Git }

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
            val stroke = Stroke(1.6f)
            fun line(x: Float, y: Float, xx: Float, yy: Float) =
                drawLine(tint, Offset(x, y), Offset(xx, yy), 1.6f)
            fun path(vararg points: Pair<Float, Float>) {
                val p = Path()
                points.forEachIndexed { i, v -> if (i == 0) p.moveTo(v.first, v.second) else p.lineTo(v.first, v.second) }
                drawPath(p, tint, style = stroke)
            }
            when (kind) {
                ToolIcon.Folder -> path(3f to 7f, 3f to 20f, 21f to 20f, 21f to 6f, 12f to 6f, 10f to 3f, 3f to 3f, 3f to 7f)
                ToolIcon.File -> { path(5f to 3f, 14f to 3f, 19f to 8f, 19f to 21f, 5f to 21f, 5f to 3f); path(14f to 3f, 14f to 8f, 19f to 8f) }
                ToolIcon.Search -> { drawCircle(tint, 6.5f, Offset(10f, 10f), style = stroke); line(15f, 15f, 21f, 21f) }
                ToolIcon.Terminal -> { path(4f to 6f, 10f to 12f, 4f to 18f); line(12f, 18f, 21f, 18f) }
                ToolIcon.Preview -> { drawRoundRect(tint, Offset(2f, 3f), Size(20f, 15f), CornerRadius(2f), style = stroke); line(12f, 18f, 12f, 22f); line(7f, 22f, 17f, 22f) }
                ToolIcon.Plus -> { line(12f, 4f, 12f, 20f); line(4f, 12f, 20f, 12f) }
                ToolIcon.Close -> { line(5f, 5f, 19f, 19f); line(19f, 5f, 5f, 19f) }
                ToolIcon.Back -> { path(11f to 4f, 3f to 12f, 11f to 20f); line(3f, 12f, 21f, 12f) }
                ToolIcon.Run -> path(6f to 3f, 21f to 12f, 6f to 21f, 6f to 3f)
                ToolIcon.Undo -> { path(8f to 3f, 3f to 8f, 8f to 13f); path(3f to 8f, 15f to 8f, 20f to 12f, 20f to 17f, 16f to 21f) }
                ToolIcon.Redo -> { path(16f to 3f, 21f to 8f, 16f to 13f); path(21f to 8f, 9f to 8f, 4f to 12f, 4f to 17f, 8f to 21f) }
                ToolIcon.Theme -> { drawCircle(tint, 7f, Offset(12f, 12f), style = stroke); line(12f, 1f, 12f, 3f); line(12f, 21f, 12f, 23f); line(1f, 12f, 3f, 12f); line(21f, 12f, 23f, 12f); line(4f, 4f, 6f, 6f); line(18f, 18f, 20f, 20f) }
                ToolIcon.Refresh -> { drawArc(tint, 40f, 290f, false, Offset(4f, 4f), Size(16f, 16f), style = stroke); path(20f to 2f, 20f to 8f, 14f to 8f) }
                ToolIcon.More -> for (y in listOf(5f, 12f, 19f)) drawCircle(tint, 1.5f, Offset(12f, y))
                ToolIcon.Delete -> { path(5f to 6f, 6f to 21f, 18f to 21f, 19f to 6f); line(3f, 6f, 21f, 6f); path(8f to 6f, 8f to 2f, 16f to 2f, 16f to 6f) }
                ToolIcon.Edit -> { path(3f to 21f, 5f to 14f, 17f to 2f, 22f to 7f, 10f to 19f, 3f to 21f); line(15f, 4f, 20f, 9f) }
                ToolIcon.Check -> path(3f to 12f, 9f to 18f, 21f to 5f)
                ToolIcon.Chevron -> path(9f to 5f, 16f to 12f, 9f to 19f)
                ToolIcon.Git -> {
                    line(7f, 5f, 7f, 19f); line(7f, 12f, 17f, 12f); line(17f, 12f, 17f, 6f)
                    drawCircle(tint, 2.2f, Offset(7f, 5f)); drawCircle(tint, 2.2f, Offset(7f, 19f))
                    drawCircle(tint, 2.2f, Offset(17f, 6f)); drawCircle(tint, 2.2f, Offset(7f, 12f))
                }
            }
        }
    }
}
