package dev.moavia.codriod.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val Panel = Color(0xFF252526)
private val Line = Color(0xFF3C3C3C)
private val Dim = Color(0xFF9D9D9D)
private val Txt = Color(0xFFD4D4D4)
private val TagC = Color(0xFF569CD6)
private val AttrC = Color(0xFF9CDCFE)
private val StrC = Color(0xFFCE9178)
private val Ok = Color(0xFF89D185)
private val Bad = Color(0xFFF48771)
private val Warn = Color(0xFFE2C08D)
private val Link = Color(0xFF3794FF)
private val SelBg = Color(0xFF04395E)
private val Mono = FontFamily.Monospace

private val DEV_TABS = listOf("Elements", "Console", "Network", "Sources", "Application", "Performance")

private fun parseJson(raw: String?): Any? = runCatching {
    when {
        raw == null || raw == "null" -> null
        raw.trimStart().startsWith("{") -> JSONObject(raw)
        raw.trimStart().startsWith("[") -> JSONArray(raw)
        else -> raw
    }
}.getOrNull()

private fun bytes(n: Long): String = when {
    n <= 0 -> "0 B"
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> String.format("%.1f kB", n / 1024.0)
    else -> String.format("%.1f MB", n / 1048576.0)
}

private fun ms(v: Double) = if (v <= 0) "—" else if (v < 1000) "${v.toInt()} ms" else String.format("%.2f s", v / 1000)

/** Chrome-style developer tools in a drawer that can be dragged up and down. */
@Composable
fun DevToolsPanel(model: WorkspaceModel, browser: BrowserController, tab: BrowserTab) {
    val density = LocalDensity.current
    val screen = LocalConfiguration.current.screenHeightDp
    var height by rememberSaveable { mutableFloatStateOf(380f) }
    val errors = tab.console.count { it.level == LogLevel.Error }
    Column(Modifier.fillMaxWidth().height(height.dp).background(Panel)) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(Color(0xFF007ACC)))
        Box(Modifier.fillMaxWidth().height(14.dp).pointerInput(Unit) {
            detectVerticalDragGestures { _, drag -> height = (height - drag / density.density).coerceIn(150f, screen * 0.8f) }
        }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(36.dp, 4.dp).background(Color(0xFF555555), RoundedCornerShape(2.dp)))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                DEV_TABS.forEach { name ->
                    val on = browser.devTab == name
                    Column(Modifier.width(IntrinsicSize.Max).clickable { browser.devTab = name }.padding(horizontal = 9.dp)) {
                        Row(Modifier.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(name, fontSize = 11.5.sp, color = if (on) Color.White else Dim)
                            if (name == "Console" && errors > 0) Text(" $errors ", fontSize = 9.5.sp, color = Color(0xFFF4A8A0),
                                modifier = Modifier.padding(start = 4.dp).background(Color(0xFF5A1D1D), RoundedCornerShape(8.dp)))
                        }
                        Box(Modifier.fillMaxWidth().height(2.dp).background(if (on) Link else Color.Transparent))
                    }
                }
            }
            if (browser.devTab == "Elements") Box(Modifier.size(30.dp).clip(CircleShape)
                .background(if (browser.inspecting) SelBg else Color.Transparent)
                .clickable { browser.setInspecting(tab, !browser.inspecting) }, contentAlignment = Alignment.Center) {
                ToolGlyph(ToolIcon.Inspect, if (browser.inspecting) Color(0xFF9FD0FF) else Txt, Modifier.size(16.dp))
            }
            Box(Modifier.size(30.dp).clickable { browser.devOpen = false; browser.setInspecting(tab, false); browser.call(tab, "highlight", null) },
                contentAlignment = Alignment.Center) { Text("✕", fontSize = 12.sp, color = Dim) }
        }
        HorizontalDivider(color = Line)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (browser.devTab) {
                "Elements" -> ElementsTab(browser, tab)
                "Console" -> ConsoleTab(model, browser, tab)
                "Network" -> NetworkTab(browser, tab)
                "Sources" -> SourcesTab(model, browser, tab)
                "Application" -> ApplicationTab(browser, tab)
                "Performance" -> PerformanceTab(browser, tab)
            }
        }
    }
}

@Composable
private fun Chips(options: List<String>, selected: String, onSelect: (String) -> Unit, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEach { o ->
                Text(o, fontSize = 10.5.sp, color = if (o == selected) Color(0xFF9FD0FF) else Dim,
                    modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(if (o == selected) Color(0xFF0B3A5E) else Color.Transparent)
                        .clickable { onSelect(o) }.padding(horizontal = 8.dp, vertical = 3.dp))
            }
        }
        trailing()
    }
}

