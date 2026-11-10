package dev.moavia.codriod.workspace

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun WorkspaceEditor(model: WorkspaceModel) {
    val project = model.project ?: return
    val code = LocalCodeColors.current
    val density = LocalDensity.current
    var sheetFraction by remember(project.id) { mutableFloatStateOf(0.36f) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().height(44.dp).background(code.header), verticalAlignment = Alignment.CenterVertically) {
            WorkspaceIconButton(ToolIcon.Back, "Back to projects") { model.goHome() }
            Column(Modifier.weight(1f)) {
                Text(project.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("LOCAL WORKSPACE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            WorkspaceIconButton(ToolIcon.Undo, "Undo", model::undo)
            WorkspaceIconButton(ToolIcon.Redo, "Redo", model::redo)
            WorkspaceIconButton(ToolIcon.Check, "Save project") { model.save() }
            WorkspaceIconButton(ToolIcon.Search, "Find in project") { model.searchSheet = true }
            WorkspaceIconButton(ToolIcon.Run, "Run current file") { model.runActive() }
        }
        EditorTabs(model)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val boxHeightPx = with(density) { maxHeight.toPx() }
            Column(Modifier.fillMaxSize()) {
                val active = model.activeDocument
                if (active != null && active.image) ImageViewer(model, active, Modifier.weight(1f))
                else { CodeEditor(model, Modifier.weight(1f)); CodingKeys(model) }
                Row(Modifier.fillMaxWidth().height(31.dp).background(code.header)
                    .clickable { if (model.terminalOpen) model.terminalOpen = false else model.showTerminal() },
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (model.terminalOpen) "⌄" else "›", Modifier.padding(start = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    ToolGlyph(ToolIcon.Terminal, if (model.terminalOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text("Terminal", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(8.dp))
                    Text(model.terminal?.status ?: "sh · local",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f), maxLines = 1)
                    TextButton(onClick = { if (model.terminalOpen) model.terminal?.restart() else model.showTerminal() },
                        contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text(if (model.terminalOpen) "Restart" else "Open")
                    }
                }
                if (model.terminalOpen) {
                    BoxWithConstraints {
                        val availableHeight = maxHeight
                        val allowedHeight = (availableHeight * sheetFraction).coerceAtLeast(140.dp)
                        Column(Modifier.fillMaxWidth().height(allowedHeight)) {
                            Box(Modifier.fillMaxWidth().height(18.dp).background(code.header)
                                .pointerInput(availableHeight) {
                                    detectVerticalDragGestures { _, drag ->
                                        if (boxHeightPx > 0) {
                                            val fraction = with(density) { drag.toDp().value / with(density) { boxHeightPx.toDp().value } }
                                            sheetFraction = (sheetFraction - fraction).coerceIn(0.23f, 0.62f)
                                        }
                                    }
                                }, contentAlignment = Alignment.Center) {
                                Box(Modifier.width(34.dp).height(3.dp).clip(RoundedCornerShape(3.dp))
                                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)))
                            }
                            model.terminal?.let { WorkspaceTerminal(it, Modifier.fillMaxSize()) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorTabs(model: WorkspaceModel) {
    Row(Modifier.fillMaxWidth().height(34.dp).horizontalScroll(rememberScrollState())
        .background(MaterialTheme.colorScheme.surface)) {
        model.documents.toList().forEach { document ->
            val selected = model.activePath == document.path
            Row(Modifier.widthIn(min = 92.dp, max = 210.dp).fillMaxHeight()
                .background(if (selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surface)
                .clickable { model.select(document.path) }.padding(start = 11.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (document.image) Text("🖼", fontSize = 12.sp)
                else ToolGlyph(if (document.path.endsWith(".html", true) || document.path.endsWith(".sh")) ToolIcon.File
                    else ToolIcon.Edit, if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(document.path.substringAfterLast('/'), modifier = Modifier.weight(1f),
                    color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (document.dirty) Text("●", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                WorkspaceIconButton(ToolIcon.Close, "Close ${document.path.substringAfterLast('/')}") {
                    model.closeTab(document.path)
                }
            }
        }
        WorkspaceIconButton(ToolIcon.Plus, "Browse files") { model.fileSheet = true }
    }
}

@Composable
private fun CodingKeys(model: WorkspaceModel) {
    val tokens = listOf("Tab", "(", ")", "{", "}", "[", "]", "\"", "'", "=", "+", ":", ";", "#", "\u0024", "/", "\\", "|", "<", ">", "_", "-", "←", "→")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        .background(MaterialTheme.colorScheme.surface), verticalAlignment = Alignment.CenterVertically) {
        tokens.forEach { token ->
            TextButton(onClick = {
                when (token) {
                    "Tab" -> model.insert("\t")
                    "←" -> model.moveCursor(-1)
                    "→" -> model.moveCursor(1)
                    else -> model.insert(token)
                }
            },
                modifier = Modifier.widthIn(min = 38.dp).height(36.dp),
                contentPadding = PaddingValues(horizontal = 7.dp, vertical = 2.dp)) {
                Text(token, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            }
        }
    }
}

@Composable
private fun CodeEditor(model: WorkspaceModel, modifier: Modifier = Modifier) {
    val document = model.activeDocument
    val colors = LocalCodeColors.current
    val scrollY = rememberScrollState()
    val scrollX = rememberScrollState()
    val requester = remember { FocusRequester() }
    val density = LocalDensity.current
    var availableWidth by remember { mutableFloatStateOf(390f) }
    val gutter = 48.dp
    val minimumEditorWidth = with(density) { (availableWidth - gutter.toPx()).coerceAtLeast(200f).toDp() }
    BoxWithConstraints(modifier.background(MaterialTheme.colorScheme.background)
        .onSizeChanged { availableWidth = it.width.toFloat() }) {
        Row(Modifier.fillMaxSize().verticalScroll(scrollY).horizontalScroll(scrollX), verticalAlignment = Alignment.Top) {
            if (document != null) {
                val lineCount = document.value.text.count { it == '\n' } + 1
                Text((1..lineCount).joinToString("\n"),
                    modifier = Modifier.width(gutter).padding(start = 4.dp, top = 9.dp, end = 10.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.74f),
                    fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End)
                BasicTextField(
                    value = document.value, onValueChange = { model.edit(document, it) },
                    modifier = Modifier.widthIn(min = minimumEditorWidth).fillMaxHeight()
                        .focusRequester(requester)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) false
                            else when {
                                event.key == Key.Tab -> { model.insert("\t"); true }
                                event.isCtrlPressed && event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_S -> { model.save(); true }
                                event.isCtrlPressed && event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_Z -> { model.undo(); true }
                                event.isCtrlPressed && event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_Y -> { model.redo(); true }
                                else -> false
                            }
                        }.padding(start = 8.dp, top = 8.dp, bottom = 24.dp, end = 20.dp),
                    textStyle = TextStyle(
                        color = if (colors.dark) androidx.compose.ui.graphics.Color(0xFFD4D4D4)
                            else androidx.compose.ui.graphics.Color(0xFF333333),
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp
                    ),
                    visualTransformation = remember(document.path, colors) { SourceHighlighting(document.path, colors) },
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    singleLine = false, maxLines = Int.MAX_VALUE
                )
            } else Column(Modifier.fillMaxWidth().padding(22.dp)) {
                Text("No file is open.", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("Open a file from Files or create one to start editing.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private class SourceHighlighting(private val path: String, private val colors: CodeColors) :
    androidx.compose.ui.text.input.VisualTransformation {
    private val tokens = Regex("""(?:"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|//[^\n]*|/\*[\s\S]*?\*/|#[^\n]*|</?[A-Za-z][^>]*>|\b(?:fun|val|var|class|object|if|else|when|return|for|in|while|true|false|const|let|function|export|import|from|new|document|querySelector)\b|\b[0-9]+\b)""")
    override fun filter(text: AnnotatedString): androidx.compose.ui.text.input.TransformedText {
        val result = AnnotatedString.Builder(text)
        if (path.endsWith(".md", true)) return androidx.compose.ui.text.input.TransformedText(
            text, androidx.compose.ui.text.input.OffsetMapping.Identity)
        for (match in tokens.findAll(text)) {
            val token = match.value
            val color = when {
                token.startsWith("//") || token.startsWith("#") || token.startsWith("/*") -> colors.comment
                token.startsWith("\"") || token.startsWith("'") -> colors.string
                token.startsWith("<") -> colors.keyword
                token.firstOrNull()?.isDigit() == true -> if (colors.dark)
                    androidx.compose.ui.graphics.Color(0xFFB5CEA8) else androidx.compose.ui.graphics.Color(0xFF098658)
                else -> colors.keyword
            }
            result.addStyle(SpanStyle(color = color), match.range.first, match.range.last + 1)
        }
        return androidx.compose.ui.text.input.TransformedText(result.toAnnotatedString(),
            androidx.compose.ui.text.input.OffsetMapping.Identity)
    }
}
