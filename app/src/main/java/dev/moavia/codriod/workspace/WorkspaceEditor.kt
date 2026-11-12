package dev.moavia.codriod.workspace

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Purple accent used only by the new editor surfaces (tabs, suggestions), keeping the blue brand. */
private val EditorAccent = Color(0xFF7C6CF5)

@Composable
fun WorkspaceEditor(model: WorkspaceModel) {
    val project = model.project ?: return
    val code = LocalCodeColors.current
    val density = LocalDensity.current
    var sheetFraction by remember(project.id) { mutableFloatStateOf(0.36f) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        EditorToolbar(model)
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

// ---------------------------------------------------------------------------------------------
// Toolbar
// ---------------------------------------------------------------------------------------------

@Composable
private fun EditorToolbar(model: WorkspaceModel) {
    val project = model.project ?: return
    val code = LocalCodeColors.current
    var more by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(code.header)) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            ToolbarIcon(ToolIcon.Back, "Back to projects") { model.goHome() }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(project.name, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text("LOCAL WORKSPACE · ${model.entries.size} files",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            ToolbarIcon(ToolIcon.Copy, "Copy code") { model.copyAll() }
            Box {
                ToolbarIcon(ToolIcon.More, "More options") { more = true }
                DropdownMenu(more, { more = false }) {
                    val pick: (() -> Unit) -> Unit = { action -> more = false; action() }
                    MoreMenuItem("Copy code", ToolIcon.Copy) { pick { model.copyAll() } }
                    MoreMenuItem("Copy selection", ToolIcon.Copy) { pick { model.copySelection() } }
                    MoreMenuItem("Cut", ToolIcon.Cut) { pick { model.cutSelection() } }
                    MoreMenuItem("Paste", ToolIcon.Paste) { pick { model.paste() } }
                    MoreMenuItem("Select all", ToolIcon.CodeBrackets) { pick { model.selectAll() } }
                    HorizontalDivider()
                    MoreMenuItem("Find in file", ToolIcon.Search) { pick { model.findOpen = true } }
                    MoreMenuItem("Save", ToolIcon.Check) { pick { model.save() } }
                    MoreMenuItem("Run / preview", ToolIcon.Run) { pick { model.runActive() } }
                    MoreMenuItem("Toggle terminal", ToolIcon.Terminal) {
                        pick { if (model.terminalOpen) model.terminalOpen = false else model.showTerminal() }
                    }
                    HorizontalDivider()
                    MoreMenuItem("Files", ToolIcon.Folder) { pick { model.fileSheet = true } }
                    MoreMenuItem("Search project", ToolIcon.Search) { pick { model.searchSheet = true } }
                    MoreMenuItem("Git", ToolIcon.Git) { pick { model.showGit() } }
                    MoreMenuItem("Browser", ToolIcon.Globe) { pick { model.showPreview() } }
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(46.dp).horizontalScroll(rememberScrollState())
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
            .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionChip(ToolIcon.Undo, "Undo") { model.undo() }
            ActionChip(ToolIcon.Redo, "Redo") { model.redo() }
            ActionChip(ToolIcon.Check, "Save") { model.save() }
            ActionChip(ToolIcon.Search, "Find") { model.findOpen = true }
            ActionChip(ToolIcon.Run, "Run") { model.runActive() }
            ActionChip(ToolIcon.Copy, "Copy") { model.copyAll() }
        }
    }
}

@Composable
private fun ToolbarIcon(icon: ToolIcon, description: String, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        ToolGlyph(icon, MaterialTheme.colorScheme.onSurface, Modifier.size(19.dp))
    }
}

@Composable
private fun ActionChip(icon: ToolIcon, label: String, onClick: () -> Unit) {
    Row(Modifier.height(32.dp).clip(RoundedCornerShape(10.dp))
        .background(MaterialTheme.colorScheme.surfaceContainer)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
        .clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        ToolGlyph(icon, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

@Composable
private fun MoreMenuItem(label: String, icon: ToolIcon, onClick: () -> Unit) {
    DropdownMenuItem(text = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ToolGlyph(icon, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(16.dp))
            Spacer(Modifier.width(10.dp))
            Text(label)
        }
    }, onClick = onClick)
}

// ---------------------------------------------------------------------------------------------
// Tabs
// ---------------------------------------------------------------------------------------------

@Composable
private fun EditorTabs(model: WorkspaceModel) {
    val tabs = model.documents.toList()
    val listState = rememberLazyListState()
    val activeIndex = tabs.indexOfFirst { it.path == model.activePath }
    LaunchedEffect(model.activePath, tabs.size) {
        if (activeIndex >= 0) listState.animateScrollToItem(activeIndex)
    }
    Row(Modifier.fillMaxWidth().height(38.dp)
        .background(MaterialTheme.colorScheme.surface), verticalAlignment = Alignment.CenterVertically) {
        LazyRow(state = listState, modifier = Modifier.weight(1f).fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically) {
        itemsIndexed(tabs, key = { _, document -> document.path }) { _, document ->
            val selected = model.activePath == document.path
            Column(Modifier.widthIn(min = 104.dp, max = 220.dp).fillMaxHeight()
                .background(if (selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surface)
                .clickable { model.select(document.path) }) {
                Box(Modifier.fillMaxWidth().height(2.dp)
                    .background(if (selected) EditorAccent else Color.Transparent))
                Row(Modifier.fillMaxWidth().weight(1f).padding(start = 10.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    FileTypeIcon(document.path)
                    Spacer(Modifier.width(6.dp))
                    Text(document.path.substringAfterLast('/'), modifier = Modifier.weight(1f),
                        color = if (selected) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (document.dirty) Box(Modifier.size(6.dp).clip(CircleShape).background(EditorAccent))
                    Spacer(Modifier.width(2.dp))
                    Box(Modifier.size(26.dp).clip(RoundedCornerShape(8.dp))
                        .clickable { model.closeTab(document.path) }
                        .semantics { contentDescription = "Close ${document.path.substringAfterLast('/')}" },
                        contentAlignment = Alignment.Center) {
                        ToolGlyph(ToolIcon.Close, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(13.dp))
                    }
                }
            }
            Box(Modifier.width(1.dp).height(20.dp).background(MaterialTheme.colorScheme.outlineVariant))
        }
        }
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).clickable { model.fileSheet = true }
            .semantics { contentDescription = "Open or create file" }, contentAlignment = Alignment.Center) {
            ToolGlyph(ToolIcon.Plus, MaterialTheme.colorScheme.primary, Modifier.size(17.dp))
        }
        Spacer(Modifier.width(6.dp))
    }
}

@Composable
private fun FileTypeIcon(path: String) {
    val language = Languages.of(path)
    val (icon, color) = when {
        ImageFiles.isImage(path) -> ToolIcon.Preview to Color(0xFF9C8CFF)
        else -> when (language) {
            EditorLanguage.Html -> ToolIcon.CodeBrackets to Color(0xFFE44D26)
            EditorLanguage.Css -> ToolIcon.Edit to Color(0xFF42A5F5)
            EditorLanguage.JavaScript, EditorLanguage.TypeScript, EditorLanguage.Jsx, EditorLanguage.Tsx ->
                ToolIcon.CodeBrackets to Color(0xFFF0DB4F)
            EditorLanguage.Json -> ToolIcon.CodeBrackets to Color(0xFFB5CEA8)
            EditorLanguage.Markdown -> ToolIcon.File to Color(0xFF8AB4F8)
            EditorLanguage.Python -> ToolIcon.CodeBrackets to Color(0xFF4B8BBE)
            EditorLanguage.Shell -> ToolIcon.Terminal to Color(0xFF89D185)
            EditorLanguage.Sql -> ToolIcon.CodeBrackets to Color(0xFFF29111)
            EditorLanguage.Plain -> ToolIcon.File to MaterialTheme.colorScheme.onSurfaceVariant
            else -> ToolIcon.CodeBrackets to Color(0xFF9C8CFF)
        }
    }
    Box(Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center) {
        ToolGlyph(icon, color, Modifier.size(13.dp))
    }
}

// ---------------------------------------------------------------------------------------------
// Coding shortcut row
// ---------------------------------------------------------------------------------------------

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

// ---------------------------------------------------------------------------------------------
// Code editor + autocomplete + find
// ---------------------------------------------------------------------------------------------

private const val CHAR_WIDTH_DP = 7.8f
private const val LINE_HEIGHT_DP = 20f

@Composable
private fun CodeEditor(model: WorkspaceModel, modifier: Modifier = Modifier) {
    val document = model.activeDocument
    val colors = LocalCodeColors.current
    val scrollY = rememberScrollState()
    val scrollX = rememberScrollState()
    val requester = remember { FocusRequester() }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var availableWidth by remember { mutableFloatStateOf(390f) }
    val gutter = 48.dp
    val minimumEditorWidth = with(density) { (availableWidth - gutter.toPx()).coerceAtLeast(200f).toDp() }
    val lineHeightPx = with(density) { LINE_HEIGHT_DP.dp.toPx() }

    var suggestion by remember(document?.path) { mutableStateOf<CompletionResult?>(null) }
    var selectedIndex by remember(document?.path) { mutableIntStateOf(0) }
    var dismissedForText by remember(document?.path) { mutableStateOf<String?>(null) }

    val text = document?.value?.text.orEmpty()
    val selection = document?.value?.selection ?: TextRange(0)
    val caret = selection.start.coerceIn(0, text.length)
    val collapsed = selection.collapsed

    LaunchedEffect(document?.path, text, caret, collapsed) {
        if (document == null || document.image || !collapsed) { suggestion = null; return@LaunchedEffect }
        if (dismissedForText == text) return@LaunchedEffect
        delay(140)
        val result = CompletionEngine.suggest(document.path, text, caret)
        suggestion = result?.takeIf { it.items.isNotEmpty() }
        selectedIndex = 0
    }

    val lineColumn = remember(text, caret) { lineAndColumn(text, caret) }
    val scrollXdp = with(density) { scrollX.value.toDp().value }
    val scrollYdp = with(density) { scrollY.value.toDp().value }
    val cursorXDp = gutter.value + 8f + lineColumn.second * CHAR_WIDTH_DP - scrollXdp
    val lineTopDp = 8f + lineColumn.first * LINE_HEIGHT_DP - scrollYdp
    val caretBottomDp = lineTopDp + LINE_HEIGHT_DP

    val applySelected: (CompletionResult) -> Unit = { result ->
        val item = result.items.getOrNull(selectedIndex)
        if (item != null) {
            model.applyCompletion(item, result.replaceStart, result.replaceEnd)
            dismissedForText = null
            suggestion = null
            runCatching { requester.requestFocus() }
        }
    }

    BoxWithConstraints(modifier.background(MaterialTheme.colorScheme.background)
        .onSizeChanged { availableWidth = it.width.toFloat() }) {
        val editorMinHeight = maxHeight
        if (document != null && collapsed && lineTopDp > -LINE_HEIGHT_DP && lineTopDp < maxHeight.value) {
            Box(Modifier.offset(y = lineTopDp.dp).fillMaxWidth().height(LINE_HEIGHT_DP.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)))
        }
        Row(Modifier.fillMaxSize().verticalScroll(scrollY).horizontalScroll(scrollX), verticalAlignment = Alignment.Top) {
            if (document != null) {
                val lineCount = document.value.text.count { it == '\n' } + 1
                Text((1..lineCount).joinToString("\n"),
                    modifier = Modifier.width(gutter).padding(start = 4.dp, top = 9.dp, end = 10.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.74f),
                    fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = LINE_HEIGHT_DP.sp,
                    textAlign = TextAlign.End)
                BasicTextField(
                    value = document.value,
                    onValueChange = { new ->
                        val adjusted = SmartEdit.apply(document.value, new) ?: new
                        if (adjusted.text != document.value.text) dismissedForText = null
                        model.edit(document, adjusted)
                    },
                    modifier = Modifier.widthIn(min = minimumEditorWidth).defaultMinSize(minHeight = editorMinHeight)
                        .focusRequester(requester)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) false
                            else {
                                val active = suggestion
                                if (active != null && active.items.isNotEmpty()) {
                                    when {
                                        event.key == Key.DirectionDown -> {
                                            selectedIndex = (selectedIndex + 1) % active.items.size; true
                                        }
                                        event.key == Key.DirectionUp -> {
                                            selectedIndex = (selectedIndex - 1 + active.items.size) % active.items.size; true
                                        }
                                        event.key == Key.Enter || event.key == Key.Tab -> {
                                            applySelected(active); true
                                        }
                                        event.key == Key.Escape -> {
                                            dismissedForText = document.value.text; suggestion = null; true
                                        }
                                        else -> handleEditorKey(model, event)
                                    }
                                } else handleEditorKey(model, event)
                            }
                        }.padding(start = 8.dp, top = 8.dp, bottom = 24.dp, end = 20.dp),
                    textStyle = LocalTextStyle.current.copy(
                        color = if (colors.dark) Color(0xFFD4D4D4) else Color(0xFF333333),
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = LINE_HEIGHT_DP.sp
                    ),
                    visualTransformation = remember(document.path, colors) { SourceHighlighting(document.path, colors) },
                    cursorBrush = SolidColor(EditorAccent),
                    singleLine = false, maxLines = Int.MAX_VALUE
                )
            } else Column(Modifier.fillMaxWidth().padding(22.dp)) {
                Text("No file is open.", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("Open a file from Files or create one to start editing.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (document != null && model.findOpen) {
            FindBar(model, scrollY, lineHeightPx, scope, Modifier.align(Alignment.TopCenter))
        }
        val current = suggestion
        if (document != null && current != null) {
            val panelWidth = 300.dp
            val panelMaxHeight = 196.dp
            val x = cursorXDp.dp.coerceIn(8.dp, (maxWidth - panelWidth - 8.dp).coerceAtLeast(8.dp))
            var y = caretBottomDp.dp
            if (y + panelMaxHeight > maxHeight) y = (caretBottomDp.dp - panelMaxHeight - 8.dp).coerceAtLeast(8.dp)
            SuggestionPanel(current, selectedIndex, { item ->
                model.applyCompletion(item, current.replaceStart, current.replaceEnd)
                dismissedForText = null
                suggestion = null
                runCatching { requester.requestFocus() }
            }, Modifier.offset(x = x, y = y))
        }
    }
}

private fun handleEditorKey(model: WorkspaceModel, event: KeyEvent): Boolean = when {
    event.key == Key.Tab -> { model.insert("\t"); true }
    event.isCtrlPressed && event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_S -> { model.save(); true }
    event.isCtrlPressed && event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_Z -> { model.undo(); true }
    event.isCtrlPressed && event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_Y -> { model.redo(); true }
    else -> false
}

private fun lineAndColumn(text: String, offset: Int): Pair<Int, Int> {
    val safe = offset.coerceIn(0, text.length)
    var line = 0
    var lastBreak = -1
    var index = 0
    while (index < safe) {
        if (text[index] == '\n') { line++; lastBreak = index }
        index++
    }
    return line to (safe - lastBreak - 1)
}

@Composable
private fun SuggestionPanel(
    result: CompletionResult,
    selected: Int,
    onSelect: (CompletionItem) -> Unit,
    modifier: Modifier
) {
    val listState = rememberLazyListState()
    LaunchedEffect(selected) {
        if (selected in result.items.indices) listState.animateScrollToItem(selected)
    }
    Surface(modifier = modifier.width(300.dp), shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 8.dp, shadowElevation = 12.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("SUGGESTIONS", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(result.language.label, style = MaterialTheme.typography.labelSmall, color = EditorAccent)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LazyColumn(state = listState, modifier = Modifier.heightIn(max = 190.dp)) {
                itemsIndexed(result.items) { index, item ->
                    val active = index == selected
                    Row(Modifier.fillMaxWidth()
                        .background(if (active) EditorAccent.copy(alpha = 0.22f) else Color.Transparent)
                        .clickable { onSelect(item) }.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        CompletionBadge(item.kind)
                        Spacer(Modifier.width(8.dp))
                        Text(item.label, color = if (active) Color.White else MaterialTheme.colorScheme.onSurface,
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(6.dp))
                        Text(item.detail, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun CompletionBadge(kind: CompletionKind) {
    val (icon, color) = when (kind) {
        CompletionKind.Keyword -> ToolIcon.CodeBrackets to Color(0xFF9C8CFF)
        CompletionKind.Property -> ToolIcon.Edit to Color(0xFF42A5F5)
        CompletionKind.Tag -> ToolIcon.CodeBrackets to Color(0xFFE44D26)
        CompletionKind.Snippet -> ToolIcon.File to Color(0xFF89D185)
        CompletionKind.Function -> ToolIcon.Run to Color(0xFFF0DB4F)
        CompletionKind.Value -> ToolIcon.Star to Color(0xFF4DD0E1)
        CompletionKind.Type -> ToolIcon.File to Color(0xFF4DD0E1)
        CompletionKind.Variable -> ToolIcon.File to Color(0xFFB3B3B3)
    }
    Box(Modifier.size(18.dp).clip(RoundedCornerShape(5.dp)).background(color.copy(alpha = 0.2f)),
        contentAlignment = Alignment.Center) {
        ToolGlyph(icon, color, Modifier.size(11.dp))
    }
}

@Composable
private fun FindBar(
    model: WorkspaceModel,
    scrollY: ScrollState,
    lineHeightPx: Float,
    scope: CoroutineScope,
    modifier: Modifier
) {
    var query by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var showReplace by remember { mutableStateOf(false) }

    fun reveal(offset: Int) {
        val doc = model.activeDocument ?: return
        val line = doc.value.text.substring(0, offset.coerceIn(0, doc.value.text.length)).count { it == '\n' }
        scope.launch { scrollY.animateScrollTo((line * lineHeightPx).toInt()) }
    }

    fun find(forward: Boolean) {
        val doc = model.activeDocument ?: return
        if (query.isEmpty()) return
        val text = doc.value.text
        val selection = doc.value.selection
        val found = if (forward) {
            text.indexOf(query, selection.max, ignoreCase = true).let { if (it < 0) text.indexOf(query, ignoreCase = true) else it }
        } else {
            text.lastIndexOf(query, (selection.min - 1).coerceAtLeast(0), ignoreCase = true)
                .let { if (it < 0) text.lastIndexOf(query, ignoreCase = true) else it }
        }
        if (found >= 0) {
            doc.value = doc.value.copy(selection = TextRange(found, found + query.length))
            reveal(found)
        } else model.message = "No matches for \"$query\""
    }

    Surface(modifier.fillMaxWidth().padding(8.dp), shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 4.dp, shadowElevation = 8.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it }, modifier = Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("Find in file") },
                    leadingIcon = { ToolGlyph(ToolIcon.Search, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(16.dp)) },
                    textStyle = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(4.dp))
                SmallIcon(ToolIcon.Chevron, "Previous match", 180f) { find(false) }
                SmallIcon(ToolIcon.Chevron, "Next match", 0f) { find(true) }
                SmallIcon(ToolIcon.Edit, "Toggle replace", 0f) { showReplace = !showReplace }
                SmallIcon(ToolIcon.Close, "Close find", 0f) { model.findOpen = false }
            }
            if (showReplace) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(replacement, { replacement = it }, modifier = Modifier.weight(1f),
                        singleLine = true, placeholder = { Text("Replace with") },
                        textStyle = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { model.replaceInFile(query, replacement, false) }) { Text("Replace") }
                    TextButton(onClick = { model.replaceInFile(query, replacement, true) }) { Text("All") }
                }
            }
        }
    }
}

@Composable
private fun SmallIcon(icon: ToolIcon, description: String, rotation: Float, onClick: () -> Unit) {
    Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).clickable(onClick = onClick)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        ToolGlyph(icon, MaterialTheme.colorScheme.onSurfaceVariant,
            Modifier.size(16.dp).then(if (rotation != 0f) Modifier.rotate(rotation) else Modifier))
    }
}

// ---------------------------------------------------------------------------------------------
// Syntax highlighting
// ---------------------------------------------------------------------------------------------

private class SourceHighlighting(private val path: String, private val colors: CodeColors) : VisualTransformation {
    private val language = Languages.of(path)
    private val regex = Languages.highlightRegex(language)

    override fun filter(text: AnnotatedString): TransformedText {
        val builder = AnnotatedString.Builder(text)
        for (match in regex.findAll(text)) {
            val token = match.value
            val color = when {
                token.startsWith("//") || token.startsWith("/*") || token.startsWith("<!--") -> colors.comment
                token.startsWith("#") && Languages.hashComments(language) -> colors.comment
                token.startsWith("\"") || token.startsWith("'") || token.startsWith("`") -> colors.string
                token.startsWith("<") -> colors.tag
                token.startsWith("#") -> colors.number
                token.firstOrNull()?.isDigit() == true -> colors.number
                else -> colors.keyword
            }
            builder.addStyle(SpanStyle(color = color), match.range.first, match.range.last + 1)
        }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}
