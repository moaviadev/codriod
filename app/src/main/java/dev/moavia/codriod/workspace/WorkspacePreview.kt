package dev.moavia.codriod.workspace

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import java.net.URLDecoder

data class PreviewAsset(val mime: String, val openStream: () -> java.io.InputStream)

/** Serves the open project's files to the browser at [PROJECT_ORIGIN]. */
class PreviewFiles(private val store: ProjectStore, private val projectId: String) {
    fun resolve(encodedPath: String): PreviewAsset? = runCatching {
        val path = URLDecoder.decode(encodedPath.replace("+", "%2B"), "UTF-8").removePrefix("/")
        val safePath = if (path.isEmpty() || path.endsWith("/")) path + "index.html" else path
        val mime = when (safePath.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js", "mjs" -> "application/javascript"
            "json", "map" -> "application/json"
            "xml" -> "application/xml"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "ico" -> "image/x-icon"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "mp3" -> "audio/mpeg"
            "mp4" -> "video/mp4"
            "wasm" -> "application/wasm"
            "txt", "md" -> "text/plain"
            else -> "application/octet-stream"
        }
        val file = runCatching { store.resolve(projectId, safePath) }.getOrNull()
        if (file != null && file.isFile && file.length() <= 32 * 1024 * 1024) PreviewAsset(mime) { file.inputStream() }
        else {
            val content = store.read(projectId, safePath)
            PreviewAsset(mime) { ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)) }
        }
    }.getOrNull()
}

private val Bar = Color(0xFF2D2D30)
private val Side = Color(0xFF252526)
private val Field = Color(0xFF1E1E1E)
private val Muted = Color(0xFF9D9D9D)
private val Accent = Color(0xFF007ACC)
private val Live = Color(0xFF89D185)

private fun faviconColor(url: String): Color {
    if (url.startsWith(PROJECT_ORIGIN)) return Accent
    val host = Uri.parse(url).host ?: return Color(0xFF555555)
    val palette = listOf(0xFFE34C26, 0xFFC586C0, 0xFF4EC9B0, 0xFFE2C08D, 0xFF569CD6, 0xFF89D185, 0xFFF48771, 0xFF9CDCFE)
    return Color(palette[(host.hashCode() and 0x7fffffff) % palette.size])
}

/** Chrome-style browser: tabs, address bar, Mobile · Tablet · PC, and DevTools. */
@Composable
fun WorkspacePreview(model: WorkspaceModel, modifier: Modifier = Modifier) {
    val browser = model.browser
    browser.ensureTab()
    val tab = browser.active ?: return
    LaunchedEffect(model.previewRevision) { browser.reloadProjectTabs() }
    Column(modifier.fillMaxSize().background(Field)) {
        if (!browser.fullscreen) {
            TabStrip(browser)
            Omnibox(model, browser, tab)
            if (tab.progress in 1..99 && !tab.isNewTab)
                LinearProgressIndicator(progress = { tab.progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = Color(0xFF3794FF), trackColor = Bar)
            DeviceBar(browser)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tab.isNewTab) NewTabPage(model, browser, tab) else Stage(browser, tab)
            if (browser.fullscreen) SmallFloatingActionButton(onClick = { browser.fullscreen = false },
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp), containerColor = Color(0xCC000000), contentColor = Color.White) {
                ToolGlyph(ToolIcon.Close, Color.White)
            }
        }
        if (browser.devOpen && !browser.fullscreen && !tab.isNewTab) DevToolsPanel(model, browser, tab)
    }
}

// ---- tab strip ---------------------------------------------------------------------------------

