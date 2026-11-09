package dev.moavia.codriod.workspace

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.*
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLDecoder

data class PreviewAsset(val file: File, val mime: String)

class PreviewFiles(private val store: ProjectStore, private val projectId: String) {
    fun resolve(encodedPath: String): PreviewAsset? = runCatching {
        val path = URLDecoder.decode(encodedPath.replace("+", "%2B"), "UTF-8").removePrefix("/")
        val file = store.resolve(projectId, path.ifEmpty { "index.html" })
        require(file.isFile && file.length() <= 16 * 1024 * 1024)
        val mime = when (file.extension.lowercase()) {
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js", "mjs" -> "application/javascript"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "ico" -> "image/x-icon"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "txt", "md" -> "text/plain"
            else -> error("Unsupported preview asset")
        }
        PreviewAsset(file, mime)
    }.getOrNull()
}

private const val PREVIEW_HOST = "preview.codriod.local"

private class LocalPreviewClient(private val files: PreviewFiles) : WebViewClient() {
    private fun allowed(uri: Uri) = uri.scheme == "https" && uri.host == PREVIEW_HOST && uri.port == -1
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = !allowed(request.url)
    @Suppress("DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView, url: String) = !allowed(Uri.parse(url))

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
        val asset = if (allowed(request.url) && request.method == "GET")
            files.resolve(request.url.encodedPath ?: "/") else null
        if (asset == null) return WebResourceResponse(
            "text/plain", "utf-8", 404, "Not Found", emptyMap(),
            ByteArrayInputStream("Local preview resource not found or blocked".toByteArray()))
        return try {
            WebResourceResponse(asset.mime, "utf-8", 200, "OK", mapOf(
                "Cache-Control" to "no-store",
                "X-Content-Type-Options" to "nosniff",
                "Content-Security-Policy" to "default-src 'self' data: blob:; " +
                    "script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; " +
                    "connect-src 'self'; frame-src 'none'; object-src 'none'; base-uri 'self'"
            ), asset.file.inputStream())
        } catch (_: Exception) {
            WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream("Could not read file".toByteArray()))
        }
    }
}

private data class Viewport(val label: String, val width: Int, val height: Int)
private val viewports = listOf(Viewport("Mobile", 390, 844), Viewport("Tablet", 768, 1024), Viewport("PC", 1280, 800))

