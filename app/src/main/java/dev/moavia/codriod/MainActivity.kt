package dev.moavia.codriod

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import dev.moavia.codriod.workspace.ToolGlyph
import dev.moavia.codriod.workspace.ToolIcon
import dev.moavia.codriod.workspace.WorkspaceActivity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF0C0C0C),
                    surface = Color(0xFF141414),
                    primary = Color(0xFF00B0FF),
                    onBackground = Color.White,
                    onSurface = Color.White
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0C0C0C)
                ) {
                    CodriodProTerminalScreen(
                        onBack = { finish() },
                        onOpenWorkspace = { finish() }
                    )
                }
            }
        }
    }
}

data class TerminalTabModel(
    val id: Int,
    var title: String,
    val session: TerminalSession
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodriodProTerminalScreen(
    onBack: () -> Unit,
    onOpenWorkspace: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var terminalView by remember { mutableStateOf<TerminalView?>(null) }
    var activeTabId by remember { mutableIntStateOf(1) }
    var nextTabId by remember { mutableIntStateOf(2) }
    val tabs = remember { mutableStateListOf<TerminalTabModel>() }
    val ctrlLatched = remember { mutableStateOf(false) }
    var isFullscreen by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showRunDialog by remember { mutableStateOf(false) }
    var showHistoryDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }

    // Balanced, comfortable terminal font size: 12sp scaled to px
    var textSizeSp by remember { mutableIntStateOf(12) }
    val textSizePx = with(density) { textSizeSp.sp.toPx().toInt() }

    var currentThemeName by remember { mutableStateOf("Obsidian Dark") }

    // Dynamic keyboard / IME height awareness: keeps helper buttons directly on top of keyboard
    val isImeVisible = WindowInsets.ime.getBottom(density) > 0

    BackHandler {
        onBack()
    }

    val commandHistory = remember {
        mutableStateListOf(
            "pwd",
            "ls",
            "echo \"Codriod is Awesome!\"",
            "git status",
            "cat README.md",
            "whoami",
            "clear"
        )
    }

    // Reliable keyboard opener that works across all Android versions
    fun openKeyboard() {
        terminalView?.let { view ->
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            view.requestFocus()
            val activity = context as? ComponentActivity
            activity?.let { act ->
                WindowCompat.getInsetsController(act.window, act.window.decorView)
                    .show(WindowInsetsCompat.Type.ime())
            }
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(view, InputMethodManager.SHOW_FORCED)
        }
    }

    fun toggleKeyboard() {
        terminalView?.let { view ->
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            view.requestFocus()
            val activity = context as? ComponentActivity
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            if (isImeVisible) {
                activity?.let { act ->
                    WindowCompat.getInsetsController(act.window, act.window.decorView)
                        .hide(WindowInsetsCompat.Type.ime())
                }
                imm?.hideSoftInputFromWindow(view.windowToken, 0)
            } else {
                activity?.let { act ->
                    WindowCompat.getInsetsController(act.window, act.window.decorView)
                        .show(WindowInsetsCompat.Type.ime())
                }
                imm?.showSoftInput(view, InputMethodManager.SHOW_FORCED)
            }
        }
    }

    val sessionClient = remember(context) {
        object : TerminalSessionClient {
            override fun onTextChanged(changedSession: TerminalSession) {
                terminalView?.onScreenUpdated()
            }
            override fun onTitleChanged(changedSession: TerminalSession) {
                val index = tabs.indexOfFirst { it.session == changedSession }
                if (index != -1 && !changedSession.title.isNullOrEmpty()) {
                    tabs[index] = tabs[index].copy(title = changedSession.title ?: tabs[index].title)
                }
            }
            override fun onSessionFinished(finishedSession: TerminalSession) {
                terminalView?.onScreenUpdated()
            }
            override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("Codriod", text))
                android.widget.Toast.makeText(context, "Copied to clipboard!", android.widget.Toast.LENGTH_SHORT).show()
            }
            override fun onPasteTextFromClipboard(session: TerminalSession?) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = clipboard?.primaryClip
                val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0)?.coerceToText(context)?.toString() else null
                if (!text.isNullOrEmpty()) {
                    if (session?.emulator != null) {
                        session.emulator.paste(text)
                    } else {
                        val formatted = text.replace("\r?\n".toRegex(), "\r")
                        session?.write(formatted.toByteArray(Charsets.UTF_8), 0, formatted.length)
                    }
                    android.widget.Toast.makeText(context, "Pasted!", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            override fun onBell(session: TerminalSession) {}
            override fun onColorsChanged(session: TerminalSession) {
                terminalView?.onScreenUpdated()
            }
            override fun onTerminalCursorStateChange(state: Boolean) {
                terminalView?.invalidate()
            }
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
            override fun onSingleTapUp(e: MotionEvent) {
                openKeyboard()
            }
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
                if (ctrlDown) {
                    ctrlLatched.value = false
                    val byteVal = when (codePoint) {
                        in 65..90 -> (codePoint - 64).toByte()
                        in 97..122 -> (codePoint - 96).toByte()
                        in 1..26 -> codePoint.toByte()
                        else -> null
                    }
                    if (byteVal != null) {
                        val data = byteArrayOf(byteVal)
                        session?.write(data, 0, data.size)
                        return true
                    }
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

    fun applyThemeColors(session: TerminalSession) {
        val colors = session.emulator?.mColors ?: return
        when (currentThemeName) {
            "Matrix Green" -> {
                colors.tryParseColor(TextStyle.COLOR_INDEX_BACKGROUND, "#051105")
                colors.tryParseColor(TextStyle.COLOR_INDEX_FOREGROUND, "#00FF66")
                colors.tryParseColor(TextStyle.COLOR_INDEX_CURSOR, "#00FF66")
                colors.tryParseColor(2, "#00E676")
                colors.tryParseColor(10, "#00FF66")
                colors.tryParseColor(4, "#00E5FF")
                colors.tryParseColor(12, "#18FFFF")
            }
            "VS Code Dark" -> {
                colors.tryParseColor(TextStyle.COLOR_INDEX_BACKGROUND, "#1E1E1E")
                colors.tryParseColor(TextStyle.COLOR_INDEX_FOREGROUND, "#D4D4D4")
                colors.tryParseColor(TextStyle.COLOR_INDEX_CURSOR, "#007ACC")
                colors.tryParseColor(2, "#4EC9B0")
                colors.tryParseColor(10, "#B5CEA8")
                colors.tryParseColor(4, "#007ACC")
                colors.tryParseColor(12, "#9CDCFE")
            }
            else -> { // Obsidian Dark (screenshot style)
                colors.tryParseColor(TextStyle.COLOR_INDEX_BACKGROUND, "#0C0C0C")
                colors.tryParseColor(TextStyle.COLOR_INDEX_FOREGROUND, "#FFFFFF")
                colors.tryParseColor(TextStyle.COLOR_INDEX_CURSOR, "#FFFFFF")
                colors.tryParseColor(2, "#00E676")
                colors.tryParseColor(10, "#00FF66")
                colors.tryParseColor(4, "#00B0FF")
                colors.tryParseColor(12, "#40C4FF")
                colors.tryParseColor(3, "#FFD600")
                colors.tryParseColor(11, "#FFFF00")
                colors.tryParseColor(1, "#FF5252")
                colors.tryParseColor(9, "#FF1744")
                colors.tryParseColor(6, "#00E5FF")
                colors.tryParseColor(14, "#18FFFF")
            }
        }
        terminalView?.onScreenUpdated()
    }

    fun createNewSession(tabTitle: String? = null): TerminalSession {
        val backend = LocalShellBackend(context)
        val session = backend.createSession(viewClient, sessionClient)
        applyThemeColors(session)
        return session
    }

    // Initialize initial tab
    LaunchedEffect(Unit) {
        if (tabs.isEmpty()) {
            val session = createNewSession("Terminal 1")
            val firstTab = TerminalTabModel(id = 1, title = "Terminal 1", session = session)
            tabs.add(firstTab)
            activeTabId = 1
        }
    }

    val activeTab = tabs.find { it.id == activeTabId } ?: tabs.firstOrNull()

    fun sendCommand(cmd: String) {
        val session = activeTab?.session ?: return
        val normalized = if (cmd.endsWith("\n")) cmd else "$cmd\n"
        session.write(normalized.toByteArray(Charsets.UTF_8), 0, normalized.length)
        val cleanCmd = cmd.trim()
        if (cleanCmd.isNotEmpty() && !commandHistory.contains(cleanCmd)) {
            commandHistory.add(0, cleanCmd)
        }
    }

    fun sendBytes(bytes: ByteArray) {
        val session = activeTab?.session ?: return
        session.write(bytes, 0, bytes.size)
        ctrlLatched.value = false
    }

    fun copyFromTerminal() {
        val view = terminalView
        val selectedText = view?.selectedText
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val textToCopy = if (!selectedText.isNullOrBlank()) {
            selectedText
        } else {
            val session = activeTab?.session
            val emulator = session?.emulator
            if (emulator != null) {
                val screen = emulator.screen
                val endRow = emulator.cursorRow.coerceAtLeast(0)
                screen.getSelectedText(0, 0, emulator.columns - 1, endRow).trimEnd()
            } else ""
        }

        if (!textToCopy.isNullOrBlank()) {
            clipboard?.setPrimaryClip(ClipData.newPlainText("Codriod", textToCopy))
            view?.stopTextSelectionMode()
            android.widget.Toast.makeText(context, "Copied to clipboard!", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            android.widget.Toast.makeText(context, "Long-press text to select or run a command first", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun pasteFromClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = clipboard?.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0)?.coerceToText(context)?.toString() else null
        if (!text.isNullOrEmpty()) {
            val session = activeTab?.session
            if (session?.emulator != null) {
                session.emulator.paste(text)
            } else {
                val formatted = text.replace("\r?\n".toRegex(), "\r")
                session?.write(formatted.toByteArray(Charsets.UTF_8), 0, formatted.length)
            }
            android.widget.Toast.makeText(context, "Pasted!", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            android.widget.Toast.makeText(context, "Clipboard is empty", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun addNewTab() {
        val id = nextTabId
        nextTabId++
        val session = createNewSession("Terminal $id")
        val newTab = TerminalTabModel(id = id, title = "Terminal $id", session = session)
        tabs.add(newTab)
        activeTabId = id
        terminalView?.attachSession(session)
        applyThemeColors(session)
    }

    fun closeTab(tabToClose: TerminalTabModel) {
        if (tabs.size > 1) {
            val isCurrent = tabToClose.id == activeTabId
            val closeIdx = tabs.indexOfFirst { it.id == tabToClose.id }
            tabToClose.session.finishIfRunning()
            tabs.remove(tabToClose)
            if (isCurrent) {
                val newActive = tabs.getOrNull(closeIdx.coerceAtMost(tabs.size - 1)) ?: tabs.first()
                activeTabId = newActive.id
                terminalView?.attachSession(newActive.session)
                applyThemeColors(newActive.session)
            }
        } else {
            // Only 1 tab left: restart it
            tabToClose.session.finishIfRunning()
            val newSession = createNewSession(tabToClose.title)
            val updated = tabToClose.copy(session = newSession)
            tabs[0] = updated
            terminalView?.attachSession(newSession)
            applyThemeColors(newSession)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0C0C0C))
            .windowInsetsPadding(WindowInsets.statusBars)
            .then(
                if (isImeVisible) {
                    Modifier.windowInsetsPadding(WindowInsets.ime)
                } else {
                    Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                }
            )
    ) {
        // TOP HEADER BAR (Exact match to screenshot + Back navigation to home)
        if (!isFullscreen) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .background(Color(0xFF0C0C0C))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Back button to return to home screen
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(32.dp)
                ) {
                    ToolGlyph(ToolIcon.Back, tint = Color.White, modifier = Modifier.size(18.dp))
                }

                Spacer(Modifier.width(4.dp))

                // Purple / Magenta < / > Icon Logo
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF9C27B0), Color(0xFFE040FB))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    ToolGlyph(ToolIcon.CodeBrackets, tint = Color.White, modifier = Modifier.size(17.dp))
                }

                Spacer(Modifier.width(8.dp))

                // App Title and Subtitle
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Codriod",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.2.sp
                        )
                        Spacer(Modifier.width(8.dp))
                        // "Active" pill indicator
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF132819))
                                .border(1.dp, Color(0xFF1B5E20), RoundedCornerShape(12.dp))
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF00E676))
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "Active",
                                color = Color(0xFF00E676),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    Text(
                        text = "Pro Android Terminal",
                        color = Color(0xFF9E9E9E),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Normal
                    )
                }

                // Top Right Action Icons
                // 1. Folder icon (returns to Workspace/Projects)
                IconButton(
                    onClick = onOpenWorkspace,
                    modifier = Modifier.size(34.dp)
                ) {
                    ToolGlyph(ToolIcon.Folder, tint = Color(0xFFCCCCCC), modifier = Modifier.size(19.dp))
                }

                // 2. Fullscreen icon [ ]
                IconButton(
                    onClick = { isFullscreen = !isFullscreen },
                    modifier = Modifier.size(34.dp)
                ) {
                    ToolGlyph(ToolIcon.Fullscreen, tint = Color(0xFFCCCCCC), modifier = Modifier.size(18.dp))
                }

                // 3. Settings gear icon
                IconButton(
                    onClick = { showSettingsDialog = true },
                    modifier = Modifier.size(34.dp)
                ) {
                    ToolGlyph(ToolIcon.Settings, tint = Color(0xFFCCCCCC), modifier = Modifier.size(19.dp))
                }

                // 4. More Options Overflow Menu
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(34.dp)
                    ) {
                        ToolGlyph(ToolIcon.More, tint = Color(0xFFCCCCCC), modifier = Modifier.size(19.dp))
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(Color(0xFF1E1E1E))
                    ) {
                        DropdownMenuItem(
                            text = { Text("New Terminal Tab", color = Color.White) },
                            onClick = {
                                addNewTab()
                                showMenu = false
                            },
                            leadingIcon = { ToolGlyph(ToolIcon.Plus, tint = Color(0xFF00B0FF)) }
                        )
                        DropdownMenuItem(
                            text = { Text("Clear Terminal", color = Color.White) },
                            onClick = {
                                sendCommand("clear")
                                showMenu = false
                            },
                            leadingIcon = { ToolGlyph(ToolIcon.Clear, tint = Color(0xFFFFB74D)) }
                        )
                        DropdownMenuItem(
                            text = { Text("Quick Run Presets", color = Color.White) },
                            onClick = {
                                showRunDialog = true
                                showMenu = false
                            },
                            leadingIcon = { ToolGlyph(ToolIcon.Run, tint = Color(0xFF00E676)) }
                        )
                        DropdownMenuItem(
                            text = { Text("Command History", color = Color.White) },
                            onClick = {
                                showHistoryDialog = true
                                showMenu = false
                            },
                            leadingIcon = { ToolGlyph(ToolIcon.History, tint = Color(0xFF81D4FA)) }
                        )
                        HorizontalDivider(color = Color(0xFF333333))
                        DropdownMenuItem(
                            text = { Text("Back to Home", color = Color.White) },
                            onClick = {
                                showMenu = false
                                onBack()
                            },
                            leadingIcon = { ToolGlyph(ToolIcon.Back, tint = Color(0xFF00B0FF)) }
                        )
                        DropdownMenuItem(
                            text = { Text("About Codriod Pro", color = Color.White) },
                            onClick = {
                                showAboutDialog = true
                                showMenu = false
                            },
                            leadingIcon = { ToolGlyph(ToolIcon.Terminal, tint = Color(0xFFCCCCCC)) }
                        )
                    }
                }
            }
        }

        // TABS BAR (Terminal 1, Terminal 2... + button)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0C0C0C))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEach { tab ->
                val isSelected = tab.id == activeTabId
                Row(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (isSelected) Color(0xFF14202B) else Color(0xFF161616))
                        .border(
                            1.dp,
                            if (isSelected) Color(0xFF00B0FF) else Color(0xFF2E2E2E),
                            RoundedCornerShape(7.dp)
                        )
                        .clickable {
                            activeTabId = tab.id
                            terminalView?.attachSession(tab.session)
                            applyThemeColors(tab.session)
                            openKeyboard()
                        }
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ToolGlyph(
                        ToolIcon.Terminal,
                        tint = if (isSelected) Color(0xFF00B0FF) else Color(0xFF888888),
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = tab.title,
                        color = if (isSelected) Color.White else Color(0xFF9E9E9E),
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                    )
                    Spacer(Modifier.width(7.dp))
                    Box(
                        modifier = Modifier
                            .size(15.dp)
                            .clickable { closeTab(tab) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "×",
                            color = Color(0xFF888888),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Plus Button to add a new session tab
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF181818))
                    .border(1.dp, Color(0xFF333333), RoundedCornerShape(6.dp))
                    .clickable { addNewTab() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "+",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (isFullscreen) {
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = { isFullscreen = false },
                    modifier = Modifier.size(26.dp)
                ) {
                    ToolGlyph(ToolIcon.Fullscreen, tint = Color.White, modifier = Modifier.size(15.dp))
                }
            }
        }

        // TERMINAL OUTPUT VIEWPORT (Pure black, high contrast colors, tap opens keyboard)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF0C0C0C))
                .clickable { openKeyboard() }
                .padding(horizontal = 6.dp)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    TerminalView(ctx, null).apply {
                        setTerminalViewClient(viewClient)
                        setTextSize(textSizePx)
                        isFocusable = true
                        isFocusableInTouchMode = true
                        keepScreenOn = true
                        terminalView = this

                        activeTab?.session?.let { currentSession ->
                            attachSession(currentSession)
                            applyThemeColors(currentSession)
                        }
                        post {
                            requestFocus()
                        }
                    }
                },
                update = { view ->
                    view.setTextSize(textSizePx)
                    activeTab?.session?.let { currentSession ->
                        if (view.mEmulator == null || view.mTermSession != currentSession) {
                            view.attachSession(currentSession)
                            applyThemeColors(currentSession)
                        }
                    }
                }
            )
        }

        // ACTION TOOLBAR (Run, Copy, Paste, Clear, History, Keyboard)
        // Positioned immediately above the Special Keys Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF101010))
                .border(width = 1.dp, color = Color(0xFF222222))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ▷ Run button
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color(0xFF181818))
                    .border(1.dp, Color(0xFF2E2E2E), RoundedCornerShape(7.dp))
                    .clickable { showRunDialog = true }
                    .padding(horizontal = 9.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "▷",
                    color = Color(0xFF00E676),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "Run",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.width(5.dp))

            // 📋 Copy button
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color(0xFF181818))
                    .border(1.dp, Color(0xFF2E2E2E), RoundedCornerShape(7.dp))
                    .clickable { copyFromTerminal() }
                    .padding(horizontal = 9.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ToolGlyph(ToolIcon.Copy, tint = Color(0xFF81D4FA), modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "Copy",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.width(5.dp))

            // 📋 Paste button
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color(0xFF181818))
                    .border(1.dp, Color(0xFF2E2E2E), RoundedCornerShape(7.dp))
                    .clickable { pasteFromClipboard() }
                    .padding(horizontal = 9.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ToolGlyph(ToolIcon.Paste, tint = Color(0xFF00B0FF), modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "Paste",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.width(6.dp))

            // ⌃ Clear button
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color(0xFF181818))
                    .border(1.dp, Color(0xFF2E2E2E), RoundedCornerShape(7.dp))
                    .clickable { sendCommand("clear") }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "⌃",
                    color = Color(0xFFFFB74D),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "Clear",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.width(6.dp))

            // 🕒 History button
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color(0xFF181818))
                    .border(1.dp, Color(0xFF2E2E2E), RoundedCornerShape(7.dp))
                    .clickable { showHistoryDialog = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ToolGlyph(ToolIcon.History, tint = Color(0xFF81D4FA), modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "History",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.weight(1f))

            // ⌨ Soft Keyboard toggle button on right (always directly accessible above keyboard)
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (isImeVisible) Color(0xFF0E304F) else Color(0xFF181818))
                    .border(
                        1.dp,
                        if (isImeVisible) Color(0xFF00B0FF) else Color(0xFF2E2E2E),
                        RoundedCornerShape(7.dp)
                    )
                    .clickable { toggleKeyboard() },
                contentAlignment = Alignment.Center
            ) {
                ToolGlyph(
                    ToolIcon.Keyboard,
                    tint = if (isImeVisible) Color(0xFF00B0FF) else Color(0xFFCCCCCC),
                    modifier = Modifier.size(17.dp)
                )
            }
        }

        // SPECIAL KEYS TOOLBAR (Ctrl, Esc, Tab, ↑, ↓, ←, →, Del)
        // Positioned at the very bottom edge of the view, sitting directly flush above the Android keyboard
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0C0C0C))
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Ctrl Key (latched blue button)
            SpecialKeyButton(
                label = "Ctrl",
                textColor = Color(0xFF00B0FF),
                isHighlighted = ctrlLatched.value,
                modifier = Modifier.weight(1f).padding(horizontal = 2.dp)
            ) {
                ctrlLatched.value = !ctrlLatched.value
            }

            // Esc Key
            SpecialKeyButton(
                label = "Esc",
                textColor = Color(0xFFE0E0E0),
                modifier = Modifier.weight(1f).padding(horizontal = 2.dp)
            ) {
                sendBytes(byteArrayOf(27))
            }

            // Tab Key
            SpecialKeyButton(
                label = "Tab",
                textColor = Color(0xFFE0E0E0),
                modifier = Modifier.weight(1f).padding(horizontal = 2.dp)
            ) {
                sendBytes(byteArrayOf(9))
            }

            // ↑ Arrow Key
            SpecialKeyButton(
                label = "↑",
                textColor = Color(0xFFE0E0E0),
                modifier = Modifier.weight(1f).padding(horizontal = 2.dp)
            ) {
                sendBytes(byteArrayOf(27, 91, 65))
            }

            // ↓ Arrow Key
            SpecialKeyButton(
                label = "↓",
                textColor = Color(0xFFE0E0E0),
                modifier = Modifier.weight(1f).padding(horizontal = 2.dp)
            ) {
                sendBytes(byteArrayOf(27, 91, 66))
            }

            // ← Arrow Key
            SpecialKeyButton(
                label = "←",
                textColor = Color(0xFFE0E0E0),
                modifier = Modifier.weight(1f).padding(horizontal = 2.dp)
            ) {
                sendBytes(byteArrayOf(27, 91, 68))
            }

            // → Arrow Key
            SpecialKeyButton(
                label = "→",
                textColor = Color(0xFFE0E0E0),
                modifier = Modifier.weight(1f).padding(horizontal = 2.dp)
            ) {
                sendBytes(byteArrayOf(27, 91, 67))
            }

            // Del Key (Red text)
            SpecialKeyButton(
                label = "Del",
                textColor = Color(0xFFFF5252),
                modifier = Modifier.weight(1f).padding(horizontal = 2.dp)
            ) {
                sendBytes(byteArrayOf(127))
            }
        }
    }

    // RUN QUICK COMMAND DIALOG
    if (showRunDialog) {
        var inputCommand by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showRunDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("▷", color = Color(0xFF00E676), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Text("Execute Command", color = Color.White, fontSize = 17.sp)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = inputCommand,
                        onValueChange = { inputCommand = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("echo 'Hello World'") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF00B0FF),
                            unfocusedBorderColor = Color(0xFF444444)
                        )
                    )

                    Text("PRESET COMMANDS", fontSize = 10.sp, color = Color(0xFF888888), fontWeight = FontWeight.Bold)

                    val presets = listOf(
                        "pwd" to "Print working directory",
                        "ls" to "List files and folders",
                        "echo \"Codriod is Awesome!\"" to "Print greeting",
                        "git status" to "Check git repository status",
                        "cat README.md" to "View project readme",
                        "uname -a" to "System kernel & architecture",
                        "./gradlew tasks" to "View gradle tasks"
                    )

                    LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                        items(presets) { (cmd, desc) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        sendCommand(cmd)
                                        showRunDialog = false
                                    }
                                    .padding(vertical = 7.dp, horizontal = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = cmd,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF00E676),
                                    fontSize = 12.sp
                                )
                                Text(
                                    text = desc,
                                    color = Color(0xFF888888),
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (inputCommand.isNotBlank()) {
                            sendCommand(inputCommand)
                        }
                        showRunDialog = false
                    }
                ) {
                    Text("Run", color = Color(0xFF00E676), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRunDialog = false }) {
                    Text("Cancel", color = Color(0xFF888888))
                }
            },
            containerColor = Color(0xFF1E1E1E)
        )
    }

    // COMMAND HISTORY DIALOG
    if (showHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showHistoryDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ToolGlyph(ToolIcon.History, tint = Color(0xFF81D4FA), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Command History", color = Color.White, fontSize = 17.sp)
                }
            },
            text = {
                if (commandHistory.isEmpty()) {
                    Text("No command history yet.", color = Color(0xFF888888))
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                        items(commandHistory) { cmd ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        sendCommand(cmd)
                                        showHistoryDialog = false
                                    }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "$",
                                    color = Color(0xFF00B0FF),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = cmd,
                                    color = Color.White,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showHistoryDialog = false }) {
                    Text("Close", color = Color(0xFF00B0FF))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        commandHistory.clear()
                    }
                ) {
                    Text("Clear All", color = Color(0xFFFF5252))
                }
            },
            containerColor = Color(0xFF1E1E1E)
        )
    }

    // SETTINGS DIALOG (with fine-grained text size control)
    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ToolGlyph(ToolIcon.Settings, tint = Color(0xFFCCCCCC), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Terminal Settings", color = Color.White, fontSize = 17.sp)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    // Font Size Controller
                    Column {
                        Text(
                            "FONT SIZE: ${textSizeSp}sp (${textSizePx}px)",
                            fontSize = 11.sp,
                            color = Color(0xFF888888),
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = { textSizeSp = (textSizeSp - 1).coerceAtLeast(9) },
                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF2C2C2C))
                            ) {
                                Text("A- Smaller", color = Color.White, fontSize = 12.sp)
                            }
                            FilledTonalButton(
                                onClick = { textSizeSp = (textSizeSp + 1).coerceAtMost(20) },
                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF2C2C2C))
                            ) {
                                Text("A+ Bigger", color = Color.White, fontSize = 12.sp)
                            }
                        }
                    }

                    // Color Theme Selector
                    Column {
                        Text("COLOR THEME", fontSize = 11.sp, color = Color(0xFF888888), fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        listOf("Obsidian Dark", "VS Code Dark", "Matrix Green").forEach { theme ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        currentThemeName = theme
                                        activeTab?.session?.let { applyThemeColors(it) }
                                    }
                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(theme, color = if (currentThemeName == theme) Color(0xFF00B0FF) else Color.White, fontSize = 13.sp)
                                if (currentThemeName == theme) {
                                    Text("✓", color = Color(0xFF00B0FF), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    // Shell Info
                    Column {
                        Text("SHELL ENVIRONMENT", fontSize = 11.sp, color = Color(0xFF888888), fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text("Binary: /system/bin/sh (local)", fontSize = 11.sp, color = Color(0xFFBBBBBB))
                        Text("Directory: ~/projects/Codriod", fontSize = 11.sp, color = Color(0xFFBBBBBB))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("Done", color = Color(0xFF00B0FF), fontWeight = FontWeight.Bold)
                }
            },
            containerColor = Color(0xFF1E1E1E)
        )
    }

    // ABOUT DIALOG
    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Brush.linearGradient(listOf(Color(0xFF9C27B0), Color(0xFFE040FB)))),
                        contentAlignment = Alignment.Center
                    ) {
                        ToolGlyph(ToolIcon.CodeBrackets, tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("About Codriod Pro", color = Color.White, fontSize = 17.sp)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Codriod Pro Android Terminal v0.1.0", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                    Text("Professional terminal workspace and developer environment running on Android.", color = Color(0xFFCCCCCC), fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("• Multi-session tabs with Termux backend\n• Direct shell access & ANSI color rendering\n• Quick Run, Paste, Clear & History toolbars\n• Coding keys (Ctrl, Esc, Tab, arrows, Del)", color = Color(0xFF9E9E9E), fontSize = 11.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text("OK", color = Color(0xFF00B0FF))
                }
            },
            containerColor = Color(0xFF1E1E1E)
        )
    }
}

@Composable
private fun SpecialKeyButton(
    label: String,
    textColor: Color,
    modifier: Modifier = Modifier,
    isHighlighted: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (isHighlighted) Color(0xFF0E304F) else Color(0xFF181818))
            .border(
                1.dp,
                if (isHighlighted) Color(0xFF00B0FF) else Color(0xFF262626),
                RoundedCornerShape(6.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
