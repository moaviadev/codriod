package dev.moavia.codriod

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { CodriodScreen() } }
    }
}

@Composable
private fun CodriodScreen() {
    val context = LocalContext.current
    val manager = remember { SessionManager(context) }
    var backendName by remember { mutableStateOf(manager.activeBackend.displayName) }
    var terminalView by remember { mutableStateOf<TerminalView?>(null) }
    var status by remember { mutableStateOf("Local shell") }
    var terminalSession by remember { mutableStateOf<TerminalSession?>(null) }
    val ctrlLatched = remember { mutableStateOf(false) }

    val sessionClient = remember(context, terminalView) {
        object : TerminalSessionClient {
            override fun onTextChanged(changedSession: TerminalSession) {
                terminalView?.onScreenUpdated()
            }
            override fun onTitleChanged(changedSession: TerminalSession) {
                status = changedSession.title ?: "Local shell"
            }
            override fun onSessionFinished(finishedSession: TerminalSession) { status = "Process completed" }
            override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText("Codriod", text))
            }
            override fun onPasteTextFromClipboard(session: TerminalSession?) {
                val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                val text = clip?.getItemAt(0)?.coerceToText(context)?.toString()
                if (!text.isNullOrEmpty()) terminalSession?.write(text.toByteArray(Charsets.UTF_8), 0, text.toByteArray(Charsets.UTF_8).size)
            }
            override fun onBell(session: TerminalSession) {}
            override fun onColorsChanged(session: TerminalSession) { terminalView?.onScreenUpdated() }
            override fun onTerminalCursorStateChange(state: Boolean) {}
            override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
            override fun getTerminalCursorStyle(): Int? = null
            override fun logError(tag: String, message: String) {}
            override fun logWarn(tag: String, message: String) {}
            override fun logInfo(tag: String, message: String) {}
            override fun logDebug(tag: String, message: String) {}
            override fun logVerbose(tag: String, message: String) {}
            override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
            override fun logStackTrace(tag: String, e: Exception) {}
        }
    }

    val viewClient = remember {
        object : TerminalViewClient {
            override fun onScale(scale: Float) = 1f
            override fun onSingleTapUp(e: MotionEvent) {}
            override fun shouldBackButtonBeMappedToEscape() = false
            override fun shouldEnforceCharBasedInput() = true
            override fun shouldUseCtrlSpaceWorkaround() = false
            override fun isTerminalViewSelected() = true
            override fun copyModeChanged(copyMode: Boolean) {}
            override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession?) = false
            override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
            override fun onLongPress(event: MotionEvent) = false
            override fun readControlKey() = ctrlLatched.value
            override fun readAltKey() = false
            override fun readShiftKey() = false
            override fun readFnKey() = false
            override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
                if (ctrlDown && codePoint in 1..26) {
                    val data = byteArrayOf((codePoint - 64).toByte())
                    session?.write(data, 0, data.size)
                    return true
                }
                if (ctrlDown && codePoint in 65..90) {
                    val data = byteArrayOf((codePoint - 64).toByte())
                    session?.write(data, 0, data.size)
                    return true
                }
                if (ctrlDown && codePoint in 97..122) {
                    val data = byteArrayOf((codePoint - 96).toByte())
                    session?.write(data, 0, data.size)
                    return true
                }
                return false
            }
            override fun onEmulatorSet() {}
            override fun logError(tag: String, message: String) {}
            override fun logWarn(tag: String, message: String) {}
            override fun logInfo(tag: String, message: String) {}
            override fun logDebug(tag: String, message: String) {}
            override fun logVerbose(tag: String, message: String) {}
            override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
            override fun logStackTrace(tag: String, e: Exception) {}
        }
    }

    DisposableEffect(Unit) {
        onDispose { manager.terminate() }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF101010))) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(status, modifier = Modifier.padding(8.dp), fontFamily = FontFamily.Monospace, color = Color(0xFFB8FFB8))
            Text(manager.workingDirectory, modifier = Modifier.padding(8.dp), fontFamily = FontFamily.Monospace, color = Color(0xFF777777), maxLines = 1)
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    TerminalView(ctx, null).apply {
                        setTerminalViewClient(viewClient)
                        setTextSize(14)
                        isFocusable = true
                        isFocusableInTouchMode = true
                        keepScreenOn = true
                        terminalView = this
                        try {
                            val session = manager.create(backendName, viewClient, sessionClient)
                            attachSession(session)
                            terminalSession = session
                            requestFocus()
                        } catch (error: Exception) {
                            status = "Startup failed: ${error.message ?: error.javaClass.simpleName}"
                        }
                    }
                },
                update = { view ->
                    view.setTextSize(14)
                    manager.session?.let { view.attachSession(it) }
                }
            )
        }
        ExtraKeys(
            context = context,
            manager = manager,
            terminalView = terminalView,
            ctrlLatched = ctrlLatched.value,
            onCtrlChanged = { ctrlLatched.value = it }
        )
    }
}

@Composable
private fun ExtraKeys(
    context: android.content.Context,
    manager: SessionManager,
    terminalView: TerminalView?,
    ctrlLatched: Boolean,
    onCtrlChanged: (Boolean) -> Unit
) {
    val keys = listOf("ESC" to byteArrayOf(27), "TAB" to byteArrayOf(9), "CTRL" to null, "C" to byteArrayOf(3), "-" to byteArrayOf(45), "|" to byteArrayOf(124), "/" to byteArrayOf(47), "UP" to byteArrayOf(27,91,65), "DOWN" to byteArrayOf(27,91,66), "COPY" to null, "PASTE" to null)
    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(Color(0xFF202020)).padding(4.dp)) {
        keys.forEach { (label, bytes) ->
            TextButton(onClick = {
                when (label) {
                    "CTRL" -> onCtrlChanged(!ctrlLatched)
                    "C" -> {
                        if (ctrlLatched) {
                            manager.write(byteArrayOf(3))
                            onCtrlChanged(false)
                        } else {
                            manager.write(bytes ?: byteArrayOf())
                        }
                    }
                    "COPY" -> {
                        val selected = terminalView?.getSelectedText()
                        if (!selected.isNullOrEmpty()) {
                            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                                ClipData.newPlainText("Codriod", selected)
                            )
                            terminalView.stopTextSelectionMode()
                        }
                    }
                    "PASTE" -> {
                        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                        val text = clip?.getItemAt(0)?.coerceToText(context)?.toString()
                        if (!text.isNullOrEmpty()) manager.writeText(text)
                    }
                    else -> if (bytes != null) {
                        manager.write(bytes)
                        onCtrlChanged(false)
                    }
                }
            }, modifier = Modifier.padding(horizontal = 1.dp), colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFE0E0E0))) {
                Text(label, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