@Composable
fun WorkspacePreview(model: WorkspaceModel, modifier: Modifier = Modifier) {
    val project = model.project ?: return
    val initialEntry = model.previewEntry()
    val pages = remember(model.entries, initialEntry) {
        (model.entries.filter { !it.directory && it.path.substringAfterLast('.', "").equals("html", true) }
            .map { it.path } + listOfNotNull(initialEntry)).distinct()
    }
    var entry by remember(project.id, initialEntry) { mutableStateOf(initialEntry) }
    var mode by remember { mutableStateOf("PC") }
    var rotated by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var address by remember(entry) { mutableStateOf("https://$PREVIEW_HOST/${entry ?: ""}") }
    var addressError by remember { mutableStateOf<String?>(null) }
    var devOpen by remember { mutableStateOf(false) }
    var devTab by remember { mutableStateOf("Console") }
    val messages = remember(project.id) { mutableStateListOf<String>() }
    val density = LocalDensity.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val log: (String) -> Unit = {
        messages.add(it.take(400))
        if (messages.size > 100) messages.removeAt(0)
    }
    fun selectPage(path: String) {
        entry = path
        address = "https://$PREVIEW_HOST/${path.split('/').joinToString("/") { Uri.encode(it) }}"
        addressError = null
    }
    fun navigate(raw: String) {
        val path = runCatching {
            val typed = raw.trim()
            require(typed.isNotEmpty())
            val uri = Uri.parse(if (typed.startsWith("https://", true)) typed else "https://$PREVIEW_HOST/${typed.trimStart('/')}")
            require(uri.scheme == "https" && uri.host == PREVIEW_HOST && uri.port == -1)
            URLDecoder.decode((uri.encodedPath ?: "/").removePrefix("/"), "UTF-8").ifBlank { "index.html" }
        }.getOrNull()
        if (path == null || !path.endsWith(".html", true) || PreviewFiles(model.store, project.id).resolve(path) == null) {
            addressError = "Enter a valid project HTML path, for example index.html"
            return
        }
        selectPage(path)
        devTab = "Console"
        focusManager.clearFocus()
    }

    Column(modifier.background(Color(0xFF111522))) {
        Surface(color = Color(0xFF171B2A)) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    TextButton(onClick = { model.previewOpen = false }) { Text("Back", color = Color(0xFFCDD4EA), fontSize = 12.sp) }
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it; addressError = null },
                        modifier = Modifier.weight(1f).height(48.dp),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 11.sp, color = Color(0xFFDDE2F4)),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Go),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { navigate(address) }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF7557E8), unfocusedBorderColor = Color(0xFF303650),
                            focusedContainerColor = Color(0xFF20243A), unfocusedContainerColor = Color(0xFF20243A),
                            cursorColor = Color(0xFF9C89FF)),
                        leadingIcon = { Text("LOCAL", color = Color(0xFF5BC49A), fontSize = 8.sp) },
                        trailingIcon = { TextButton(onClick = { navigate(address) }) { Text("GO", color = Color(0xFFBFC6DB), fontSize = 10.sp) } },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(9.dp))
                    listOf("Mobile", "Tablet", "PC").forEach { label ->
                        ViewportButton(label, selected = mode == label) { mode = label }
                    }
                    IconButton(onClick = { model.refreshFiles(); revision++ }) { Text("R", color = Color(0xFFC9D1EA), fontSize = 11.sp) }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    TextButton(onClick = { rotated = !rotated }) { Text("Rotate", color = Color(0xFFBFC6DB), fontSize = 10.sp) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { devOpen = !devOpen }) {
                        Text(if (devOpen) "Dev ON" else "Dev", color = if (devOpen) Color(0xFF9D85FF) else Color(0xFFAEB6CC), fontSize = 11.sp)
                    }
                }
            }
        }
        addressError?.let { Text(it, Modifier.fillMaxWidth().background(Color(0xFF3A2028)).padding(8.dp),
            color = Color(0xFFFFB4AB), fontSize = 11.sp) }

        Row(Modifier.fillMaxWidth().height(35.dp).background(Color(0xFF101421))
            .horizontalScroll(rememberScrollState()).padding(start = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            pages.forEach { path ->
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 7.dp, topEnd = 7.dp),
                    color = if (entry == path) Color(0xFF20243A) else Color.Transparent,
                    modifier = Modifier.widthIn(min = 120.dp, max = 210.dp).fillMaxHeight().clickable { selectPage(path) }) {
                    Row(Modifier.padding(horizontal = 9.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("C", color = Color(0xFF9D72FF), fontSize = 10.sp)
                        Text(path.substringAfterLast('/'), Modifier.padding(start = 7.dp), color = Color(0xFFDDE2F4), fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
        }

        if (entry == null) {
            Box(Modifier.weight(1f).padding(24.dp)) { Text("Add an HTML file to preview this project.", color = Color(0xFFAEB6CC)) }
        } else {
            val viewport = viewports.first { it.label == mode }
            val width = if (rotated) viewport.height else viewport.width
            val height = if (rotated) viewport.width else viewport.height
            Column(Modifier.fillMaxWidth().weight(1f).padding(8.dp)) {
                Text("$mode preview | $width x $height CSS px", color = Color(0xFFAEB6CC),
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 6.dp))
                PreviewWebView(model.store, project.id, entry!!, width, height,
                    model.previewRevision + revision, log, Modifier.fillMaxSize())
            }
        }

        if (devOpen) Surface(color = Color(0xFF151829)) {
            Column {
                Row(Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 6.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    listOf("Console", "Page", "Files").forEach { tab ->
                        TextButton(onClick = { devTab = tab }) {
                            val title = if (tab == "Console") "Console · ${messages.size}" else tab
                            Text(title, color = if (devTab == tab) Color(0xFFDDE2F4) else Color(0xFF8F97AF), fontSize = 10.sp)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (devTab == "Console") TextButton(onClick = { messages.clear() }) { Text("Clear", color = Color(0xFF8F97AF), fontSize = 10.sp) }
                    TextButton(onClick = { devOpen = false }) { Text("Hide", color = Color(0xFF8F97AF), fontSize = 10.sp) }
                }
                when (devTab) {
                    "Console" -> LazyColumn(Modifier.fillMaxWidth().height(142.dp).background(Color(0xFF101421)).padding(vertical = 5.dp)) {
                        if (messages.isEmpty()) item { Text("> Console ready - page JavaScript messages appear here",
                            Modifier.padding(12.dp), color = Color(0xFF757D99), fontSize = 10.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
                        items(messages.toList()) { message -> Text("> $message", Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            color = Color(0xFFAEB6CC), fontSize = 10.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
                    }
                    "Page" -> Column(Modifier.fillMaxWidth().height(142.dp).background(Color(0xFF101421)).padding(12.dp)) {
                        val vp = viewports.first { it.label == mode }
                        Text("URL   ${address.removePrefix("https://")}", color = Color(0xFFDDE2F4), fontSize = 10.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        Text("Viewport   $mode | ${if (rotated) vp.height else vp.width} x ${if (rotated) vp.width else vp.height} CSS px",
                            Modifier.padding(top = 6.dp), color = Color(0xFFAEB6CC), fontSize = 10.sp)
                        Text("Runtime   Local project WebView; external navigation blocked",
                            Modifier.padding(top = 6.dp), color = Color(0xFFAEB6CC), fontSize = 10.sp)
                    }
                    "Files" -> LazyColumn(Modifier.fillMaxWidth().height(142.dp).background(Color(0xFF101421))) {
                        items(pages) { path -> Text(path, Modifier.fillMaxWidth().clickable { selectPage(path) }.padding(12.dp),
                            color = if (entry == path) Color(0xFFBCA9FF) else Color(0xFFAEB6CC), fontSize = 10.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
                        if (pages.isEmpty()) item { Text("No project HTML pages", Modifier.padding(12.dp), color = Color(0xFF8F97AF), fontSize = 10.sp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewportButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val iconColor = if (selected) Color(0xFFA993FF) else Color(0xFFADB4CA)
    Canvas(
        Modifier.size(32.dp, 36.dp).clickable(onClick = onClick)
            .semantics { contentDescription = "$label viewport" }
    ) {
        val stroke = Stroke(width = 1.5.dp.toPx())
        when (label) {
            "Mobile" -> drawRoundRect(iconColor, Offset(size.width * .34f, size.height * .16f),
                Size(size.width * .32f, size.height * .68f), CornerRadius(3.dp.toPx()), style = stroke)
            "Tablet" -> drawRoundRect(iconColor, Offset(size.width * .2f, size.height * .2f),
                Size(size.width * .6f, size.height * .6f), CornerRadius(2.dp.toPx()), style = stroke)
            else -> {
                drawRoundRect(iconColor, Offset(size.width * .1f, size.height * .18f),
                    Size(size.width * .8f, size.height * .52f), CornerRadius(2.dp.toPx()), style = stroke)
                drawLine(iconColor, Offset(size.width * .5f, size.height * .7f), Offset(size.width * .5f, size.height * .83f), stroke.width)
                drawLine(iconColor, Offset(size.width * .32f, size.height * .84f), Offset(size.width * .68f, size.height * .84f), stroke.width)
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PreviewWebView(store: ProjectStore, id: String, entry: String, width: Int,
    height: Int, revision: Int, log: (String) -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    val currentLog by rememberUpdatedState(log)
    val path = entry.split('/').joinToString("/") { Uri.encode(it) }
    val url = "https://$PREVIEW_HOST/$path"
    Box(modifier.clipToBounds().background(Color(0xFF171B2A))) {
        AndroidView(
            modifier = Modifier.layout { measurable, constraints ->
                val fullWidth = with(density) { width.dp.roundToPx() }
                val fullHeight = with(density) { height.dp.roundToPx() }
                val scale = minOf(constraints.maxWidth.toFloat() / fullWidth, constraints.maxHeight.toFloat() / fullHeight, 1f)
                val placeable = measurable.measure(Constraints.fixed(fullWidth, fullHeight))
                layout((fullWidth * scale).toInt(), (fullHeight * scale).toInt()) {
                    placeable.placeWithLayer(0, 0) {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                    }
                }
            },
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.domStorageEnabled = true
                    settings.setSupportMultipleWindows(false)
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    webViewClient = LocalPreviewClient(PreviewFiles(store, id))
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                            currentLog("${message.messageLevel()}: ${message.message()}")
                            return true
                        }
                    }
                    webView = this
                    loadUrl(url)
                }
            },
            update = {
                val key = "$revision:$url"
                if (it.tag != key) { it.tag = key; it.loadUrl(url) }
            }
        )
    }
    DisposableEffect(Unit) {
        onDispose { webView?.apply { stopLoading(); webChromeClient = null; destroy() }; webView = null }
    }
}