@Composable
private fun SmallButton(text: String, onClick: () -> Unit) {
    Text(text, fontSize = 10.5.sp, color = Txt, modifier = Modifier.clip(RoundedCornerShape(9.dp)).border(1.dp, Line, RoundedCornerShape(9.dp))
        .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 3.dp))
}

// ---- Elements ----------------------------------------------------------------------------------

private data class DomNode(val path: String, val tag: String, val attrs: List<Pair<String, String>>, val text: String, val count: Int)

private fun JSONObject.toNode(): DomNode {
    val a = optJSONArray("attrs") ?: JSONArray()
    return DomNode(optString("path"), optString("tag"), (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getString(0) to p.getString(1) } },
        optString("text"), optInt("count"))
}

private fun JSONArray?.pairs(): List<Pair<String, String>> = if (this == null) emptyList() else
    (0 until length()).mapNotNull { i -> optJSONArray(i)?.let { it.optString(0) to it.optString(1) } }

@Composable
private fun ElementsTab(browser: BrowserController, tab: BrowserTab) {
    val children = remember(tab.id, tab.url) { mutableStateMapOf<String, List<DomNode>>() }
    val expanded = remember(tab.id, tab.url) { mutableStateListOf("") }
    var root by remember(tab.id, tab.url) { mutableStateOf<DomNode?>(null) }
    var details by remember(tab.id) { mutableStateOf<JSONObject?>(null) }
    var pane by remember { mutableStateOf("Styles") }
    var edit by remember { mutableStateOf<Pair<String, String>?>(null) }

    fun load(path: String) = browser.call(tab, "children", path) { raw ->
        (parseJson(raw) as? JSONArray)?.let { a -> children[path] = (0 until a.length()).map { a.getJSONObject(it).toNode() } }
    }
    fun loadDetails() = browser.selectedNode?.let { path ->
        browser.call(tab, "details", path) { details = parseJson(it) as? JSONObject }
    }
    LaunchedEffect(tab.id, tab.url, tab.progress) {
        if (tab.progress >= 100) {
            browser.call(tab, "root") { raw -> (parseJson(raw) as? JSONObject)?.let { root = it.toNode() } }
            expanded.toList().forEach { load(it) }
        }
    }
    // A tap with the inspect tool selects a node deep in the tree: open all of its ancestors.
    LaunchedEffect(browser.selectionRevision) {
        val path = browser.selectedNode ?: return@LaunchedEffect
        val parts = path.split('/').filter { it.isNotEmpty() }
        for (i in 0 until parts.size) {
            val ancestor = parts.take(i).joinToString("/")
            if (ancestor !in expanded) expanded += ancestor
            load(ancestor)
        }
        loadDetails()
    }
    val rows = buildList {
        root?.let { r ->
            add(0 to r)
            fun walk(path: String, depth: Int) {
                if (path !in expanded) return
                children[path].orEmpty().forEach { c -> add(depth to c); walk(c.path, depth + 1) }
            }
            walk("", 1)
        }
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f).horizontalScroll(rememberScrollState())) {
            if (rows.isEmpty()) item { Text("Loading DOM…", Modifier.padding(10.dp), color = Dim, fontSize = 11.sp) }
            items(rows, key = { it.second.path + "#" + it.first }) { (depth, node) ->
                val open = node.path in expanded
                val selected = browser.selectedNode == node.path
                Row(Modifier.widthIn(min = 400.dp).background(if (selected) SelBg else Color.Transparent)
                    .clickable { browser.selectNode(tab, node.path) }.padding(start = (6 + depth * 12).dp, end = 8.dp, top = 1.dp, bottom = 1.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (node.count > 0) (if (open) "▾" else "▸") else " ", color = Dim, fontSize = 11.sp,
                        modifier = Modifier.width(14.dp).clickable(enabled = node.count > 0) {
                            if (open) expanded.remove(node.path) else { expanded += node.path; load(node.path) }
                        })
                    Text(buildAnnotatedString {
                        withStyle(SpanStyle(color = TagC)) { append("<${node.tag}") }
                        node.attrs.forEach { (k, v) ->
                            append(" "); withStyle(SpanStyle(color = AttrC)) { append(k) }
                            if (v.isNotEmpty()) { append("="); withStyle(SpanStyle(color = StrC)) { append("\"${v.take(60)}\"") } }
                        }
                        withStyle(SpanStyle(color = TagC)) { append(">") }
                        if (node.count == 0 && node.text.isNotEmpty()) { withStyle(SpanStyle(color = Txt)) { append(node.text) } }
                        if (node.count == 0 || !open) withStyle(SpanStyle(color = TagC)) { append(if (node.count > 0) "…</${node.tag}>" else "</${node.tag}>") }
                        if (selected) withStyle(SpanStyle(color = Color(0xFF6A9955))) { append("  == \$0") }
                    }, fontFamily = Mono, fontSize = 10.5.sp, maxLines = 1, softWrap = false)
                }
            }
        }
        HorizontalDivider(color = Line)
        Chips(listOf("Styles", "Computed", "Layout", "Attributes"), pane, { pane = it }) {
            if (browser.selectedNode != null) SmallButton("+ style") { edit = "" to "" }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            val d = details
            if (browser.selectedNode == null || d == null)
                Text("Tap ⬉ then tap anything on the page, or pick an element above.", Modifier.padding(10.dp), color = Dim, fontSize = 11.sp)
            else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp)) {
                when (pane) {
                    "Styles" -> {
                        StyleRule("element.style", "inline", d.optJSONArray("inline").pairs()) { edit = it }
                        val rules = d.optJSONArray("rules") ?: JSONArray()
                        for (i in 0 until rules.length()) rules.getJSONObject(i).let { r ->
                            StyleRule(r.optString("selector"), r.optString("source"), r.optJSONArray("props").pairs()) { edit = it }
                        }
                    }
                    "Computed" -> d.optJSONArray("computed").pairs().forEach { (k, v) ->
                        Text(buildAnnotatedString { withStyle(SpanStyle(color = AttrC)) { append(k) }; append(": "); withStyle(SpanStyle(color = StrC)) { append(v) } },
                            fontFamily = Mono, fontSize = 10.5.sp)
                    }
                    "Layout" -> BoxModel(d.optJSONObject("box") ?: JSONObject())
                    "Attributes" -> {
                        d.optJSONArray("attrs").pairs().forEach { (k, v) ->
                            Text(buildAnnotatedString { withStyle(SpanStyle(color = AttrC)) { append(k) }; append(" = "); withStyle(SpanStyle(color = StrC)) { append(v) } },
                                fontFamily = Mono, fontSize = 10.5.sp)
                        }
                        Spacer(Modifier.height(6.dp))
                        SelectionContainer { Text(d.optString("html"), fontFamily = Mono, fontSize = 10.sp, color = Txt) }
                    }
                }
            }
        }
    }
    edit?.let { (prop, value) ->
        var name by remember(prop) { mutableStateOf(prop) }
        var text by remember(prop) { mutableStateOf(value) }
        AlertDialog(onDismissRequest = { edit = null }, title = { Text(if (prop.isEmpty()) "Add style" else "Edit $prop") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.trim() }, singleLine = true, label = { Text("Property") }, enabled = prop.isEmpty())
                OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Value (empty removes it)") })
                Text("Applied to the element's inline style on this page.", fontSize = 11.sp, color = Dim)
            } },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
                browser.selectedNode?.let { path -> browser.call(tab, "setStyle", path, name, text) { loadDetails() } }
                edit = null
            }) { Text("Apply") } },
            dismissButton = { TextButton(onClick = { edit = null }) { Text("Cancel") } })
    }
}