@Composable
private fun TabStrip(browser: BrowserController) {
    var switcher by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().background(Side).padding(start = 6.dp, end = 6.dp, top = 5.dp), verticalAlignment = Alignment.Bottom) {
        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            items(browser.tabs, key = { it.id }) { item ->
                val selected = item.id == browser.activeId
                Row(Modifier.widthIn(max = 150.dp).height(30.dp)
                    .clip(RoundedCornerShape(topStart = 9.dp, topEnd = 9.dp))
                    .background(if (selected) Bar else Color.Transparent)
                    .clickable { browser.select(item) }.padding(start = 9.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).background(if (item.isNewTab) Color(0xFF555555) else faviconColor(item.url), RoundedCornerShape(3.dp)))
                    Spacer(Modifier.width(6.dp))
                    Text(item.title, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (selected) Color.White else Muted, modifier = Modifier.weight(1f, fill = false))
                    if (selected) Box(Modifier.size(22.dp).clickable { browser.close(item) }, contentAlignment = Alignment.Center) {
                        Text("✕", fontSize = 10.sp, color = Muted)
                    } else Spacer(Modifier.width(5.dp))
                }
            }
            item {
                Box(Modifier.size(30.dp).clickable { browser.newTab() }, contentAlignment = Alignment.Center) {
                    Text("+", fontSize = 18.sp, color = Muted)
                }
            }
        }
        Box(Modifier.padding(start = 4.dp, bottom = 5.dp).size(24.dp, 21.dp).border(1.6.dp, Muted, RoundedCornerShape(6.dp))
            .clickable { switcher = true }, contentAlignment = Alignment.Center) {
            Text(browser.tabs.size.toString(), fontSize = 11.sp, color = Color.White)
        }
    }
    if (switcher) AlertDialog(onDismissRequest = { switcher = false }, title = { Text("Tabs") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                browser.tabs.toList().forEach { item ->
                    Row(Modifier.fillMaxWidth().clickable { browser.select(item); switcher = false }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).background(faviconColor(item.url), RoundedCornerShape(3.dp)))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = if (item.id == browser.activeId) FontWeight.Bold else null)
                            Text(item.url.ifBlank { "New tab" }, fontSize = 11.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = { browser.close(item) }) { Text("Close") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { browser.newTab(); switcher = false }) { Text("New tab") } },
        dismissButton = { TextButton(onClick = { switcher = false }) { Text("Done") } })
}

// ---- address bar -------------------------------------------------------------------------------

@Composable
private fun Omnibox(model: WorkspaceModel, browser: BrowserController, tab: BrowserTab) {
    val focus = LocalFocusManager.current
    val context = LocalContext.current
    var editing by remember { mutableStateOf(false) }
    var value by remember(tab.id) { mutableStateOf(TextFieldValue(tab.url)) }
    var menu by remember { mutableStateOf(false) }
    var listDialog by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tab.url, editing) { if (!editing) value = TextFieldValue(tab.url) }
    Row(Modifier.fillMaxWidth().background(Bar).padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (tab.isNewTab) WorkspaceIconButton(ToolIcon.Home, "Home") { browser.goHome(tab) }
        else WorkspaceIconButton(ToolIcon.Back, "Back") { browser.back(tab) }
        Row(Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(18.dp)).background(Field).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            when {
                editing || tab.isNewTab -> ToolGlyph(ToolIcon.Search, Muted, Modifier.size(15.dp))
                tab.url.startsWith(PROJECT_ORIGIN) -> Box(Modifier.size(8.dp).background(Live, CircleShape))
                tab.url.startsWith("https://") -> ToolGlyph(ToolIcon.Lock, Live, Modifier.size(14.dp))
                else -> Text("ⓘ", fontSize = 12.sp, color = Muted)
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (!editing && !tab.isNewTab) Text(displayUrl(tab.url), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!editing && tab.isNewTab) Text("Search or type a web address", fontSize = 13.sp, color = Muted, maxLines = 1)
                BasicTextField(value, { value = it }, singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, color = if (editing) Color.White else Color.Transparent),
                    cursorBrush = SolidColor(Color(0xFF3794FF)),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { browser.navigate(tab, value.text); focus.clearFocus() }),
                    modifier = Modifier.fillMaxWidth().onFocusChanged {
                        editing = it.isFocused
                        if (it.isFocused) value = TextFieldValue(tab.url, TextRange(0, tab.url.length))
                    })
            }
            if (!tab.isNewTab && !editing) Box(Modifier.size(26.dp).clickable { browser.reload(tab) }, contentAlignment = Alignment.Center) {
                ToolGlyph(ToolIcon.Refresh, Color(0xFFD4D4D4), Modifier.size(17.dp))
            }
        }
        if (!tab.isNewTab) Box(Modifier.padding(start = 4.dp).size(32.dp).clip(CircleShape)
            .background(if (browser.devOpen) Color(0xFF0B3A5E) else Color.Transparent)
            .clickable { browser.devOpen = !browser.devOpen }, contentAlignment = Alignment.Center) {
            ToolGlyph(ToolIcon.CodeBrackets, if (browser.devOpen) Color(0xFF9FD0FF) else Color(0xFFD4D4D4), Modifier.size(18.dp))
        }
        Box {
            WorkspaceIconButton(ToolIcon.More, "Browser menu") { menu = true }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("New tab") }, { menu = false; browser.newTab() })
                if (!tab.isNewTab) {
                    DropdownMenuItem({ Text("Forward") }, { menu = false; browser.forward(tab) }, enabled = tab.canGoForward)
                    DropdownMenuItem({ Text(if (browser.isBookmarked(tab.url)) "Remove bookmark" else "Bookmark this page") },
                        { menu = false; browser.toggleBookmark(tab) })
                }
                DropdownMenuItem({ Text("Bookmarks") }, { menu = false; listDialog = "Bookmarks" })
                DropdownMenuItem({ Text("History") }, { menu = false; listDialog = "History" })
                DropdownMenuItem({ Text(if (browser.mode == DeviceMode.PC) "✓ Desktop site" else "Desktop site") },
                    { menu = false; browser.changeMode(if (browser.mode == DeviceMode.PC) DeviceMode.Mobile else DeviceMode.PC) })
                DropdownMenuItem({ Text(if (browser.disableCache) "✓ Disable cache" else "Disable cache") },
                    { menu = false; browser.updateDisableCache(!browser.disableCache) })
                if (!tab.isNewTab) {
                    DropdownMenuItem({ Text("Share") }, {
                        menu = false
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, tab.url), "Share link"))
                    })
                    if (!tab.url.startsWith(PROJECT_ORIGIN)) DropdownMenuItem({ Text("Open in another app") }, {
                        menu = false
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(tab.url))) }
                    })
                }
                DropdownMenuItem({ Text("Clear browsing data") }, { menu = false; listDialog = "Clear" })
                DropdownMenuItem({ Text("Close browser") }, { menu = false; model.previewOpen = false })
            }
        }
    }
    when (listDialog) {
        "Bookmarks", "History" -> PageListDialog(listDialog!!, if (listDialog == "Bookmarks") browser.bookmarks else browser.history,
            onOpen = { browser.navigate(tab, it.url); listDialog = null },
            onRemove = if (listDialog == "Bookmarks") browser::removeBookmark else null,
            onClear = if (listDialog == "History") browser::clearHistory else null,
            onDismiss = { listDialog = null })
        "Clear" -> AlertDialog(onDismissRequest = { listDialog = null }, title = { Text("Clear browsing data?") },
            text = { Text("History, cookies, site storage and cached files are removed. Open tabs stay.") },
            confirmButton = { TextButton(onClick = { browser.clearBrowsingData(); listDialog = null }) {
                Text("Clear", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { listDialog = null }) { Text("Cancel") } })
    }
}

