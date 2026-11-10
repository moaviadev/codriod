package dev.moavia.codriod.workspace

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.*
import androidx.compose.runtime.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/** Project files are served at this origin inside the browser, like a local dev server. */
const val PROJECT_ORIGIN = "http://localhost:3000"

enum class DeviceMode(val label: String, val width: Int, val height: Int) {
    Mobile("Mobile", 0, 0), Tablet("Tablet", 768, 1024), PC("PC", 1280, 800)
}

enum class LogLevel { Log, Info, Warn, Error, Input, Result }

data class ConsoleEntry(val level: LogLevel, val text: String, val source: String = "", val line: Int = 0,
    val time: Long = System.currentTimeMillis())

/** A request seen by DevTools: from the Resource Timing API, enriched with fetch/XHR bodies. */
data class NetEntry(val url: String, val method: String = "GET", val status: Int = 0, val type: String = "other",
    val size: Long = 0, val start: Double = 0.0, val duration: Double = 0.0, val mime: String = "", val body: String? = null,
    val error: String? = null)

data class HistoryItem(val url: String, val title: String, val time: Long)

class BrowserTab(val id: Long, url: String) {
    /** Empty means the New tab page. */
    var url by mutableStateOf(url)
    var title by mutableStateOf("New tab")
    var progress by mutableIntStateOf(100)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    val console = mutableStateListOf<ConsoleEntry>()
    /** fetch/XHR captures (bodies); merged with Resource Timing in the Network tab. */
    val captured = mutableStateListOf<NetEntry>()
    internal var view: WebView? = null
    internal var appliedUa: String? = null
    val isNewTab get() = url.isBlank()
}