@Composable
private fun StyleRule(selector: String, source: String, props: List<Pair<String, String>>, onEdit: (Pair<String, String>) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row {
            Text(selector, color = Color(0xFFD7BA7D), fontFamily = Mono, fontSize = 10.5.sp, modifier = Modifier.weight(1f))
            Text(source, color = Dim, fontFamily = Mono, fontSize = 10.sp)
        }
        if (props.isEmpty()) Text("  { }", color = Dim, fontFamily = Mono, fontSize = 10.5.sp)
        props.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth().clickable { onEdit(k to v) }.padding(start = 14.dp, top = 1.dp, bottom = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(k, color = AttrC, fontFamily = Mono, fontSize = 10.5.sp)
                Text(": ", color = Txt, fontFamily = Mono, fontSize = 10.5.sp)
                colorSwatch(v)?.let { Box(Modifier.padding(end = 4.dp).size(9.dp).background(it).border(1.dp, Color(0xFF888888))) }
                Text("$v;", color = StrC, fontFamily = Mono, fontSize = 10.5.sp)
            }
        }
    }
}

private fun colorSwatch(value: String): Color? {
    val v = value.trim()
    Regex("^#([0-9a-fA-F]{6})$").matchEntire(v)?.let { return Color(0xFF000000 or it.groupValues[1].toLong(16)) }
    Regex("^#([0-9a-fA-F]{3})$").matchEntire(v)?.let { m ->
        val h = m.groupValues[1].map { "$it$it" }.joinToString(""); return Color(0xFF000000 or h.toLong(16))
    }
    Regex("^rgba?\\((\\d+),\\s*(\\d+),\\s*(\\d+)").find(v)?.let { m ->
        return Color(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
    }
    return null
}

@Composable
private fun BoxModel(box: JSONObject) {
    fun side(name: String, i: Int) = box.optJSONArray(name)?.optDouble(i)?.toInt() ?: 0
    @Composable fun Layer(label: String, name: String, color: Color, border: Color, content: @Composable () -> Unit) {
        Column(Modifier.border(1.dp, border).background(color).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth()) { Text(label, fontSize = 9.sp, color = border); Spacer(Modifier.weight(1f)); Text("${side(name, 0)}", fontSize = 9.sp, color = Txt); Spacer(Modifier.weight(1.2f)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${side(name, 3)}", fontSize = 9.sp, color = Txt); Spacer(Modifier.width(6.dp)); content(); Spacer(Modifier.width(6.dp)); Text("${side(name, 1)}", fontSize = 9.sp, color = Txt)
            }
            Text("${side(name, 2)}", fontSize = 9.sp, color = Txt)
        }
    }
    Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
        Layer("margin", "margin", Color(0x22B38B5D), Color(0xFFD7BA7D)) {
            Layer("border", "border", Color(0x22E2C08D), Color(0xFFE2C08D)) {
                Layer("padding", "padding", Color(0x225D8BB3), Color(0xFF9FD0FF)) {
                    Text("${box.optDouble("width").toInt()} × ${box.optDouble("height").toInt()}", fontSize = 10.sp, color = Color(0xFFB5E0A0),
                        fontFamily = Mono, modifier = Modifier.border(1.dp, Color(0xFF6A9955)).background(Color(0x226A9955)).padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
        }
    }
}

// ---- Console -----------------------------------------------------------------------------------

@Composable
private fun ConsoleTab(model: WorkspaceModel, browser: BrowserController, tab: BrowserTab) {
    var level by remember { mutableStateOf("All levels") }
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    val shown = tab.console.filter {
        when (level) {
            "Errors" -> it.level == LogLevel.Error
            "Warnings" -> it.level == LogLevel.Warn
            "Info" -> it.level == LogLevel.Info || it.level == LogLevel.Log
            else -> true
        }
    }
    LaunchedEffect(shown.size) { if (shown.isNotEmpty()) list.scrollToItem(shown.size - 1) }
    Column(Modifier.fillMaxSize()) {
        Chips(listOf("All levels", "Errors", "Warnings", "Info"), level, { level = it }) { SmallButton("Clear") { tab.console.clear() } }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list) {
            if (shown.isEmpty()) item { Text("Console messages from the page appear here.", Modifier.padding(10.dp), color = Dim, fontSize = 11.sp) }
            itemsIndexed(shown) { _, entry ->
                val (bg, fg, mark) = when (entry.level) {
                    LogLevel.Error -> Triple(Color(0xFF3B1F1F), Color(0xFFF4A8A0), "✕ ")
                    LogLevel.Warn -> Triple(Color(0xFF3A321C), Warn, "⚠ ")
                    LogLevel.Input -> Triple(Color.Transparent, Txt, "> ")
                    LogLevel.Result -> Triple(Color.Transparent, StrC, "< ")
                    else -> Triple(Color.Transparent, Txt, "")
                }
                Row(Modifier.fillMaxWidth().background(bg).padding(horizontal = 8.dp, vertical = 3.dp)) {
                    SelectionContainer(Modifier.weight(1f)) { Text(mark + entry.text, color = fg, fontFamily = Mono, fontSize = 10.5.sp) }
                    if (entry.source.isNotBlank() && entry.level != LogLevel.Input && entry.level != LogLevel.Result) {
                        val name = entry.source.substringAfterLast('/').substringBefore('?').ifBlank { entry.source } + if (entry.line > 0) ":${entry.line}" else ""
                        Text(name, color = Link, fontFamily = Mono, fontSize = 10.5.sp, modifier = Modifier.padding(start = 6.dp).clickable {
                            if (!browser.openSourceInEditor(entry.source, entry.line)) { browser.devTab = "Sources"; pendingSource = entry.source }
                        })
                    }
                }
                HorizontalDivider(color = Color(0xFF2C2C2C))
            }
        }
        HorizontalDivider(color = Line)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("❯ ", color = Link, fontFamily = Mono, fontSize = 12.sp)
            Box(Modifier.weight(1f)) {
                if (input.isEmpty()) Text("Run JavaScript, e.g. document.title", color = Dim, fontFamily = Mono, fontSize = 11.sp)
                BasicTextField(input, { input = it }, singleLine = true,
                    textStyle = LocalTextStyle.current.copy(color = Txt, fontFamily = Mono, fontSize = 11.sp), cursorBrush = SolidColor(Link),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onSend = { browser.runConsole(tab, input); input = "" }), modifier = Modifier.fillMaxWidth())
            }
            if (input.isNotBlank()) SmallButton("Run") { browser.runConsole(tab, input); input = "" }
        }
    }
}

