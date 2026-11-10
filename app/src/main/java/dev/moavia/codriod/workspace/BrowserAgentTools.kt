package dev.moavia.codriod.workspace

import android.graphics.Bitmap
import dev.moavia.codriod.agent.AgentMode
import dev.moavia.codriod.agent.AgentToolset
import dev.moavia.codriod.agent.AgentTools
import dev.moavia.codriod.agent.ToolOutcome
import dev.moavia.codriod.agent.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

/** Page-side helpers for the agent: numbered interactive elements, clicking and typing like a person. */
private const val AGENT_JS = """
(function(){
 if (window.__cdAgent) return;
 var A = window.__cdAgent = {};
 function visible(el){ var r = el.getBoundingClientRect(); var s = getComputedStyle(el); return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'none'; }
 function label(el){ return (el.getAttribute('aria-label') || el.innerText || el.value || el.placeholder || el.title || el.alt || el.name || '').trim().replace(/\s+/g, ' ').slice(0, 80); }
 A.snapshot = function(){
   var sel = 'a[href],button,input,select,textarea,[role=button],[role=link],[role=tab],[role=checkbox],[role=menuitem],[onclick],[contenteditable=true],summary,label[for]';
   var els = Array.prototype.filter.call(document.querySelectorAll(sel), visible).slice(0, 150);
   var out = [];
   els.forEach(function(el, i){ el.setAttribute('data-cd-id', String(i + 1)); var t = el.tagName.toLowerCase();
     out.push({i: i + 1, tag: t, type: el.type || el.getAttribute('role') || '', label: label(el), href: t === 'a' ? (el.getAttribute('href') || '').slice(0, 120) : '', value: (t === 'input' || t === 'textarea') ? String(el.value || '').slice(0, 60) : ''}); });
   var heads = Array.prototype.slice.call(document.querySelectorAll('h1,h2,h3'), 0, 20).map(function(h){ return h.tagName + ' ' + h.innerText.trim().slice(0, 90); });
   var text = (document.body ? document.body.innerText : '').replace(/\n{3,}/g, '\n\n').slice(0, 6000);
   return {url: location.href, title: document.title, scroll: [Math.round(scrollY), Math.round(document.documentElement.scrollHeight), innerHeight], headings: heads, elements: out, text: text};
 };
 function find(target){
   if (target.index) { var e = document.querySelector('[data-cd-id="' + target.index + '"]'); if (e) return e; }
   if (target.selector) { try { var s = document.querySelector(target.selector); if (s) return s; } catch(e) { throw new Error('Invalid selector'); } }
   if (target.text) { var want = target.text.toLowerCase(); var all = document.querySelectorAll('a,button,[role=button],input[type=submit],label,summary,li,span,div');
     for (var i = 0; i < all.length; i++) { if (visible(all[i]) && label(all[i]).toLowerCase() === want) return all[i]; }
     for (var j = 0; j < all.length; j++) { if (visible(all[j]) && label(all[j]).toLowerCase().indexOf(want) >= 0) return all[j]; } }
   return null;
 }
 A.click = function(target){ var el = find(target); if (!el) return {ok: false, error: 'Element not found. Call browser_snapshot to get fresh numbers.'};
   el.scrollIntoView({block: 'center'}); var r = el.getBoundingClientRect(); var x = r.left + r.width / 2, y = r.top + r.height / 2;
   ['pointerdown', 'mousedown', 'pointerup', 'mouseup'].forEach(function(t){ el.dispatchEvent(new MouseEvent(t, {bubbles: true, clientX: x, clientY: y})); });
   el.click(); if (el.focus) el.focus(); return {ok: true, clicked: el.tagName.toLowerCase() + ' "' + label(el) + '"'}; };
 A.type = function(target, text, clear, submit){ var el = find(target); if (!el) return {ok: false, error: 'Field not found. Call browser_snapshot to get fresh numbers.'};
   el.scrollIntoView({block: 'center'}); el.focus();
   if (el.isContentEditable) { if (clear) el.innerText = ''; el.innerText += text; el.dispatchEvent(new InputEvent('input', {bubbles: true})); }
   else { var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : (el.tagName === 'SELECT' ? HTMLSelectElement.prototype : HTMLInputElement.prototype);
     var setter = Object.getOwnPropertyDescriptor(proto, 'value').set; setter.call(el, (clear ? '' : (el.value || '')) + text);
     el.dispatchEvent(new Event('input', {bubbles: true})); el.dispatchEvent(new Event('change', {bubbles: true})); }
   if (submit) { el.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', code: 'Enter', keyCode: 13, bubbles: true}));
     if (el.form) { if (el.form.requestSubmit) el.form.requestSubmit(); else el.form.submit(); } }
   return {ok: true, typed: text.length + ' characters into ' + el.tagName.toLowerCase() + ' "' + label(el) + '"'}; };
 A.scroll = function(dir, px){ var h = innerHeight * 0.8; if (dir === 'top') scrollTo(0, 0); else if (dir === 'bottom') scrollTo(0, document.documentElement.scrollHeight);
   else scrollBy(0, (dir === 'up' ? -1 : 1) * (px || h)); return {ok: true, y: Math.round(scrollY), height: Math.round(document.documentElement.scrollHeight)}; };
})();
"""