private fun displayUrl(url: String): androidx.compose.ui.text.AnnotatedString = buildAnnotatedString {
    val clean = url.removePrefix("https://").removePrefix("http://").removePrefix("www.")
    val host = clean.substringBefore('/')
    val rest = clean.removePrefix(host)
    withStyle(SpanStyle(color = Muted)) { append(host + if (rest.isNotEmpty()) "/" else "") }
    withStyle(SpanStyle(color = Color.White)) { append(rest.removePrefix("/")) }
}

@Composable
private fun PageListDialog(title: String, items: List<HistoryItem>, onOpen: (HistoryItem) -> Unit,
    onRemove: ((HistoryItem) -> Unit)?, onClear: (() -> Unit)?, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (items.isEmpty()) Text(if (title == "History") "No history yet." else "No bookmarks yet.", color = Muted)
                items.toList().take(150).forEach { item ->
                    Row(Modifier.fillMaxWidth().clickable { onOpen(item) }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).background(faviconColor(item.url), RoundedCornerShape(3.dp)))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                            Text(item.url, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = Muted)
                        }
                        if (onRemove != null) TextButton(onClick = { onRemove(item) }) { Text("Remove", fontSize = 12.sp) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { if (onClear != null && items.isNotEmpty()) TextButton(onClick = onClear) { Text("Clear history") } })
}

// ---- device switch -----------------------------------------------------------------------------

