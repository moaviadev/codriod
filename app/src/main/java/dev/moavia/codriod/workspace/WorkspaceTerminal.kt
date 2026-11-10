package dev.moavia.codriod.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.*
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import dev.moavia.codriod.SessionManager
import java.io.File

/** Reuses the terminal engine; workspace-specific commands and views live here. */
class WorkspaceTerminalController(private val context: Context, val root: File) :
    TerminalSessionClient, TerminalViewClient {
    private val manager = SessionManager(context)
    private var view: TerminalView? = null
    private var initialized = false
    private val pending = mutableListOf<String>()
    var ctrl by mutableStateOf(false)
    var status by mutableStateOf("Local shell")
        private set
    private var dark = true

    fun attach(terminal: TerminalView) {
        view = terminal
        try {
            val session = manager.session ?: manager.create("Local", this, this)
            terminal.attachSession(session)
            applyColors()
        } catch (error: Exception) { status = "Terminal failed: " + error.message }
    }

    fun detach() { view = null }

    /** Everything the terminal printed (scroll-back included), for the agent's @ Terminal context. */
    fun transcript(): String = runCatching { manager.session?.emulator?.screen?.transcriptText }.getOrNull().orEmpty()

    fun setTheme(darkTheme: Boolean) { dark = darkTheme; applyColors() }

    private fun applyColors() {
        manager.session?.emulator?.mColors?.let {
            it.tryParseColor(TextStyle.COLOR_INDEX_BACKGROUND, if (dark) "#1e1e1e" else "#ffffff")
            it.tryParseColor(TextStyle.COLOR_INDEX_FOREGROUND, if (dark) "#d4d4d4" else "#333333")
            it.tryParseColor(TextStyle.COLOR_INDEX_CURSOR, "#007acc")
        }
        view?.onScreenUpdated()
    }

    fun execute(command: String) {
        if (initialized && manager.session?.isRunning == true) manager.writeText(command)
        else if (manager.session != null && initialized) {
            status = "Shell exited. Restart terminal to run another command."
        } else pending.add(command)
    }

    fun key(bytes: ByteArray) { manager.write(bytes); ctrl = false }
    fun restart() {
        manager.terminate()
        initialized = false
        pending.clear()
        status = "Local shell"
        view?.let(::attach)
    }
    fun close() { manager.terminate(); pending.clear(); initialized = false; view = null }

    fun copy() {
        val text = view?.selectedText
        if (text.isNullOrEmpty()) { status = "Long-press terminal text to select it"; return }
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.setPrimaryClip(ClipData.newPlainText("Codriod terminal", text))
        view?.stopTextSelectionMode()
    }
    fun paste() {
        val text = (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
        // Respect bracketed-paste mode instead of simulating typed commands.
        manager.session?.emulator?.paste(text)
    }
    override fun onEmulatorSet() {
        applyColors()
        if (!initialized) {
            initialized = true
            manager.writeText("cd " + shellQuote(root.absolutePath) + "\n")
            pending.toList().forEach(manager::writeText)
            pending.clear()
        }
    }
    override fun onTextChanged(changedSession: TerminalSession) { view?.onScreenUpdated() }
    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) {
        status = "Exited (${finishedSession.exitStatus}) · Restart to continue"
    }
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Codriod", text))
    }
    override fun onPasteTextFromClipboard(session: TerminalSession?) = paste()
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) { view?.onScreenUpdated() }
    override fun onTerminalCursorStateChange(state: Boolean) { view?.invalidate() }
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
    override fun getTerminalCursorStyle(): Int? = null
    override fun onScale(scale: Float) = 1f
    override fun onSingleTapUp(e: MotionEvent) {
        view?.let {
            it.requestFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(it, InputMethodManager.SHOW_IMPLICIT)
        }
    }
    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = true
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession?) = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
    override fun onLongPress(event: MotionEvent) = false
    override fun readControlKey() = ctrl
    override fun readAltKey() = false
    override fun readShiftKey() = false
    override fun readFnKey() = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
        ctrl = false
        return false // Let TerminalView perform its normal control-code translation.
    }
    override fun logError(tag: String, message: String) { Log.e(tag, message); status = message }
    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, e.message, e) }
}

@Composable
fun WorkspaceTerminal(controller: WorkspaceTerminalController, modifier: Modifier = Modifier) {
    val dark = LocalCodeColors.current.dark
    val textSize = with(LocalDensity.current) { 13.sp.toPx().toInt() }
    DisposableEffect(controller) { onDispose { controller.detach() } }
    Column(modifier.background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(controller.status, Modifier.weight(1f).padding(8.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1)
            TextButton(onClick = controller::restart, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Restart") }
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { context ->
                TerminalView(context, null).apply {
                    setTerminalViewClient(controller)
                    setTextSize(textSize)
                    isFocusableInTouchMode = true
                    controller.setTheme(dark)
                    controller.attach(this)
                }
            },
            update = { controller.setTheme(dark) }
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(MaterialTheme.colorScheme.surface)) {
            listOf("ESC", "CTRL", "TAB", "Ctrl+C", "↑", "↓", "←", "→", "{", "}", "[", "]", "(", ")", "|", "\\", "/", "_", "-", "=", ":", "'", "\"", "COPY", "PASTE").forEach { key ->
                TextButton(onClick = {
                    when (key) {
                        "CTRL" -> controller.ctrl = !controller.ctrl
                        "ESC" -> controller.key(byteArrayOf(27))
                        "TAB" -> controller.key(byteArrayOf(9))
                        "Ctrl+C" -> controller.key(byteArrayOf(3))
                        "↑" -> controller.key(byteArrayOf(27, 91, 65))
                        "↓" -> controller.key(byteArrayOf(27, 91, 66))
                        "←" -> controller.key(byteArrayOf(27, 91, 68))
                        "→" -> controller.key(byteArrayOf(27, 91, 67))
                        "COPY" -> controller.copy()
                        "PASTE" -> controller.paste()
                        else -> controller.key(key.toByteArray(Charsets.UTF_8))
                    }
                }, modifier = Modifier.widthIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (key == "CTRL" && controller.ctrl) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface),
                    contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(key, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        }
    }
}