/**
 * Lets the coding agent use Codriod's browser like a person: open pages, read them, click, type, scroll, run
 * JavaScript, read console and network, and take screenshots it can look at.
 */
class BrowserAgentTools(
    private val browser: BrowserController,
    private val enabled: () -> Boolean,
    private val vision: () -> Boolean,
    private val shotsDir: File,
    private val onScreenshot: (String) -> Unit
) : AgentToolset {

    private fun spec(name: String, description: String, vararg props: Triple<String, String, String>, required: List<String> = emptyList()) =
        ToolSpec(name, description, AgentTools.schema(*props, required = required))

    private val reading = listOf(
        spec("browser_open", "Open a URL in Codriod's browser and wait for it to load. The user's project is at http://localhost:3000/ " +
            "(e.g. http://localhost:3000/index.html). Optional device: mobile, tablet or pc (changes screen size and user agent).",
            Triple("url", "string", "Full URL"), Triple("device", "string", "mobile | tablet | pc (optional)"), required = listOf("url")),
        spec("browser_snapshot", "Read the current page: URL, title, headings, visible text and a numbered list of clickable/typeable elements. " +
            "Use the numbers with browser_click and browser_type."),
        spec("browser_screenshot", "Take a screenshot of the browser page and look at it (vision models). Use it to check layout and design."),
        spec("browser_console", "Recent console messages and errors of the page."),
        spec("browser_network", "Requests the page made: URL, type, status, size, time."),
        spec("browser_scroll", "Scroll the page.", Triple("direction", "string", "down | up | top | bottom"),
            Triple("pixels", "integer", "Distance for up/down (optional)"), required = listOf("direction")),
        spec("browser_back", "Go back to the previous page.")
    )
    private val acting = listOf(
        spec("browser_click", "Click an element by its number from browser_snapshot, a CSS selector, or its visible text.",
            Triple("element", "integer", "Number from browser_snapshot"), Triple("selector", "string", "CSS selector"),
            Triple("text", "string", "Visible text of the element")),
        spec("browser_type", "Type into an input, textarea or editable element. Optionally clear it first and press Enter / submit.",
            Triple("element", "integer", "Number from browser_snapshot"), Triple("selector", "string", "CSS selector"),
            Triple("text", "string", "Text to type"), Triple("clear", "boolean", "Clear the field first"),
            Triple("submit", "boolean", "Press Enter / submit the form"), required = listOf("text")),
        spec("browser_eval", "Run JavaScript in the page and return the result (JSON). Use for checks the other tools can't do.",
            Triple("code", "string", "A JavaScript expression or IIFE"), required = listOf("code"))
    )
    private val names = (reading + acting).map { it.name }.toSet()

    override fun specs(mode: AgentMode) = if (!enabled()) emptyList() else if (mode.canEdit) reading + acting else reading
    override fun handles(name: String) = name in names

    private suspend fun <T> main(block: suspend () -> T): T = withContext(Dispatchers.Main) { block() }

    private suspend fun eval(tab: BrowserTab, code: String): String? = main {
        val view = browser.ensureView(tab) ?: return@main null
        withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine<String?> { cont -> view.evaluateJavascript("(function(){ $AGENT_JS; return $code; })()") { cont.resume(it) } }
        }
    }

    private suspend fun waitLoaded(tab: BrowserTab, timeoutMs: Long = 20_000) {
        delay(250)
        withTimeoutOrNull(timeoutMs) { while (tab.progress < 100) delay(150) }
        delay(400)
    }

    private fun errorsSince(tab: BrowserTab, from: Int): String {
        val list = tab.console.drop(from).filter { it.level == LogLevel.Error || it.level == LogLevel.Warn }
        return if (list.isEmpty()) "" else "\nConsole:\n" + list.takeLast(8).joinToString("\n") { "${it.level}: ${it.text.take(300)}" +
            (if (it.source.isNotBlank()) " (${it.source.substringAfterLast('/')}:${it.line})" else "") }
    }

    override suspend fun execute(name: String, args: JSONObject, mode: AgentMode): ToolOutcome {
        if (!enabled()) return ToolOutcome("Error: browser control is turned off in Agent settings.", "$name blocked")
        val tab = main { browser.agentTab() }
        if (main { browser.ensureView(tab) } == null) return ToolOutcome("Error: the browser is not available right now. Open Codriod and try again.", "$name failed")
        val consoleBefore = tab.console.size
        return when (name) {
            "browser_open" -> {
                val url = args.optString("url").trim()
                require(Regex("^(https?|about|data):").containsMatchIn(url) || url.startsWith("localhost")) { "Use a full http(s) URL" }
                when (args.optString("device").lowercase()) {
                    "mobile" -> main { browser.changeMode(DeviceMode.Mobile) }
                    "tablet" -> main { browser.changeMode(DeviceMode.Tablet) }
                    "pc", "desktop" -> main { browser.changeMode(DeviceMode.PC) }
                }
                main { browser.open(tab, browser.resolveInput(url)) }
                waitLoaded(tab)
                val snap = parse(eval(tab, "__cdAgent.snapshot()"))
                ToolOutcome("Loaded ${tab.url}\nTitle: ${tab.title}\nDevice: ${browser.mode.label}" +
                    (snap?.optString("text")?.take(1500)?.let { "\n\nVisible text (start):\n$it" } ?: "") + errorsSince(tab, 0) +
                    "\n\nUse browser_snapshot for numbered elements.", "Opened ${shortUrl(tab.url)} · ${browser.mode.label}")
            }
            "browser_snapshot" -> {
                val snap = parse(eval(tab, "__cdAgent.snapshot()")) ?: return ToolOutcome("Error: could not read the page.", "Snapshot failed")
                val elements = snap.optJSONArray("elements") ?: JSONArray()
                val text = buildString {
                    appendLine("URL: ${snap.optString("url")}\nTitle: ${snap.optString("title")}")
                    snap.optJSONArray("scroll")?.let { appendLine("Scroll: ${it.optInt(0)} of ${it.optInt(1)} (viewport ${it.optInt(2)})") }
                    snap.optJSONArray("headings")?.let { h -> if (h.length() > 0) { appendLine("Headings:"); for (i in 0 until h.length()) appendLine("  " + h.getString(i)) } }
                    appendLine("Elements:")
                    for (i in 0 until elements.length()) elements.getJSONObject(i).let { e ->
                        append("  [${e.optInt("i")}] ${e.optString("tag")}")
                        e.optString("type").takeIf { it.isNotBlank() }?.let { append("($it)") }
                        append(" \"${e.optString("label")}\"")
                        e.optString("href").takeIf { it.isNotBlank() }?.let { append(" → $it") }
                        e.optString("value").takeIf { it.isNotBlank() }?.let { append(" value=\"$it\"") }
                        appendLine()
                    }
                    appendLine("Text:"); append(snap.optString("text"))
                }
                ToolOutcome(text, "Read ${shortUrl(snap.optString("url"))} · ${elements.length()} elements")
            }
            "browser_click", "browser_type" -> {
                val target = JSONObject().apply {
                    if (args.has("element")) put("index", args.optInt("element"))
                    args.optString("selector").takeIf { it.isNotBlank() }?.let { put("selector", it) }
                    if (name == "browser_click") args.optString("text").takeIf { it.isNotBlank() }?.let { put("text", it) }
                }
                require(target.length() > 0) { "Give element, selector or text" }
                val urlBefore = tab.url
                val raw = if (name == "browser_click") eval(tab, "__cdAgent.click($target)")
                    else eval(tab, "__cdAgent.type($target, ${JSONObject.quote(args.optString("text"))}, ${args.optBoolean("clear")}, ${args.optBoolean("submit")})")
                val result = parse(raw) ?: return ToolOutcome("Error: the page did not respond.", "$name failed")
                if (!result.optBoolean("ok")) return ToolOutcome("Error: ${result.optString("error")}", "$name: not found")
                delay(500)
                if (tab.progress < 100 || tab.url != urlBefore) waitLoaded(tab)
                val what = result.optString("clicked").ifBlank { result.optString("typed") }
                ToolOutcome("Done: $what.\nNow at ${tab.url} (${tab.title})" + errorsSince(tab, consoleBefore),
                    (if (name == "browser_click") "Click " else "Type ") + what.take(60))
            }
            "browser_scroll" -> {
                val r = parse(eval(tab, "__cdAgent.scroll(${JSONObject.quote(args.optString("direction", "down"))}, ${args.optInt("pixels", 0)})"))
                ToolOutcome("Scrolled to y=${r?.optInt("y")} of ${r?.optInt("height")}.", "Scroll ${args.optString("direction")}")
            }
            "browser_back" -> {
                main { browser.back(tab) }; waitLoaded(tab)
                ToolOutcome("Now at ${tab.url} (${tab.title})", "Back to ${shortUrl(tab.url)}")
            }
            "browser_eval" -> {
                val code = args.optString("code")
                val value = eval(tab, "(function(){ try { var v = (0, eval)(${JSONObject.quote(code)}); return typeof v === 'undefined' ? 'undefined' : JSON.stringify(v); } catch(e) { return 'Error: ' + e; } })()")
                val text = value?.let { runCatching { JSONArray("[$it]").getString(0) }.getOrDefault(it) } ?: "null"
                ToolOutcome(text.take(8000) + errorsSince(tab, consoleBefore), "Ran JavaScript", detail = "> ${code.take(300)}\n${text.take(2000)}")
            }
            "browser_console" -> {
                val logs = tab.console.takeLast(40)
                ToolOutcome(if (logs.isEmpty()) "No console messages." else logs.joinToString("\n") {
                    "${it.level}: ${it.text.take(400)}" + if (it.source.isNotBlank()) " (${it.source.substringAfterLast('/')}:${it.line})" else ""
                }, "Console · ${logs.count { it.level == LogLevel.Error }} errors")
            }
            "browser_network" -> {
                val raw = eval(tab, "(function(){ $DEVTOOLS_AGENT; return window.__cd.resources(); })()")
                val list = runCatching { JSONArray(raw) }.getOrNull() ?: JSONArray()
                val lines = (0 until list.length()).map { list.getJSONObject(it) }.joinToString("\n") {
                    "${it.optString("type")} ${if (it.optInt("status") == 0) "—" else it.optInt("status")} ${it.optLong("size")}B ${it.optDouble("duration").toInt()}ms ${it.optString("url")}"
                }
                val captured = tab.captured.takeLast(10).joinToString("\n") { "${it.type} ${it.method} ${it.status} ${it.url}" + (it.body?.let { b -> "\n  body: ${b.take(300)}" } ?: "") }
                ToolOutcome((lines.ifBlank { "No requests recorded." } + if (captured.isNotBlank()) "\n\nfetch/XHR:\n$captured" else "").take(12_000),
                    "Network · ${list.length()} requests")
            }
            "browser_screenshot" -> {
                delay(200)
                val bitmap = main { browser.capture(tab) } ?: return ToolOutcome("Error: could not capture the page.", "Screenshot failed")
                shotsDir.mkdirs()
                val file = File(shotsDir, "shot-${System.currentTimeMillis()}.jpg")
                withContext(Dispatchers.IO) { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) } }
                val size = "${bitmap.width}×${bitmap.height}"
                bitmap.recycle()
                onScreenshot(file.absolutePath)
                val canSee = vision()
                ToolOutcome(if (canSee) "Screenshot of ${tab.url} ($size, ${browser.mode.label}) is attached." else
                    "Screenshot saved ($size), but the selected model cannot see images. Use browser_snapshot to read the page.",
                    "Screenshot · ${browser.mode.label}", images = if (canSee) listOf(file.absolutePath) else emptyList(), detail = "IMAGE ${file.absolutePath}")
            }
            else -> ToolOutcome("Error: unknown tool $name", "Unknown tool")
        }
    }

    private fun parse(raw: String?): JSONObject? = runCatching { JSONObject(raw ?: return null) }.getOrNull()
    private fun shortUrl(url: String) = url.removePrefix("https://").removePrefix("http://").take(50)
}