/** Page-side helper for DevTools. Only reports data to the app; it cannot call anything else. */
internal const val DEVTOOLS_AGENT = """
(function(){
 if (window.__cd) return;
 var B = window.__codriodDevtools;
 function post(o){ try { B && B.post(JSON.stringify(o)); } catch(e) {} }
 var cd = window.__cd = {};
 try { performance.setResourceTimingBufferSize(1000); } catch(e) {}
 var seq = 0;
 var of = window.fetch;
 if (of) window.fetch = function(input, init){
   var id = ++seq, t0 = performance.now();
   var method = (init && init.method) || (input && input.method) || 'GET';
   var url = (typeof input === 'string') ? input : (input && input.url) || String(input);
   try { url = new URL(url, location.href).href; } catch(e) {}
   return of.apply(this, arguments).then(function(r){
     var base = {type:'net', kind:'fetch', method:method, url:url, status:r.status, mime:r.headers.get('content-type') || '', duration:performance.now()-t0};
     if (/json|text|javascript|xml|html/.test(base.mime)) r.clone().text().then(function(b){ base.size = b.length; base.body = b.slice(0, 20000); post(base); }, function(){ post(base); });
     else post(base);
     return r;
   }, function(e){ post({type:'net', kind:'fetch', method:method, url:url, status:0, error:String(e), duration:performance.now()-t0}); throw e; });
 };
 var XO = XMLHttpRequest.prototype.open, XS = XMLHttpRequest.prototype.send;
 XMLHttpRequest.prototype.open = function(m, u){ this.__cdi = {method:m, url:u}; return XO.apply(this, arguments); };
 XMLHttpRequest.prototype.send = function(){
   var x = this, info = x.__cdi || {}, t0 = performance.now();
   x.addEventListener('loadend', function(){
     var url = info.url; try { url = new URL(url, location.href).href; } catch(e) {}
     var body = ''; try { if (!x.responseType || x.responseType === 'text') body = x.responseText; else if (x.responseType === 'json') body = JSON.stringify(x.response); } catch(e) {}
     post({type:'net', kind:'xhr', method:info.method || 'GET', url:url, status:x.status, mime:x.getResponseHeader('content-type') || '', size:(body || '').length, body:(body || '').slice(0, 20000), duration:performance.now()-t0});
   });
   return XS.apply(this, arguments);
 };
 function nodeAt(path){ var n = document.documentElement; if (!path) return n; var p = String(path).split('/'); for (var i = 0; i < p.length; i++) { if (!n) return null; n = n.children[+p[i]]; } return n; }
 function pathOf(el){ var p = []; while (el && el !== document.documentElement) { var par = el.parentElement; if (!par) return null; p.unshift(Array.prototype.indexOf.call(par.children, el)); el = par; } return p.join('/'); }
 function attrs(el){ var a = []; for (var i = 0; i < el.attributes.length && i < 10; i++) a.push([el.attributes[i].name, String(el.attributes[i].value).slice(0, 160)]); return a; }
 function label(el){ var s = el.tagName.toLowerCase(); if (el.id) s += '#' + el.id; if (el.classList && el.classList.length) s += '.' + Array.prototype.join.call(el.classList, '.'); return s; }
 function info(el, path){ var t = ''; if (!el.children.length) t = (el.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 80); return {path:path, tag:el.tagName.toLowerCase(), attrs:attrs(el), text:t, count:el.children.length}; }
 cd.root = function(){ return info(document.documentElement, ''); };
 cd.children = function(path){ var n = nodeAt(path); if (!n) return []; var out = []; for (var i = 0; i < n.children.length && i < 300; i++) { var c = n.children[i]; if (c.getAttribute && c.getAttribute('data-codriod')) continue; out.push(info(c, (path ? path + '/' : '') + i)); } return out; };
 var box, tag;
 function hl(el){
   if (!box) { box = document.createElement('div'); box.setAttribute('data-codriod', '1'); box.style.cssText = 'position:fixed;z-index:2147483647;pointer-events:none;outline:2px solid #3794ff;background:rgba(55,148,255,.15);';
     tag = document.createElement('div'); tag.setAttribute('data-codriod', '1'); tag.style.cssText = 'position:fixed;z-index:2147483647;pointer-events:none;background:#3794ff;color:#fff;font:10px monospace;padding:2px 5px;border-radius:3px;white-space:nowrap'; }
   if (!el) { box.remove(); tag.remove(); return; }
   var r = el.getBoundingClientRect();
   box.style.left = r.left + 'px'; box.style.top = r.top + 'px'; box.style.width = r.width + 'px'; box.style.height = r.height + 'px';
   tag.textContent = label(el) + '  ' + Math.round(r.width) + ' × ' + Math.round(r.height);
   tag.style.left = Math.max(0, r.left) + 'px'; tag.style.top = Math.max(0, r.top - 18) + 'px';
   document.documentElement.appendChild(box); document.documentElement.appendChild(tag);
 }
 cd.highlight = function(path){ var el = path == null ? null : nodeAt(path); if (el && el.scrollIntoView) el.scrollIntoView({block:'nearest'}); hl(el); };
 function pick(e){ e.preventDefault(); e.stopPropagation(); document.removeEventListener('click', pick, true); var el = e.target; hl(el); post({type:'inspect', path:pathOf(el)}); }
 cd.inspect = function(on){ document.removeEventListener('click', pick, true); if (on) document.addEventListener('click', pick, true); };
 cd.details = function(path){
   var el = nodeAt(path); if (!el) return null;
   var cs = getComputedStyle(el), r = el.getBoundingClientRect(), rules = [];
   for (var i = 0; i < document.styleSheets.length; i++) {
     var sh = document.styleSheets[i], list; try { list = sh.cssRules; } catch(e) { continue; }
     var src = sh.href ? sh.href.split('/').pop().split('?')[0] : '<style>';
     for (var j = 0; list && j < list.length && rules.length < 40; j++) {
       var ru = list[j]; if (!ru.selectorText) continue;
       try { if (el.matches(ru.selectorText)) { var props = []; for (var k = 0; k < ru.style.length; k++) { var nm = ru.style[k]; props.push([nm, ru.style.getPropertyValue(nm)]); } rules.push({selector:ru.selectorText, source:src, props:props}); } } catch(e) {}
     }
   }
   var inline = []; for (var k = 0; k < el.style.length; k++) { var nm = el.style[k]; inline.push([nm, el.style.getPropertyValue(nm)]); }
   var keys = ['display','position','box-sizing','width','height','margin','padding','border','color','background-color','font-family','font-size','font-weight','line-height','text-align','flex-direction','justify-content','align-items','gap','z-index','opacity','overflow'];
   function px(n){ return parseFloat(cs.getPropertyValue(n)) || 0; }
   return {label:label(el), path:path, inline:inline, rules:rules.reverse(), computed:keys.map(function(n){ return [n, cs.getPropertyValue(n)]; }), attrs:attrs(el),
     box:{width:r.width, height:r.height, margin:[px('margin-top'), px('margin-right'), px('margin-bottom'), px('margin-left')], padding:[px('padding-top'), px('padding-right'), px('padding-bottom'), px('padding-left')], border:[px('border-top-width'), px('border-right-width'), px('border-bottom-width'), px('border-left-width')]},
     html:el.outerHTML.slice(0, 3000)};
 };
 cd.setStyle = function(path, prop, value){ var el = nodeAt(path); if (el) { if (value) el.style.setProperty(prop, value); else el.style.removeProperty(prop); hl(el); } return true; };
 function store(kind){ return kind === 'session' ? sessionStorage : localStorage; }
 cd.storage = function(kind){ var s = store(kind), out = []; for (var i = 0; i < s.length; i++) { var k = s.key(i); out.push([k, String(s.getItem(k)).slice(0, 4000)]); } return out; };
 cd.setItem = function(kind, k, v){ store(kind).setItem(k, v); return true; };
 cd.removeItem = function(kind, k){ store(kind).removeItem(k); return true; };
 cd.clearStorage = function(kind){ store(kind).clear(); return true; };
 cd.resources = function(){
   var out = [], nav = performance.getEntriesByType('navigation')[0];
   function add(e, type){ out.push({url:e.name, type:type, status:e.responseStatus || 0, size:e.transferSize || e.encodedBodySize || 0, start:e.startTime, duration:e.duration}); }
   if (nav) add(nav, 'document'); else out.push({url:location.href, type:'document', status:0, size:0, start:0, duration:0});
   performance.getEntriesByType('resource').forEach(function(e){ add(e, e.initiatorType || 'other'); });
   return out;
 };
 cd.sources = function(){
   var out = [location.href];
   Array.prototype.forEach.call(document.scripts, function(s){ if (s.src) out.push(s.src); });
   Array.prototype.forEach.call(document.styleSheets, function(s){ if (s.href) out.push(s.href); });
   return out.filter(function(v, i){ return out.indexOf(v) === i; });
 };
 cd.perf = function(){
   var n = performance.getEntriesByType('navigation')[0] || {}, p = {};
   performance.getEntriesByType('paint').forEach(function(e){ p[e.name] = e.startTime; });
   var res = performance.getEntriesByType('resource'), bytes = n.transferSize || 0, byType = {};
   res.forEach(function(e){ bytes += e.transferSize || 0; var t = e.initiatorType || 'other'; byType[t] = byType[t] || [0, 0]; byType[t][0]++; byType[t][1] += e.transferSize || 0; });
   return {ttfb:n.responseStart || 0, dns:(n.domainLookupEnd - n.domainLookupStart) || 0, connect:(n.connectEnd - n.connectStart) || 0,
     dcl:n.domContentLoadedEventEnd || 0, load:n.loadEventEnd || 0, fp:p['first-paint'] || 0, fcp:p['first-contentful-paint'] || 0,
     requests:res.length + 1, bytes:bytes, heap:(performance.memory ? performance.memory.usedJSHeapSize : 0),
     nodes:document.getElementsByTagName('*').length, byType:byType};
 };
})();
"""