@Composable
private fun DeviceBar(browser: BrowserController) {
    Row(Modifier.fillMaxWidth().background(Bar).padding(start = 8.dp, end = 8.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Field).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            DeviceMode.entries.forEach { item ->
                val on = browser.mode == item
                Row(Modifier.weight(1f).height(28.dp).clip(RoundedCornerShape(8.dp)).background(if (on) Accent else Color.Transparent)
                    .clickable { browser.changeMode(item) }, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    DeviceGlyph(item, if (on) Color.White else Muted)
                    Spacer(Modifier.width(6.dp))
                    Text(item.label, fontSize = 12.sp, color = if (on) Color.White else Muted, fontWeight = if (on) FontWeight.Medium else null)
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(Field).clickable {
            when (browser.mode) {
                DeviceMode.PC -> browser.fullscreen = true
                DeviceMode.Tablet -> browser.rotated = !browser.rotated
                DeviceMode.Mobile -> browser.active?.let { browser.reload(it) }
            }
        }, contentAlignment = Alignment.Center) {
            ToolGlyph(when (browser.mode) { DeviceMode.PC -> ToolIcon.Fullscreen; DeviceMode.Tablet -> ToolIcon.Rotate; else -> ToolIcon.Refresh },
                Color(0xFFD4D4D4), Modifier.size(16.dp))
        }
    }
}

@Composable
private fun DeviceGlyph(mode: DeviceMode, tint: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(14.dp)) {
        val s = androidx.compose.ui.graphics.drawscope.Stroke(1.6.dp.toPx())
        val w = size.width; val h = size.height
        when (mode) {
            DeviceMode.Mobile -> drawRoundRect(tint, androidx.compose.ui.geometry.Offset(w * .3f, h * .05f),
                androidx.compose.ui.geometry.Size(w * .4f, h * .9f), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()), style = s)
            DeviceMode.Tablet -> drawRoundRect(tint, androidx.compose.ui.geometry.Offset(w * .15f, h * .05f),
                androidx.compose.ui.geometry.Size(w * .7f, h * .9f), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()), style = s)
            DeviceMode.PC -> {
                drawRoundRect(tint, androidx.compose.ui.geometry.Offset(w * .05f, h * .12f), androidx.compose.ui.geometry.Size(w * .9f, h * .58f),
                    androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()), style = s)
                drawLine(tint, androidx.compose.ui.geometry.Offset(w * .3f, h * .9f), androidx.compose.ui.geometry.Offset(w * .7f, h * .9f), s.width)
            }
        }
    }
}

// ---- page area ---------------------------------------------------------------------------------

@Composable
private fun Stage(browser: BrowserController, tab: BrowserTab) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()
        .background(if (browser.mode == DeviceMode.Mobile) Color.White else Color(0xFF1B1B1E))) {
        val mode = browser.mode
        if (mode == DeviceMode.Mobile) {
            key(tab.id) { BrowserWebView(browser, tab, Modifier.fillMaxSize()) }
            SizePill("${maxWidth.value.toInt()} × ${maxHeight.value.toInt()} · 100%", Modifier.align(Alignment.BottomEnd))
        } else {
            val cssW = if (browser.rotated && mode == DeviceMode.Tablet) mode.height else mode.width
            val cssH = if (browser.rotated && mode == DeviceMode.Tablet) mode.width else mode.height
            val frame = if (mode == DeviceMode.Tablet) 22f else 14f
            val chromeTop = if (mode == DeviceMode.PC) 18f else 0f
            val scale = minOf(maxWidth.value * 0.94f / (cssW + frame * 2), maxHeight.value * 0.92f / (cssH + frame * 2 + chromeTop), 1f)
            Column(Modifier.align(Alignment.Center)
                .layout { measurable, _ ->
                    val fullW = with(density) { (cssW + frame * 2).dp.roundToPx() }
                    val fullH = with(density) { (cssH + frame * 2 + chromeTop).dp.roundToPx() }
                    val placeable = measurable.measure(Constraints.fixed(fullW, fullH))
                    layout((fullW * scale).toInt(), (fullH * scale).toInt()) {
                        placeable.placeWithLayer(0, 0) { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0f) }
                    }
                }
                .clip(RoundedCornerShape(if (mode == DeviceMode.Tablet) 34.dp else 16.dp))
                .background(if (mode == DeviceMode.Tablet) Color(0xFF0B0B0D) else Color(0xFF2B2B2E))
                .padding(frame.dp)) {
                if (mode == DeviceMode.PC) Row(Modifier.height(chromeTop.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0xFFFF5F57, 0xFFFEBC2E, 0xFF28C840).forEach { Box(Modifier.size(12.dp).background(Color(it), CircleShape)) }
                }
                key(tab.id) {
                    BrowserWebView(browser, tab, Modifier.size(cssW.dp, cssH.dp).clip(RoundedCornerShape(if (mode == DeviceMode.Tablet) 12.dp else 0.dp)))
                }
            }
            SizePill("$cssW × $cssH · ${(scale * 100).toInt()}%" + if (mode == DeviceMode.PC) " · pinch to zoom" else "",
                Modifier.align(Alignment.TopEnd))
        }
    }
}