/** Source requested from the Console, opened by the Sources tab. */
private var pendingSource by mutableStateOf<String?>(null)

// ---- Network -----------------------------------------------------------------------------------

private fun netType(type: String, url: String): String = when {
    type == "document" || type == "navigation" -> "Doc"
    type == "fetch" -> "Fetch"
    type == "xmlhttprequest" || type == "xhr" -> "XHR"
    type == "script" -> "JS"
    type == "css" || type == "link" && url.contains(".css") -> "CSS"
    type == "img" || Regex("\\.(png|jpe?g|gif|webp|svg|ico)(\\?|$)").containsMatchIn(url) -> "Img"
    Regex("\\.(woff2?|ttf|otf)(\\?|$)").containsMatchIn(url) -> "Font"
    type == "link" -> "CSS"
    else -> type.replaceFirstChar { it.uppercase() }
}

@Composable
private fun NetworkTab(browser: BrowserController, tab: BrowserTab) {
    var entries by remember(tab.id) { mutableStateOf<List<NetEntry>>(emptyList()) }
    var perf by remember(tab.id) { mutableStateOf<JSONObject?>(null) }
    var filter by remember { mutableStateOf("All") }
    var selected by remember(tab.id, tab.url) { mutableStateOf<NetEntry?>(null) }
    var detail by remember { mutableStateOf("Headers") }
    var clearedAt by remember(tab.id, tab.url) { mutableIntStateOf(0) }
    LaunchedEffect(tab.id, tab.url) {
        while (true) {
            browser.call(tab, "resources") { raw ->
                val a = parseJson(raw) as? JSONArray ?: return@call
                entries = (0 until a.length()).map { i -> a.getJSONObject(i).let {
                    NetEntry(it.optString("url"), "GET", it.optInt("status"), it.optString("type"), it.optLong("size"),
                        it.optDouble("start"), it.optDouble("duration"))
                } }
            }
            browser.call(tab, "perf") { perf = parseJson(it) as? JSONObject }
            delay(1500)
        }
    }
    // Resource Timing lists every request; fetch/XHR captures add method, status and body.
    val merged = entries.drop(clearedAt).map { e ->
        val cap = tab.captured.lastOrNull { it.url == e.url }
        if (cap == null) e else e.copy(method = cap.method, status = if (cap.status != 0) cap.status else e.status,
            type = cap.type, body = cap.body, mime = cap.mime, error = cap.error)
    } + tab.captured.filter { c -> c.status == 0 && entries.none { it.url == c.url } }
    val shown = merged.filter { filter == "All" || netType(it.type, it.url) == filter || (filter == "Fetch/XHR" && netType(it.type, it.url) in listOf("Fetch", "XHR")) }
    val end = merged.maxOfOrNull { it.start + it.duration }?.takeIf { it > 0 } ?: 1.0
    Column(Modifier.fillMaxSize()) {
        Chips(listOf("All", "Fetch/XHR", "Doc", "JS", "CSS", "Img", "Font"), filter, { filter = it }) {
            SmallButton(if (browser.disableCache) "✓ No cache" else "Disable cache") { browser.updateDisableCache(!browser.disableCache) }
            Spacer(Modifier.width(4.dp))
            SmallButton("Clear") { clearedAt = entries.size; tab.captured.clear(); selected = null }
        }
        Row(Modifier.fillMaxWidth().background(Color(0xFF2A2A2D)).padding(horizontal = 6.dp, vertical = 4.dp)) {
            listOf("Name" to 2.2f, "Status" to .8f, "Type" to 1f, "Size" to .9f, "Time" to 1.1f).forEach { (h, w) ->
                Text(h, Modifier.weight(w), fontSize = 10.5.sp, color = Dim)
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            if (shown.isEmpty()) item { Text("Reload the page to record its requests.", Modifier.padding(10.dp), color = Dim, fontSize = 11.sp) }
            items(shown) { e ->
                val failed = e.status >= 400 || e.error != null
                Row(Modifier.fillMaxWidth().background(if (selected == e) SelBg else Color.Transparent).clickable { selected = e }
                    .padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    val name = e.url.substringBefore('?').trimEnd('/').substringAfterLast('/').ifBlank { e.url.substringAfter("://").substringBefore('/') } +
                        if (e.url.contains('?')) "?…" else ""
                    Text(name, Modifier.weight(2.2f), fontSize = 10.5.sp, fontFamily = Mono, color = if (failed) Bad else Txt, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (e.status == 0) (if (e.error != null) "failed" else "—") else e.status.toString(), Modifier.weight(.8f), fontSize = 10.5.sp,
                        fontFamily = Mono, color = if (failed) Bad else if (e.status in 200..399) Ok else Dim)
                    Text(netType(e.type, e.url).lowercase(), Modifier.weight(1f), fontSize = 10.5.sp, fontFamily = Mono, color = Txt, maxLines = 1)
                    Text(bytes(e.size), Modifier.weight(.9f), fontSize = 10.5.sp, fontFamily = Mono, color = Txt, maxLines = 1)
                    Box(Modifier.weight(1.1f).height(8.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Spacer(Modifier.weight(((e.start / end).toFloat()).coerceIn(0.001f, .95f)))
                            Box(Modifier.weight(((e.duration / end).toFloat()).coerceIn(0.03f, 1f)).fillMaxHeight()
                                .background(if (failed) Bad else when (netType(e.type, e.url)) { "JS" -> Warn; "CSS" -> Color(0xFFC586C0); "Img" -> Ok; "Fetch", "XHR" -> Color(0xFF4EC9B0); else -> Link }, RoundedCornerShape(2.dp)))
                            Spacer(Modifier.weight((1f - ((e.start + e.duration) / end).toFloat()).coerceIn(0.001f, 1f)))
                        }
                    }
                }
            }
        }
        selected?.let { e ->
            HorizontalDivider(color = Line)
            Chips(listOf("Headers", "Preview", "Response", "Timing"), detail, { detail = it }) { SmallButton("✕") { selected = null } }
            Box(Modifier.fillMaxWidth().heightIn(max = 150.dp).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                SelectionContainer {
                    Text(when (detail) {
                        "Headers" -> "URL: ${e.url}\nMethod: ${e.method}\nStatus: ${if (e.status == 0) "unknown (cross-origin)" else e.status}\n" +
                            "Type: ${netType(e.type, e.url)}${if (e.mime.isNotBlank()) "\nContent-Type: ${e.mime}" else ""}\nSize: ${bytes(e.size)}" +
                            (e.error?.let { "\nError: $it" } ?: "")
                        "Preview" -> e.body?.let { body -> runCatching { JSONObject(body).toString(2) }.recoverCatching { JSONArray(body).toString(2) }.getOrDefault(body) }
                            ?: "Response bodies are shown for fetch and XHR requests. Use Sources to view scripts, styles and pages."
                        "Response" -> e.body ?: "No captured body for this request."
                        else -> "Started at ${ms(e.start)}\nDuration ${ms(e.duration)}"
                    }, fontFamily = Mono, fontSize = 10.5.sp, color = Txt)
                }
            }
        }
        HorizontalDivider(color = Line)
        val p = perf
        Text("${merged.size} requests · ${bytes(p?.optLong("bytes") ?: merged.sumOf { it.size })} transferred" +
            (p?.let { " · Finish: ${ms(end)} · DOMContentLoaded: ${ms(it.optDouble("dcl"))}" } ?: ""),
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 10.5.sp, color = Dim, maxLines = 2)
    }
}

// ---- Sources -----------------------------------------------------------------------------------

@Composable
private fun SourcesTab(model: WorkspaceModel, browser: BrowserController, tab: BrowserTab) {
    var files by remember(tab.id, tab.url) { mutableStateOf<List<String>>(emptyList()) }
    var open by remember(tab.id, tab.url) { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tab.id, tab.url, tab.progress) {
        if (tab.progress >= 100) browser.call(tab, "sources") { raw ->
            (parseJson(raw) as? JSONArray)?.let { a -> files = (0 until a.length()).map { a.getString(it) } }
        }
    }
    LaunchedEffect(pendingSource) { pendingSource?.let { open = it; pendingSource = null } }
    LaunchedEffect(open) {
        val url = open ?: return@LaunchedEffect
        text = null; error = null
        runCatching { withContext(Dispatchers.IO) { browser.fetchSource(url) } }
            .onSuccess { text = it }.onFailure { error = it.message ?: "Could not load source" }
    }
    Column(Modifier.fillMaxSize()) {
        val current = open
        if (current == null) LazyColumn(Modifier.fillMaxSize()) {
            if (files.isEmpty()) item { Text("Loading page sources…", Modifier.padding(10.dp), color = Dim, fontSize = 11.sp) }
            items(files) { url ->
                val ext = url.substringBefore('?').substringAfterLast('.', "").lowercase()
                Row(Modifier.fillMaxWidth().clickable { open = url }.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(when (ext) { "js", "mjs" -> "JS"; "css" -> "CSS"; else -> "</>" }, fontSize = 9.5.sp, fontWeight = FontWeight.Bold,
                        color = when (ext) { "js", "mjs" -> Warn; "css" -> Color(0xFFC586C0); else -> TagC }, modifier = Modifier.width(30.dp))
                    Column(Modifier.weight(1f)) {
                        Text(url.substringBefore('?').trimEnd('/').substringAfterLast('/').ifBlank { "(index)" }, fontSize = 11.5.sp, color = Txt, maxLines = 1)
                        Text(url, fontSize = 10.sp, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                HorizontalDivider(color = Color(0xFF2C2C2C))
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallButton("‹ Files") { open = null }
                Text(current.substringBefore('?').substringAfterLast('/'), Modifier.weight(1f).padding(horizontal = 8.dp), fontSize = 11.sp,
                    color = Txt, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (current.startsWith("$PROJECT_ORIGIN/")) SmallButton("Open in editor") { browser.openSourceInEditor(current, 1) }
            }
            HorizontalDivider(color = Line)
            when {
                error != null -> Text(error.orEmpty(), Modifier.padding(10.dp), color = Bad, fontSize = 11.sp)
                text == null -> Text("Loading…", Modifier.padding(10.dp), color = Dim, fontSize = 11.sp)
                else -> {
                    val lines = remember(text) { text!!.split('\n').take(5000) }
                    LazyColumn(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                        itemsIndexed(lines) { i, l ->
                            Row(Modifier.widthIn(min = 400.dp)) {
                                Text("${i + 1}", Modifier.width(40.dp).padding(end = 8.dp), color = Color(0xFF6E7681), fontFamily = Mono, fontSize = 10.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.End)
                                Text(l, color = Txt, fontFamily = Mono, fontSize = 10.5.sp, softWrap = false)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---- Application -------------------------------------------------------------------------------

@Composable
private fun ApplicationTab(browser: BrowserController, tab: BrowserTab) {
    var section by remember { mutableStateOf("Local storage") }
    var items by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var edit by remember { mutableStateOf<Pair<String, String>?>(null) }
    val kind = if (section == "Session storage") "session" else "local"
    LaunchedEffect(section, tab.url, tab.progress, refresh) {
        if (section == "Cookies") items = browser.cookies(tab.url)
        else browser.call(tab, "storage", kind) { items = (parseJson(it) as? JSONArray).pairs() }
    }
    Column(Modifier.fillMaxSize()) {
        Chips(listOf("Local storage", "Session storage", "Cookies"), section, { section = it }) {
            if (section != "Cookies") { SmallButton("+ Add") { edit = "" to "" }; Spacer(Modifier.width(4.dp)) }
            SmallButton("Clear all") {
                if (section == "Cookies") browser.clearCookies() else browser.call(tab, "clearStorage", kind)
                refresh++
            }
        }
        Text(android.net.Uri.parse(tab.url).let { "${it.scheme}://${it.authority}" }, Modifier.padding(horizontal = 10.dp), fontSize = 10.sp, color = Dim)
        Row(Modifier.fillMaxWidth().background(Color(0xFF2A2A2D)).padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text("Key", Modifier.weight(1f), fontSize = 10.5.sp, color = Dim); Text("Value", Modifier.weight(1.6f), fontSize = 10.5.sp, color = Dim)
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            if (items.isEmpty()) item { Text("Nothing stored for this site.", Modifier.padding(10.dp), color = Dim, fontSize = 11.sp) }
            items(items) { (k, v) ->
                Row(Modifier.fillMaxWidth().clickable(enabled = section != "Cookies") { edit = k to v }.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(k, Modifier.weight(1f), fontFamily = Mono, fontSize = 10.5.sp, color = AttrC, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(v, Modifier.weight(1.6f), fontFamily = Mono, fontSize = 10.5.sp, color = StrC, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("✕", Modifier.clickable {
                        if (section == "Cookies") browser.deleteCookie(tab.url, k) else browser.call(tab, "removeItem", kind, k)
                        refresh++
                    }.padding(horizontal = 6.dp), color = Dim, fontSize = 11.sp)
                }
                HorizontalDivider(color = Color(0xFF2C2C2C))
            }
        }
    }
    edit?.let { (key, value) ->
        var k by remember(key) { mutableStateOf(key) }
        var v by remember(key) { mutableStateOf(value) }
        AlertDialog(onDismissRequest = { edit = null }, title = { Text(if (key.isEmpty()) "Add item" else "Edit $key") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(k, { k = it }, singleLine = true, label = { Text("Key") }, enabled = key.isEmpty())
                OutlinedTextField(v, { v = it }, label = { Text("Value") }, maxLines = 6)
            } },
            confirmButton = { TextButton(enabled = k.isNotBlank(), onClick = { browser.call(tab, "setItem", kind, k, v) { refresh++ }; edit = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { edit = null }) { Text("Cancel") } })
    }
}

// ---- Performance -------------------------------------------------------------------------------

@Composable
private fun PerformanceTab(browser: BrowserController, tab: BrowserTab) {
    var perf by remember(tab.id, tab.url) { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(tab.id, tab.url, tab.progress) {
        if (tab.progress >= 100) { delay(400); browser.call(tab, "perf") { perf = parseJson(it) as? JSONObject } }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Page load", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            SmallButton("⟳ Reload and measure") { perf = null; browser.reload(tab) }
        }
        val p = perf
        if (p == null) Text("Measuring…", color = Dim, fontSize = 11.sp)
        else {
            val metrics = listOf("First paint" to ms(p.optDouble("fp")), "First contentful paint" to ms(p.optDouble("fcp")),
                "Time to first byte" to ms(p.optDouble("ttfb")), "DOMContentLoaded" to ms(p.optDouble("dcl")), "Load" to ms(p.optDouble("load")),
                "DNS + connect" to ms(p.optDouble("dns") + p.optDouble("connect")), "Requests" to p.optInt("requests").toString(),
                "Transferred" to bytes(p.optLong("bytes")), "DOM elements" to p.optInt("nodes").toString(),
                "JS heap" to if (p.optLong("heap") > 0) bytes(p.optLong("heap")) else "—")
            metrics.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (k, v) ->
                        Column(Modifier.weight(1f).background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp)).padding(10.dp)) {
                            Text(v, fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text(k, fontSize = 10.5.sp, color = Dim)
                        }
                    }
                }
            }
            val byType = p.optJSONObject("byType")
            if (byType != null && byType.length() > 0) {
                Text("By resource type", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                val total = byType.keys().asSequence().sumOf { byType.getJSONArray(it).optLong(1) }.coerceAtLeast(1)
                byType.keys().asSequence().toList().sortedByDescending { byType.getJSONArray(it).optLong(1) }.forEach { k ->
                    val a = byType.getJSONArray(k)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(k, Modifier.width(80.dp), fontSize = 10.5.sp, color = Txt, fontFamily = Mono)
                        Box(Modifier.weight(1f).height(8.dp).background(Color(0xFF1E1E1E), RoundedCornerShape(4.dp))) {
                            Box(Modifier.fillMaxWidth((a.optLong(1).toFloat() / total).coerceIn(0.02f, 1f)).fillMaxHeight().background(Link, RoundedCornerShape(4.dp)))
                        }
                        Text("  ${a.optInt(0)} · ${bytes(a.optLong(1))}", fontSize = 10.5.sp, color = Dim)
                    }
                }
            }
        }
    }
}