/**
 * The in-app browser: tabs (one WebView each), device modes, history and bookmarks, and the data behind
 * DevTools. WebViews are created with the Activity's context and released when the Activity goes away.
 */
class BrowserController(context: Context, private val files: () -> PreviewFiles?, private val openInEditor: (String, Int) -> Unit) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("browser", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private var nextId = 1L

    val tabs = mutableStateListOf<BrowserTab>()
    var activeId by mutableStateOf(0L)
    val active: BrowserTab? get() = tabs.firstOrNull { it.id == activeId } ?: tabs.firstOrNull()

    var mode by mutableStateOf(runCatching { DeviceMode.valueOf(prefs.getString("mode", "Mobile")!!) }.getOrDefault(DeviceMode.Mobile))
        private set
    var rotated by mutableStateOf(false)
    var fullscreen by mutableStateOf(false)
    var devOpen by mutableStateOf(false)
    var devTab by mutableStateOf("Elements")
    var inspecting by mutableStateOf(false)
    var disableCache by mutableStateOf(false)
        private set
    /** Path of the element selected in Elements, as child indexes from <html>. */
    var selectedNode by mutableStateOf<String?>(null)
    var selectionRevision by mutableIntStateOf(0)
        private set

    val history = mutableStateListOf<HistoryItem>()
    val bookmarks = mutableStateListOf<HistoryItem>()
    val shortcuts = mutableStateListOf<HistoryItem>()

    /** Set by the Activity so <input type=file> can open the system picker. */
    var fileChooser: ((ValueCallback<Array<Uri>>) -> Unit)? = null

    private val defaultUa: String by lazy { runCatching { WebSettings.getDefaultUserAgent(app) }.getOrDefault("") }

    init {
        loadList("history", history); loadList("bookmarks", bookmarks); loadList("shortcuts", shortcuts)
    }

    // ---- tabs ----------------------------------------------------------------------------------

    fun newTab(url: String = ""): BrowserTab {
        val tab = BrowserTab(nextId++, url)
        tabs += tab
        activeId = tab.id
        return tab
    }

    fun select(tab: BrowserTab) { activeId = tab.id; selectedNode = null }

    fun close(tab: BrowserTab) {
        tab.view?.let { view -> (view.parent as? ViewGroup)?.removeView(view); view.destroy() }
        tab.view = null
        val index = tabs.indexOf(tab)
        tabs.remove(tab)
        if (tabs.isEmpty()) newTab()
        else if (activeId == tab.id) activeId = tabs[(index - 1).coerceAtLeast(0)].id
    }

    /** Opens [path] of the current project, reusing a tab that already shows the project. */
    fun openProject(path: String?) {
        val url = PROJECT_ORIGIN + "/" + (path ?: "index.html").split('/').joinToString("/") { Uri.encode(it) }
        val existing = tabs.firstOrNull { it.url.startsWith(PROJECT_ORIGIN) }
        if (existing != null) { activeId = existing.id; load(existing, url) } else newTab(url)
    }

    fun ensureTab() { if (tabs.isEmpty()) newTab() }

    /** Turns what the user typed into a URL: an address, a project file, or a web search. */
    fun resolveInput(input: String): String {
        val text = input.trim()
        val hasScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(text)
        return when {
            text.isEmpty() -> ""
            hasScheme -> text
            text.startsWith("localhost") || text.startsWith("127.0.0.1") -> "http://$text"
            files()?.let { f -> text.endsWith(".html") && f.resolve(text.removePrefix("/")) != null } == true ->
                PROJECT_ORIGIN + "/" + text.removePrefix("/")
            !text.contains(' ') && Regex("^[^/]+\\.[a-zA-Z]{2,}(:\\d+)?(/.*)?$").matches(text) -> "https://$text"
            else -> "https://www.google.com/search?q=" + Uri.encode(text)
        }
    }

    fun navigate(tab: BrowserTab, input: String) {
        val url = resolveInput(input)
        if (url.isBlank()) return
        load(tab, url)
    }

    private fun load(tab: BrowserTab, url: String) {
        tab.url = url
        tab.console.clear(); tab.captured.clear()
        tab.view?.loadUrl(url)
    }

    fun back(tab: BrowserTab) { tab.view?.takeIf { it.canGoBack() }?.goBack() ?: run { if (!tab.isNewTab) { tab.url = ""; tab.title = "New tab" } } }
    fun forward(tab: BrowserTab) { tab.view?.takeIf { it.canGoForward() }?.goForward() }
    fun reload(tab: BrowserTab) { tab.console.clear(); tab.captured.clear(); tab.view?.reload() }
    fun goHome(tab: BrowserTab) { tab.url = ""; tab.title = "New tab" }

    /** Reloads tabs that show the project (live reload after a save). */
    fun reloadProjectTabs() = tabs.filter { it.url.startsWith(PROJECT_ORIGIN) }.forEach { reload(it) }

    // ---- device modes ----------------------------------------------------------------------------

    fun changeMode(value: DeviceMode) {
        mode = value
        prefs.edit().putString("mode", value.name).apply()
        if (value != DeviceMode.Tablet) rotated = false
        if (value != DeviceMode.PC) fullscreen = false
        tabs.forEach { applyUa(it) }
    }

    fun userAgent(): String = when (mode) {
        DeviceMode.Mobile -> defaultUa
        DeviceMode.Tablet -> defaultUa.replace(" Mobile", "")
        DeviceMode.PC -> {
            val chrome = Regex("Chrome/[0-9.]+").find(defaultUa)?.value ?: "Chrome/130.0.0.0"
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
        }
    }

    private fun applyUa(tab: BrowserTab) {
        val view = tab.view ?: return
        val ua = userAgent()
        if (tab.appliedUa != ua) {
            view.settings.userAgentString = ua
            tab.appliedUa = ua
            if (!tab.isNewTab) view.reload()
        }
    }

    fun updateDisableCache(value: Boolean) {
        disableCache = value
        tabs.forEach { it.view?.settings?.cacheMode = if (value) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT }
    }

    // ---- WebView -------------------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    fun webViewFor(tab: BrowserTab, context: Context): WebView {
        tab.view?.let { view -> (view.parent as? ViewGroup)?.removeView(view); return view }
        val view = WebView(context)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = if (disableCache) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
            userAgentString = userAgent()
        }
        tab.appliedUa = view.settings.userAgentString
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        view.addJavascriptInterface(Bridge(tab), "__codriodDevtools")
        view.webViewClient = Client(tab)
        view.webChromeClient = Chrome(tab)
        view.setDownloadListener { url, userAgent, disposition, mime, _ -> download(url, userAgent, disposition, mime) }
        tab.view = view
        if (!tab.isNewTab) view.loadUrl(tab.url)
        return view
    }

    /** Called when the Activity is destroyed: WebViews hold its context. Tabs keep their URLs. */
    fun releaseViews() {
        tabs.forEach { tab -> tab.view?.let { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }; tab.view = null }
    }

    private fun injectAgent(view: WebView) = view.evaluateJavascript(DEVTOOLS_AGENT, null)

    private inner class Client(private val tab: BrowserTab) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme?.lowercase()
            if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "data" || scheme == "blob") return false
            runCatching { view.context.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return true
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url
            if (url.scheme != "http" || url.host != "localhost" || url.port != 3000) return null
            val assets = files()
            val asset = if (request.method == "GET") assets?.resolve(url.encodedPath ?: "/") else null
            return if (asset == null) WebResourceResponse("text/html", "utf-8", 404, "Not Found", mapOf("Cache-Control" to "no-store"),
                ByteArrayInputStream(("<!doctype html><title>404</title><body style='font:14px system-ui;padding:24px'>" +
                    "<h2>404 · Not found</h2><p>${if (assets == null) "Open a project to serve it here." else "This file is not in the project."}</p>")
                    .toByteArray()))
            else runCatching {
                WebResourceResponse(asset.mime, "utf-8", 200, "OK", mapOf("Cache-Control" to "no-store",
                    "X-Content-Type-Options" to "nosniff", "Timing-Allow-Origin" to "*"), asset.openStream())
            }.getOrNull()
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            tab.url = url
            tab.console.clear(); tab.captured.clear()
            if (activeId == tab.id) { selectedNode = null; inspecting = false }
            injectAgent(view)
        }

        override fun onPageFinished(view: WebView, url: String) {
            injectAgent(view)
            tab.url = url
            tab.canGoBack = view.canGoBack(); tab.canGoForward = view.canGoForward()
            view.title?.takeIf { it.isNotBlank() }?.let { tab.title = it }
            remember(url, tab.title)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            tab.url = url
            tab.canGoBack = view.canGoBack(); tab.canGoForward = view.canGoForward()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) tab.console += ConsoleEntry(LogLevel.Error,
                "Failed to load ${request.url}: ${error.description}", request.url.toString())
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            tab.console += ConsoleEntry(LogLevel.Error, "GET ${request.url} ${response.statusCode} (${response.reasonPhrase ?: ""})",
                request.url.toString())
        }
    }

    private inner class Chrome(private val tab: BrowserTab) : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) { tab.progress = newProgress }
        override fun onReceivedTitle(view: WebView, title: String?) { if (!title.isNullOrBlank()) tab.title = title }
        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            val level = when (message.messageLevel()) {
                ConsoleMessage.MessageLevel.ERROR -> LogLevel.Error
                ConsoleMessage.MessageLevel.WARNING -> LogLevel.Warn
                ConsoleMessage.MessageLevel.TIP, ConsoleMessage.MessageLevel.DEBUG -> LogLevel.Info
                else -> LogLevel.Log
            }
            tab.console += ConsoleEntry(level, message.message(), message.sourceId() ?: "", message.lineNumber())
            if (tab.console.size > 600) tab.console.removeAt(0)
            return true
        }
        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            val chooser = fileChooser ?: return false
            chooser(callback)
            return true
        }
        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) =
            callback.invoke(origin, false, false)
    }

    /** Receives DevTools reports from the page (on a background thread). */
    inner class Bridge(private val tab: BrowserTab) {
        @JavascriptInterface
        fun post(json: String) {
            if (json.length > 60_000) return
            main.post {
                val o = runCatching { JSONObject(json) }.getOrNull() ?: return@post
                when (o.optString("type")) {
                    "net" -> {
                        tab.captured += NetEntry(o.optString("url"), o.optString("method", "GET"), o.optInt("status"),
                            o.optString("kind"), o.optLong("size"), 0.0, o.optDouble("duration", 0.0), o.optString("mime"),
                            o.optString("body").ifEmpty { null }, o.optString("error").ifEmpty { null })
                        if (tab.captured.size > 300) tab.captured.removeAt(0)
                    }
                    "inspect" -> if (tab.id == activeId) {
                        inspecting = false
                        selectedNode = o.optString("path")
                        selectionRevision++
                        devOpen = true
                        devTab = "Elements"
                    }
                }
            }
        }
    }

    // ---- DevTools commands (main thread) ---------------------------------------------------------

    fun js(tab: BrowserTab, code: String, result: (String?) -> Unit = {}) {
        val view = tab.view ?: return result(null)
        view.evaluateJavascript("(function(){ $DEVTOOLS_AGENT; try { return (function(){ return $code; })(); } catch(e) { return {__error: String(e)}; } })()") { result(it) }
    }

    fun call(tab: BrowserTab, function: String, vararg args: Any?, result: (String?) -> Unit = {}) {
        val list = JSONArray().apply { args.forEach { put(it ?: JSONObject.NULL) } }.toString()
        js(tab, "window.__cd.$function.apply(null, $list)", result)
    }

    fun setInspecting(tab: BrowserTab, on: Boolean) { inspecting = on; call(tab, "inspect", on) }

    fun selectNode(tab: BrowserTab, path: String?) {
        selectedNode = path; selectionRevision++
        call(tab, "highlight", path)
    }

    /** Runs code typed in the Console and prints its result. */
    fun runConsole(tab: BrowserTab, code: String) {
        if (code.isBlank()) return
        tab.console += ConsoleEntry(LogLevel.Input, code)
        val view = tab.view ?: return
        view.evaluateJavascript(code) { value ->
            tab.console += ConsoleEntry(LogLevel.Result, value?.takeIf { it != "null" } ?: "undefined")
        }
    }

    fun openSourceInEditor(source: String, line: Int): Boolean {
        if (!source.startsWith("$PROJECT_ORIGIN/")) return false
        val path = URLDecoder.decode(source.removePrefix("$PROJECT_ORIGIN/").substringBefore('?').substringBefore('#'), "UTF-8")
        if (path.isBlank()) return false
        openInEditor(path, line.coerceAtLeast(1))
        return true
    }

    /** Source text of a page resource: project files from the project, others over the network. Blocking. */
    fun fetchSource(url: String): String {
        if (url.startsWith("$PROJECT_ORIGIN/")) {
            val asset = files()?.resolve(Uri.parse(url).encodedPath ?: "/") ?: error("Not a project file")
            return asset.openStream().use { it.readBytes().toString(Charsets.UTF_8) }
        }
        require(url.startsWith("https://") || url.startsWith("http://")) { "Only web addresses can be shown" }
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000; connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", userAgent())
            CookieManager.getInstance().getCookie(url)?.let { connection.setRequestProperty("Cookie", it) }
            val stream = if (connection.responseCode in 200..399) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input -> input.readBytes().let { if (it.size > 1_500_000) it.copyOf(1_500_000) else it } } ?: ByteArray(0)
            return bytes.toString(Charsets.UTF_8)
        } finally { connection.disconnect() }
    }

    fun cookies(url: String): List<Pair<String, String>> =
        CookieManager.getInstance().getCookie(url)?.split(";")?.mapNotNull { part ->
            val trimmed = part.trim(); if (trimmed.isEmpty()) null
            else trimmed.substringBefore('=') to trimmed.substringAfter('=', "")
        }.orEmpty()

    fun deleteCookie(url: String, name: String) {
        val host = Uri.parse(url).host ?: return
        val manager = CookieManager.getInstance()
        listOf("", "; path=/", "; domain=$host; path=/", "; domain=.${host.removePrefix("www.")}; path=/").forEach {
            manager.setCookie(url, "$name=; Max-Age=0$it")
        }
        manager.flush()
    }

    fun clearCookies() { CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush() }

    // ---- history, bookmarks, shortcuts -------------------------------------------------------------

    private fun remember(url: String, title: String) {
        if (url.isBlank() || url.startsWith("about:") || url.startsWith("data:")) return
        history.removeAll { it.url == url }
        history.add(0, HistoryItem(url, title.ifBlank { url }, System.currentTimeMillis()))
        while (history.size > 200) history.removeAt(history.lastIndex)
        saveList("history", history)
    }

    fun clearHistory() { history.clear(); saveList("history", history) }

    fun isBookmarked(url: String) = bookmarks.any { it.url == url }

    fun toggleBookmark(tab: BrowserTab) {
        if (tab.isNewTab) return
        if (isBookmarked(tab.url)) bookmarks.removeAll { it.url == tab.url }
        else bookmarks.add(0, HistoryItem(tab.url, tab.title, System.currentTimeMillis()))
        saveList("bookmarks", bookmarks)
    }

    fun removeBookmark(item: HistoryItem) { bookmarks.remove(item); saveList("bookmarks", bookmarks) }

    fun addShortcut(title: String, input: String) {
        val url = resolveInput(input); if (url.isBlank()) return
        shortcuts += HistoryItem(url, title.ifBlank { Uri.parse(url).host ?: url }, System.currentTimeMillis())
        saveList("shortcuts", shortcuts)
    }

    fun removeShortcut(item: HistoryItem) { shortcuts.remove(item); saveList("shortcuts", shortcuts) }

    fun clearBrowsingData() {
        clearHistory(); clearCookies(); WebStorage.getInstance().deleteAllData()
        tabs.forEach { it.view?.clearCache(true) }
    }

    private fun loadList(key: String, into: MutableList<HistoryItem>) {
        runCatching {
            val array = JSONArray(prefs.getString(key, "[]"))
            for (i in 0 until array.length()) array.getJSONObject(i).let {
                into += HistoryItem(it.getString("u"), it.optString("t"), it.optLong("w"))
            }
        }
    }

    private fun saveList(key: String, items: List<HistoryItem>) {
        prefs.edit().putString(key, JSONArray().apply {
            items.forEach { put(JSONObject().put("u", it.url).put("t", it.title).put("w", it.time)) }
        }.toString()).apply()
    }

    // ---- downloads -----------------------------------------------------------------------------

    private fun download(url: String, userAgent: String, disposition: String?, mime: String?) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 29 && (url.startsWith("http://") || url.startsWith("https://"))) {
                val name = URLUtil.guessFileName(url, disposition, mime)
                val request = DownloadManager.Request(Uri.parse(url))
                    .setMimeType(mime).addRequestHeader("User-Agent", userAgent)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
                (app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                active?.console?.add(ConsoleEntry(LogLevel.Info, "Downloading $name to Downloads"))
            } else app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