@Composable
private fun SizePill(text: String, modifier: Modifier) {
    Text(text, modifier = modifier.padding(8.dp).background(Color(0x99000000), RoundedCornerShape(10.dp))
        .padding(horizontal = 7.dp, vertical = 2.dp), color = Color(0xFFBDBDBD), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
}

@Composable
private fun BrowserWebView(browser: BrowserController, tab: BrowserTab, modifier: Modifier) {
    AndroidView(factory = { context -> browser.webViewFor(tab, context) }, modifier = modifier,
        update = { view -> if (view.url == null && !tab.isNewTab) view.loadUrl(tab.url) })
}

// ---- new tab page ------------------------------------------------------------------------------

private data class Shortcut(val title: String, val badge: String, val color: Color, val url: String, val custom: HistoryItem? = null)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NewTabPage(model: WorkspaceModel, browser: BrowserController, tab: BrowserTab) {
    var query by remember { mutableStateOf("") }
    var addDialog by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val shortcuts = buildList {
        if (model.project != null) add(Shortcut("My project", "▶", Live, PROJECT_ORIGIN + "/" + (model.previewEntry() ?: "index.html")))
        add(Shortcut("GitHub", "GH", Color.White, "https://github.com"))
        add(Shortcut("Web docs", "MDN", Color(0xFF7AA2F7), "https://developer.mozilla.org"))
        add(Shortcut("Q&A", "SO", Color(0xFFF48024), "https://stackoverflow.com"))
        add(Shortcut("Packages", "npm", Color(0xFFE2C08D), "https://www.npmjs.com"))
        add(Shortcut("CSS tricks", "CSS", Color(0xFFC586C0), "https://css-tricks.com"))
        add(Shortcut("Wikipedia", "W", Color(0xFF4EC9B0), "https://www.wikipedia.org"))
        browser.shortcuts.forEach { add(Shortcut(it.title, it.title.take(2), faviconColor(it.url), it.url, it)) }
    }
    Column(Modifier.fillMaxSize().background(Field).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(buildAnnotatedString {
            withStyle(SpanStyle(color = Color.White)) { append("Cod") }
            withStyle(SpanStyle(color = Color(0xFF3794FF))) { append("riod") }
        }, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(23.dp)).background(Side)
            .border(1.dp, Color(0xFF3C3C3C), RoundedCornerShape(23.dp)).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolGlyph(ToolIcon.Search, Muted, Modifier.size(17.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Search the web or enter a URL", color = Muted, fontSize = 13.5.sp)
                BasicTextField(query, { query = it }, singleLine = true,
                    textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 13.5.sp), cursorBrush = SolidColor(Color(0xFF3794FF)),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { browser.navigate(tab, query); focus.clearFocus() }),
                    modifier = Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.height(24.dp))
        shortcuts.plus(Shortcut("Add", "+", Muted, "")).chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                row.forEach { item ->
                    Column(Modifier.weight(1f).combinedClickable(
                        onClick = { if (item.url.isEmpty()) addDialog = true else browser.navigate(tab, item.url) },
                        onLongClick = { item.custom?.let(browser::removeShortcut) }),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(46.dp).background(Side, CircleShape), contentAlignment = Alignment.Center) {
                            Text(item.badge, color = item.color, fontWeight = FontWeight.Bold, fontSize = if (item.badge.length > 2) 13.sp else 15.sp)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(item.title, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (item.title == "My project") Text("localhost:3000", fontSize = 9.5.sp, color = Muted)
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (browser.history.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("RECENT", fontSize = 11.sp, color = Muted, letterSpacing = 1.sp, modifier = Modifier.fillMaxWidth())
            browser.history.take(6).forEach { item ->
                Row(Modifier.fillMaxWidth().clickable { browser.navigate(tab, item.url) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(13.dp).background(faviconColor(item.url), RoundedCornerShape(3.dp)))
                    Spacer(Modifier.width(10.dp))
                    Text(item.title, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(relativeAgo(item.time), fontSize = 11.sp, color = Muted)
                }
                HorizontalDivider(color = Color(0xFF2C2C2C))
            }
        }
    }
    if (addDialog) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { addDialog = false }, title = { Text("Add shortcut") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(30) }, singleLine = true, label = { Text("Name") })
                OutlinedTextField(url, { url = it.trim() }, singleLine = true, label = { Text("URL") })
            } },
            confirmButton = { TextButton(enabled = url.isNotBlank(), onClick = { browser.addShortcut(name, url); addDialog = false }) { Text("Add") } },
            dismissButton = { TextButton(onClick = { addDialog = false }) { Text("Cancel") } })
    }
}

fun relativeAgo(time: Long): String {
    val minutes = ((System.currentTimeMillis() - time).coerceAtLeast(0)) / 60_000
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "$minutes min"
        minutes < 24 * 60 -> "${minutes / 60} h"
        minutes < 48 * 60 -> "yesterday"
        else -> "${minutes / (24 * 60)} days"
    }
}
