package dev.moavia.codriod.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import dev.moavia.codriod.LocalShellBackend
import dev.moavia.codriod.RemoteProfile
import dev.moavia.codriod.RemoteProfileStore
import dev.moavia.codriod.RemoteShellPreset
import dev.moavia.codriod.RemoteStatus
import dev.moavia.codriod.SshSecret
import dev.moavia.codriod.SshTerminalBackend
import dev.moavia.codriod.TerminalExecutionBackend
import dev.moavia.codriod.git.CodriodHostKeys
import dev.moavia.codriod.git.UnknownHostKey
import dev.moavia.codriod.validateRemoteProfile
import java.io.File

/** One terminal session shown as a tab. [backend] is non-null for remote SSH sessions. */
class TerminalTab(
    val id: Int,
    title: String,
    val kind: String,
    var session: TerminalSession,
    val backend: SshTerminalBackend? = null
) {
    var title by mutableStateOf(title)
    val isRemote get() = backend != null || kind.startsWith("ssh:")
}

data class ToolAvailability(val name: String, val available: Boolean, val detail: String)

data class TerminalEnvironment(
    val platform: String,
    val shell: String,
    val home: String,
    val tools: List<ToolAvailability>
)

/**
 * Terminal controller for the workspace. Owns multiple real sessions (Android local shell, the
 * optional Ubuntu runtime and remote SSH shells), their tabs, split pane and keyboard state.
 */
class WorkspaceTerminalController(
    private val context: Context,
    val root: File,
    /** Creates the Ubuntu backend when Linux is installed; null hides the option. */
    private val ubuntu: (() -> TerminalExecutionBackend?)? = null
) : TerminalSessionClient, TerminalViewClient {
    private val profileStore = RemoteProfileStore(context)
    private val hostKeys = CodriodHostKeys(SshTerminalBackend.knownHostsFile(context))
    private val prefs = context.getSharedPreferences("terminal", Context.MODE_PRIVATE)

    private val tabs = mutableStateListOf<TerminalTab>()
    private var nextId = 1
    var activeIndex by mutableIntStateOf(0)
        private set
    var split by mutableStateOf(false)
        private set
    var splitIndex by mutableIntStateOf(1)
        private set
    var maximized by mutableStateOf(false)
        private set
    /** The optional arrow-key row starts hidden and only changes UI height, never terminal input. */
    var showArrowKeys by mutableStateOf(false)
        private set

    val activeTab: TerminalTab? get() = tabs.getOrNull(activeIndex)
    /** Human-readable shell/profile for the full-screen header. */
    val activeProfileLabel: String
        get() {
            val tab = activeTab ?: return "Local Shell"
            return when {
                tab.backend != null -> tab.backend.displayName
                tab.kind == "ubuntu" -> "Linux"
                else -> "Local Shell"
            }
        }
    /** The actual shell binary behind the active tab, shown next to the profile name. */
    val activeShellName: String
        get() {
            val tab = activeTab ?: return "sh"
            return when {
                tab.backend != null -> when (tab.backend.displayName) {
                    "Windows PowerShell" -> "powershell.exe"
                    "Windows CMD" -> "cmd.exe"
                    else -> "bash"
                }
                tab.kind == "ubuntu" -> "bash"
                else -> "sh"
            }
        }
    val secondaryTab: TerminalTab? get() = if (split) tabs.getOrNull(splitIndex) else null
    val activeSession: TerminalSession? get() = activeTab?.session
    val allTabs: List<TerminalTab> get() = tabs

    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)
    var status by mutableStateOf("Local shell")
        private set
    /** "phone", "ubuntu" or "ssh". Reflects the active tab so the profile chips stay in sync. */
    var shell by mutableStateOf("phone")
        private set
    var workingDirectory by mutableStateOf(root.absolutePath)
        private set
    var searchQuery by mutableStateOf("")
    var searchHits by mutableStateOf<List<String>>(emptyList())
        private set
    var showOnboarding by mutableStateOf(!prefs.getBoolean("onboarding_done", false))
        private set
    var showProfileMenu by mutableStateOf(false)
    var showHistory by mutableStateOf(false)
    var showSearch by mutableStateOf(false)
    var showRemoteManager by mutableStateOf(false)

    val commandHistory = mutableStateListOf<String>()
    private var remoteRevision by mutableIntStateOf(0)
    val remoteProfiles: List<RemoteProfile>
        get() {
            remoteRevision // subscribe to changes
            return profileStore.list()
        }

    private var primary: TerminalView? = null
    private var secondary: TerminalView? = null
    /** The pane that last received focus, so split mode opens the keyboard on the tapped pane. */
    private var focused: TerminalView? = null
    private val initialized = mutableSetOf<String>()
    private val pending = mutableMapOf<String, MutableList<String>>()
    private var dark = true

    val ubuntuAvailable get() = ubuntu?.invoke() != null

    // ---- sessions ---------------------------------------------------------------------------------

    private fun backendFor(kind: String): TerminalExecutionBackend? = when (kind) {
        "ubuntu" -> ubuntu?.invoke()
        "phone" -> LocalShellBackend(context)
        else -> null
    }

    private fun createTab(kind: String): TerminalTab {
        val id = nextId++
        val backend = backendFor(kind)
        val session = backend?.createSession(this, this) ?: LocalShellBackend(context).createSession(this, this)
        val actualKind = if (backend != null) kind else "phone"
        val title = when (actualKind) {
            "ubuntu" -> "Ubuntu $id"
            "phone" -> if (tabs.isEmpty()) "Terminal" else "Terminal $id"
            else -> "Remote $id"
        }
        return TerminalTab(id, title, actualKind, session).also { tabs.add(it) }
    }

    private fun openRemoteTab(profile: RemoteProfile, secret: SshSecret): SshTerminalBackend {
        val backend = SshTerminalBackend(profile, secret, hostKeys)
        val session = backend.createSession(this, this)
        val tab = TerminalTab(nextId++, profile.label, "ssh", session, backend)
        tabs.add(tab)
        activeIndex = tabs.lastIndex
        shell = "ssh"
        status = "Connecting to ${profile.host}..."
        initialized.remove(session.mHandle)
        attachPrimary()
        return backend
    }

    fun connectRemote(profile: RemoteProfile, secret: SshSecret): SshTerminalBackend {
        profileStore.save(profile)
        remoteRevision++
        return openRemoteTab(profile, secret)
    }

    fun connectRemote(profile: RemoteProfile) {
        showRemoteManager = true
    }

    fun deleteRemoteProfile(id: String) {
        profileStore.delete(id)
        remoteRevision++
    }

    fun attach(terminal: TerminalView) {
        primary = terminal
        if (tabs.isEmpty()) createTab("phone")
        attachPrimary()
    }

    fun attachSecondary(terminal: TerminalView) {
        secondary = terminal
        if (split && tabs.size < 2) addTab("phone")
        val tab = secondaryTab ?: return
        terminal.attachSession(tab.session)
        applyColors(tab.session)
        terminal.onScreenUpdated()
    }

    private fun attachPrimary() {
        val view = primary ?: return
        val tab = activeTab ?: return
        try {
            view.attachSession(tab.session)
            applyColors(tab.session)
            view.onScreenUpdated()
        } catch (error: Exception) {
            status = "Terminal failed: ${error.message}"
        }
    }

    fun detach(terminal: TerminalView) {
        if (primary === terminal) primary = null
        if (secondary === terminal) secondary = null
        if (focused === terminal) focused = null
    }

    fun markFocused(terminal: TerminalView) {
        focused = terminal
    }

    fun addTab(kind: String = "phone"): TerminalTab {
        val tab = createTab(kind)
        activeIndex = tabs.lastIndex
        shell = tab.kind
        status = statusFor(tab)
        attachPrimary()
        if (secondary != null) attachSecondary(secondary!!)
        return tab
    }

    fun switchTo(index: Int) {
        if (index !in tabs.indices) return
        activeIndex = index
        val tab = tabs[index]
        shell = tab.kind
        status = statusFor(tab)
        attachPrimary()
    }

    fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val tab = tabs.removeAt(index)
        tab.session.finishIfRunning()
        if (tabs.isEmpty()) {
            val fresh = createTab("phone")
            activeIndex = 0
            initialized.remove(fresh.session.mHandle)
            attachPrimary()
        } else {
            activeIndex = activeIndex.coerceIn(0, tabs.lastIndex)
            if (index < activeIndex) activeIndex--
            if (splitIndex >= tabs.size) splitIndex = (activeIndex + 1) % tabs.size
            attachPrimary()
            secondary?.let { if (secondaryTab != null) attachSecondary(it) }
        }
        if (tabs.size < 2) split = false
        val active = activeTab ?: return
        shell = active.kind
        status = statusFor(active)
    }

    fun closeActiveTab() = closeTab(activeIndex)

    fun toggleSplit() {
        if (split) {
            split = false
            secondary = null
            return
        }
        if (tabs.size < 2) addTab("phone")
        splitIndex = ((activeIndex + 1) % tabs.size).coerceAtLeast(0)
        split = true
        attachSecondary(secondary ?: return)
    }

    fun toggleMaximize() { maximized = !maximized }

    fun toggleArrowKeys() { showArrowKeys = !showArrowKeys }

    fun restart() {
        val tab = activeTab ?: return
        val old = tab.session
        val replacement = when {
            tab.backend != null -> tab.backend.createSession(this, this)
            else -> (backendFor(tab.kind) ?: LocalShellBackend(context)).createSession(this, this)
        }
        tabs[activeIndex] = tab.let { it.session = replacement; it }
        initialized.remove(replacement.mHandle)
        status = if (tab.kind == "phone") "Local shell" else statusFor(tab)
        attachPrimary()
        runCatching { old.finishIfRunning() }
    }

    fun useShell(kind: String, command: String? = null) {
        val target = if (kind == "ubuntu" && ubuntuAvailable) "ubuntu" else "phone"
        val current = activeTab
        if (current != null && current.kind == target && current.session.isRunning) {
            if (command != null) {
                writeToSession(current.session, command + "\n")
                commandHistory.add(0, command.trim())
            }
            return
        }
        val tab = createTab(target)
        activeIndex = tabs.lastIndex
        shell = target
        if (command != null) queue(tab.session, command + "\n")
        status = statusFor(tab)
        attachPrimary()
    }

    fun execute(command: String) {
        val session = activeSession ?: return
        if (command.isBlank()) return
        commandHistory.remove(command.trim())
        commandHistory.add(0, command.trim())
        when {
            !session.isRunning -> status = "Shell exited. Restart terminal to run another command."
            initialized.contains(session.mHandle) -> writeToSession(session, if (command.endsWith("\n")) command else command + "\n")
            else -> queue(session, if (command.endsWith("\n")) command else command + "\n")
        }
    }

    fun key(bytes: ByteArray) {
        activeSession?.let { writeToSession(it, bytes) }
        ctrl = false
        alt = false
    }

    private fun writeToSession(session: TerminalSession, text: String) = writeToSession(session, text.toByteArray(Charsets.UTF_8))

    private fun writeToSession(session: TerminalSession, bytes: ByteArray) {
        if (!session.isRunning || bytes.isEmpty()) return
        session.write(bytes, 0, bytes.size)
    }

    private fun queue(session: TerminalSession, text: String) {
        pending.getOrPut(session.mHandle) { mutableListOf() }.add(text)
    }

    private fun initializeSession(session: TerminalSession) {
        if (!initialized.add(session.mHandle)) return
        val tab = tabs.firstOrNull { it.session === session } ?: return
        if (tab.kind == "phone") writeToSession(session, "cd " + shellQuote(root.absolutePath) + "\n")
        pending.remove(session.mHandle)?.forEach { writeToSession(session, it) }
    }

    fun clear() {
        activeSession?.let { writeToSession(it, "clear\n") }
    }

    fun killProcess() {
        val tab = activeTab ?: return
        tab.session.finishIfRunning()
        status = "Process killed"
    }

    fun close() {
        tabs.forEach { runCatching { it.session.finishIfRunning() } }
        tabs.clear()
        pending.clear()
        initialized.clear()
        primary = null
        secondary = null
        split = false
    }

    // ---- clipboard, search, history ---------------------------------------------------------------

    fun copy() {
        val view = focused?.takeIf { it.isSelectingText } ?: primary
        val text = view?.selectedText
        if (text.isNullOrEmpty()) { status = "Long-press terminal text to select it"; return }
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.setPrimaryClip(ClipData.newPlainText("Codriod terminal", text))
        view?.stopTextSelectionMode()
    }

    fun copyAll() {
        val text = transcript()
        if (text.isBlank()) { status = "Terminal is empty"; return }
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.setPrimaryClip(ClipData.newPlainText("Codriod terminal", text))
        status = "Copied terminal output"
    }

    fun paste() {
        val text = (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
        val session = activeSession ?: return
        // Respect bracketed-paste mode instead of simulating typed commands.
        session.emulator?.paste(text) ?: writeToSession(session, text)
    }

    fun search(query: String) {
        searchQuery = query
        if (query.isBlank()) { searchHits = emptyList(); return }
        searchHits = transcript().lineSequence()
            .filter { it.contains(query, ignoreCase = true) }
            .toList()
            .takeLast(200)
    }

    fun refreshWorkingDirectory() {
        val tab = activeTab ?: return
        val cwd = if (tab.backend != null) {
            val profile = runCatching { tab.backend?.let { remoteProfiles.firstOrNull { p -> p.label == tab.title } } }.getOrNull()
            "~ (${profile?.host ?: "remote"})"
        } else {
            tab.session.getCwd() ?: workingDirectory
        }
        workingDirectory = prettyPath(cwd)
    }

    private fun prettyPath(path: String): String {
        val home = root.absolutePath
        return when {
            path == home -> "~"
            path.startsWith(home + "/") -> "~" + path.removePrefix(home)
            else -> path
        }
    }

    // ---- environment / onboarding ----------------------------------------------------------------

    fun environment(): TerminalEnvironment {
        val tools = listOf("sh", "toybox", "grep", "sed", "cat", "ls", "find", "mkdir", "cp", "mv", "rm", "git", "curl", "python3", "node", "php", "ssh")
        val path = System.getenv("PATH") ?: "/system/bin"
        val dirs = path.split(File.pathSeparatorChar).filter { it.isNotBlank() } +
            listOf("/system/bin", "/system/xbin", "/vendor/bin", "/product/bin")
        val detected = tools.map { name ->
            val file = dirs.distinct().map { File(it, name) }.firstOrNull { it.isFile && it.canExecute() }
            ToolAvailability(name, file != null, file?.path ?: "Not installed on this device")
        }
        return TerminalEnvironment(
            platform = "Android (${android.os.Build.VERSION.RELEASE}, API ${android.os.Build.VERSION.SDK_INT})",
            shell = "Local /system/bin/sh",
            home = root.absolutePath,
            tools = detected
        )
    }

    fun dismissOnboarding() {
        prefs.edit().putBoolean("onboarding_done", true).apply()
        showOnboarding = false
    }

    fun setTheme(darkTheme: Boolean) { dark = darkTheme; applyAllColors() }

    private fun applyAllColors() {
        tabs.forEach { applyColors(it.session) }
        primary?.onScreenUpdated()
        secondary?.onScreenUpdated()
    }

    private fun applyColors(session: TerminalSession) {
        session.emulator?.mColors?.let {
            it.tryParseColor(TextStyle.COLOR_INDEX_BACKGROUND, if (dark) "#1e1e1e" else "#ffffff")
            it.tryParseColor(TextStyle.COLOR_INDEX_FOREGROUND, if (dark) "#d4d4d4" else "#333333")
            it.tryParseColor(TextStyle.COLOR_INDEX_CURSOR, "#007acc")
        }
        primary?.onScreenUpdated()
    }

    private fun statusFor(tab: TerminalTab): String = when {
        tab.backend != null -> when (val state = tab.backend.status) {
            RemoteStatus.NotConnected -> "Not connected"
            RemoteStatus.Connecting -> "Connecting..."
            RemoteStatus.Connected -> "Connected"
            RemoteStatus.Disconnected -> "Disconnected"
            is RemoteStatus.Failed -> state.message
        }
        tab.kind == "ubuntu" -> "Ubuntu - bash"
        else -> if (tab.session.isRunning) "Local shell" else "Exited (${tab.session.exitStatus}) \u00b7 Restart to continue"
    }

    fun transcript(): String = runCatching { activeSession?.emulator?.screen?.transcriptText }.getOrNull().orEmpty()

    /** Everything the terminal printed (scroll-back included), for the agent's @ Terminal context. */
    fun transcript(tab: TerminalTab): String = runCatching { tab.session.emulator?.screen?.transcriptText }.getOrNull().orEmpty()

    // ---- TerminalSessionClient --------------------------------------------------------------------

    override fun onEmulatorSet() {
        applyAllColors()
        listOfNotNull(primary?.currentSession, secondary?.currentSession).forEach { initializeSession(it) }
    }

    override fun onTextChanged(changedSession: TerminalSession) {
        primary?.onScreenUpdated()
        secondary?.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        val tab = tabs.firstOrNull { it.session === changedSession } ?: return
        val title = changedSession.title
        if (!title.isNullOrBlank()) tab.title = title.take(28)
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        val tab = tabs.firstOrNull { it.session === finishedSession } ?: return
        tab.backend?.markDisconnected()
        if (finishedSession === activeSession) {
            status = if (tab.backend != null) statusFor(tab)
            else "Exited (${finishedSession.exitStatus}) \u00b7 Restart to continue"
        }
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Codriod", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) = paste()
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) { primary?.onScreenUpdated(); secondary?.onScreenUpdated() }
    override fun onTerminalCursorStateChange(state: Boolean) { primary?.invalidate(); secondary?.invalidate() }
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
    override fun getTerminalCursorStyle(): Int? = null

    // ---- TerminalViewClient -----------------------------------------------------------------------

    override fun onScale(scale: Float) = 1f
    override fun onSingleTapUp(e: MotionEvent) {
        (focused ?: primary)?.let {
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
    override fun readAltKey() = alt
    override fun readShiftKey() = false
    override fun readFnKey() = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
        ctrl = false
        alt = false
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
fun WorkspaceTerminal(
    controller: WorkspaceTerminalController,
    modifier: Modifier = Modifier,
    fullscreen: Boolean = false,
    projectName: String? = null,
    onBack: (() -> Unit)? = null
) {
    LaunchedEffect(controller.activeIndex) {
        while (true) {
            controller.refreshWorkingDirectory()
            kotlinx.coroutines.delay(1200)
        }
    }
    val activeTab = controller.activeTab
    Column(modifier.background(MaterialTheme.colorScheme.background)) {

        if (fullscreen && onBack != null) {
            TerminalFullscreenTopBar(controller, projectName, onBack)
        }

        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val showTabs = maxHeight >= 170.dp
            val showSymbols = maxHeight >= 210.dp
            val toolbarHeight = 41.dp +
                (if (controller.showArrowKeys) 36.dp else 0.dp) +
                (if (showSymbols) 36.dp else 0.dp)

            Column(Modifier.fillMaxSize()) {
                if (showTabs) TerminalTabStrip(controller, fullscreen = fullscreen)

                Box(Modifier.fillMaxWidth().weight(1f)) {
                    if (controller.split) {
                        Row(Modifier.fillMaxSize().padding(bottom = toolbarHeight)) {
                            TerminalPane(controller, controller.activeTab, Modifier.weight(1f), secondary = false)
                            Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                            TerminalPane(controller, controller.secondaryTab, Modifier.weight(1f), secondary = true)
                        }
                    } else {
                        TerminalPane(controller, activeTab,
                            Modifier.fillMaxSize().padding(bottom = toolbarHeight), secondary = false)
                    }
                    if (controller.showSearch) {
                        TerminalSearchBar(controller)
                    }
                }
            }

            TerminalShortcutToolbar(
                controller,
                showSymbols = showSymbols,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        if (controller.showOnboarding) OnboardingDialog(controller)
        if (controller.showRemoteManager) RemoteTerminalManager(controller)
        if (controller.showHistory) HistoryDialog(controller)
    }
}

internal fun terminalStatusText(tab: TerminalTab?): String = when (val backendStatus = tab?.backend?.status) {
    null -> tab?.let { if (it.session.isRunning) "Ready" else "Exited" } ?: "Ready"
    RemoteStatus.NotConnected -> "Not connected"
    RemoteStatus.Connecting -> "Connecting..."
    RemoteStatus.Connected -> "Connected"
    RemoteStatus.Disconnected -> "Disconnected"
    is RemoteStatus.Failed -> "Connection failed"
}

private fun terminalProfileLabel(tab: TerminalTab?): String = when {
    tab == null -> "Local Shell"
    tab.backend != null -> tab.backend.displayName
    tab.kind == "ubuntu" -> "Linux"
    else -> "Android local shell"
}

@Composable
private fun TerminalFullscreenTopBar(
    controller: WorkspaceTerminalController,
    projectName: String?,
    onBack: () -> Unit
) {
    val code = LocalCodeColors.current
    var sessionsOpen by remember { mutableStateOf(false) }
    val tab = controller.activeTab
    val statusText = terminalStatusText(tab)
    Column(Modifier.fillMaxWidth().background(code.header)) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 4.dp, end = 5.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TerminalChromeButton(ToolIcon.Back, "Back to editor", onClick = onBack)
            Spacer(Modifier.width(4.dp))
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp))
                .background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                ToolGlyph(ToolIcon.Terminal, MaterialTheme.colorScheme.primary, Modifier.size(17.dp))
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(projectName ?: "Terminal", style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TerminalStatusDot(tab)
                    Spacer(Modifier.width(5.dp))
                    Text("${controller.activeProfileLabel} \u00b7 ${controller.activeShellName} \u00b7 $statusText",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            TerminalChromeButton(ToolIcon.Plus, "New terminal") { controller.addTab("phone") }
            Box {
                TerminalChromeButton(ToolIcon.More, "Session management") { sessionsOpen = true }
                TerminalSessionsMenu(controller, sessionsOpen, { sessionsOpen = false }, showMaximize = false)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
internal fun TerminalStatusDot(tab: TerminalTab?, size: androidx.compose.ui.unit.Dp = 6.dp) {
    val color = when (tab?.backend?.status) {
        null -> if (tab?.session?.isRunning == true) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.error
        RemoteStatus.Connected -> MaterialTheme.colorScheme.primary
        RemoteStatus.Connecting -> MaterialTheme.colorScheme.secondary
        RemoteStatus.NotConnected -> MaterialTheme.colorScheme.onSurfaceVariant
        RemoteStatus.Disconnected -> MaterialTheme.colorScheme.error
        is RemoteStatus.Failed -> MaterialTheme.colorScheme.error
    }
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

@Composable
private fun TerminalChromeButton(
    icon: ToolIcon,
    description: String,
    active: Boolean = false,
    onClick: () -> Unit
) {
    Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp))
        .background(if (active) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface.copy(alpha = 0f))
        .clickable(onClick = onClick)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        ToolGlyph(icon, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            Modifier.size(17.dp))
    }
}

@Composable
private fun TerminalTabStrip(controller: WorkspaceTerminalController, fullscreen: Boolean) {
    var sessionsOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().height(38.dp).padding(start = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically) {
                controller.allTabs.forEachIndexed { index, tab ->
                    val selected = index == controller.activeIndex && !controller.split
                    val inSplit = controller.split && (index == controller.activeIndex || index == controller.splitIndex)
                    TerminalTabButton(
                        title = tab.title,
                        tab = tab,
                        selected = selected,
                        inSplit = inSplit,
                        onSelect = { controller.switchTo(index) },
                        onClose = { controller.closeTab(index) }
                    )
                }
            }
            TerminalChromeButton(ToolIcon.Plus, "New local terminal") { controller.addTab("phone") }
            TerminalChromeButton(ToolIcon.Split, "Toggle split terminal", active = controller.split) { controller.toggleSplit() }
            Box {
                TerminalChromeButton(ToolIcon.More, "Sessions and actions") { sessionsOpen = true }
                TerminalSessionsMenu(controller, sessionsOpen, { sessionsOpen = false }, showMaximize = !fullscreen)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun TerminalTabButton(
    title: String,
    tab: TerminalTab,
    selected: Boolean,
    inSplit: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit
) {
    val shape = RoundedCornerShape(7.dp)
    val active = selected || inSplit
    val background by animateColorAsState(
        when {
            selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
            inSplit -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.10f)
            else -> MaterialTheme.colorScheme.surface.copy(alpha = 0f)
        },
        label = "terminal-tab-background"
    )
    val border by animateColorAsState(
        when {
            selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
            inSplit -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f)
            else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
        },
        label = "terminal-tab-border"
    )
    Row(Modifier.padding(end = 4.dp).height(30.dp).clip(shape).background(background)
        .border(1.dp, border, shape).clickable(onClick = onSelect)
        .padding(start = 8.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        TerminalStatusDot(tab)
        Spacer(Modifier.width(6.dp))
        Text(title, fontSize = 11.sp, maxLines = 1,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 118.dp))
        Spacer(Modifier.width(4.dp))
        Box(Modifier.size(24.dp).clip(RoundedCornerShape(5.dp))
            .clickable(onClick = onClose)
            .semantics { contentDescription = "Close $title" },
            contentAlignment = Alignment.Center) {
            ToolGlyph(ToolIcon.Close,
                if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                Modifier.size(11.dp))
        }
    }
}

@Composable
private fun TerminalSessionsMenu(
    controller: WorkspaceTerminalController,
    expanded: Boolean,
    onDismiss: () -> Unit,
    showMaximize: Boolean
) {
    DropdownMenu(expanded, onDismiss, modifier = Modifier.widthIn(min = 264.dp, max = 320.dp)) {
        TerminalMenuLabel("SESSIONS")
        TerminalMenuItem(ToolIcon.Plus, "New local shell", "Android shell in the project folder") {
            onDismiss(); controller.addTab("phone")
        }
        if (controller.ubuntuAvailable) TerminalMenuItem(ToolIcon.Terminal, "New Ubuntu shell", "Linux runtime") {
            onDismiss(); controller.addTab("ubuntu")
        }
        TerminalMenuItem(ToolIcon.Globe, "New remote connection...",
            "Windows PowerShell, Windows CMD, Linux") {
            onDismiss(); controller.showRemoteManager = true
        }
        HorizontalDivider()
        controller.allTabs.forEachIndexed { index, tab ->
            TerminalMenuItem(
                if (tab.isRemote) ToolIcon.Globe else ToolIcon.Terminal,
                tab.title,
                "${terminalProfileLabel(tab)} \u00b7 ${terminalStatusText(tab)}",
                active = index == controller.activeIndex
            ) {
                onDismiss(); controller.switchTo(index)
            }
        }
        HorizontalDivider()
        TerminalMenuItem(ToolIcon.Split, if (controller.split) "Merge terminals" else "Split terminal",
            if (controller.split) "Return to a single terminal" else "Show two sessions side by side") {
            onDismiss(); controller.toggleSplit()
        }
        if (showMaximize) TerminalMenuItem(ToolIcon.Fullscreen,
            if (controller.maximized) "Restore panel" else "Maximize panel",
            if (controller.maximized) "Return to editor view" else "Expand the terminal panel") {
            onDismiss(); controller.toggleMaximize()
        }
        TerminalMenuItem(ToolIcon.Refresh, "Restart active session", "Start a fresh shell in this tab") {
            onDismiss(); controller.restart()
        }
        TerminalMenuItem(ToolIcon.Close, "Kill running process", "Stop the process and keep this tab",
            danger = true) {
            onDismiss(); controller.killProcess()
        }
        TerminalMenuItem(ToolIcon.Close, "Close active session", enabled = controller.allTabs.size > 1,
            danger = true) {
            onDismiss(); controller.closeActiveTab()
        }
        HorizontalDivider()
        TerminalMenuLabel("TERMINAL TOOLS")
        TerminalMenuItem(ToolIcon.Copy, "Copy selection") { onDismiss(); controller.copy() }
        TerminalMenuItem(ToolIcon.Copy, "Copy all output") { onDismiss(); controller.copyAll() }
        TerminalMenuItem(ToolIcon.Paste, "Paste") { onDismiss(); controller.paste() }
        TerminalMenuItem(ToolIcon.Search, "Search output") { onDismiss(); controller.showSearch = true }
        TerminalMenuItem(ToolIcon.History, "Command history") { onDismiss(); controller.showHistory = true }
        TerminalMenuItem(ToolIcon.Clear, "Clear terminal") { onDismiss(); controller.clear() }
        HorizontalDivider()
        TerminalMenuLabel("SHELL PROFILE")
        TerminalMenuItem(ToolIcon.Terminal, "Android local shell", active = controller.shell == "phone") {
            onDismiss(); controller.useShell("phone")
        }
        if (controller.ubuntuAvailable) TerminalMenuItem(ToolIcon.Terminal, "Ubuntu (proot)", active = controller.shell == "ubuntu") {
            onDismiss(); controller.useShell("ubuntu")
        }
    }
}

@Composable
private fun TerminalMenuLabel(label: String) {
    Text(label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun TerminalMenuItem(
    icon: ToolIcon,
    label: String,
    detail: String? = null,
    active: Boolean = false,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val tint = when {
        danger -> MaterialTheme.colorScheme.error
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToolGlyph(icon, tint, Modifier.size(16.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(label, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    detail?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        trailingIcon = if (active) {
            { ToolGlyph(ToolIcon.Check, MaterialTheme.colorScheme.primary, Modifier.size(15.dp)) }
        } else null,
        enabled = enabled,
        onClick = onClick
    )
}

@Composable
private fun TerminalPane(
    controller: WorkspaceTerminalController,
    tab: TerminalTab?,
    modifier: Modifier,
    secondary: Boolean
) {
    val dark = LocalCodeColors.current.dark
    val textSize = with(LocalDensity.current) { 13.sp.toPx().toInt() }
    val viewRef = remember { mutableStateOf<TerminalView?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            viewRef.value?.let { controller.detach(it) }
            viewRef.value = null
        }
    }
    AndroidView(
        modifier = modifier.background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        factory = { context ->
            TerminalView(context, null).apply {
                setTerminalViewClient(controller)
                setIsTerminalViewKeyLoggingEnabled(true)
                setTextSize(textSize)
                isFocusable = true
                isFocusableInTouchMode = true
                keepScreenOn = true
                setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) controller.markFocused(this)
                }
                viewRef.value = this
                if (secondary) controller.attachSecondary(this) else controller.attach(this)
            }
        },
        update = { view ->
            view.setTextSize(textSize)
            controller.setTheme(dark)
            if (secondary) {
                controller.attachSecondary(view)
            } else {
                controller.attach(view)
            }
            if (view.currentSession == null) tab?.let { view.attachSession(it.session) }
        }
    )
}

@Composable
private fun TerminalSearchBar(controller: WorkspaceTerminalController) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        tonalElevation = 4.dp,
        shadowElevation = 6.dp
    ) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = controller.searchQuery,
                    onValueChange = { controller.search(it) },
                    singleLine = true,
                    placeholder = { Text("Search terminal output", fontSize = 12.sp) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f).height(46.dp)
                )
                Spacer(Modifier.width(4.dp))
                TerminalChromeButton(ToolIcon.Close, "Close search") {
                    controller.showSearch = false
                    controller.searchQuery = ""
                    controller.search("")
                }
            }
            if (controller.searchQuery.isNotBlank()) {
                Text("${controller.searchHits.size} matching lines",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp))
                LazyColumn(Modifier.heightIn(max = 120.dp).padding(top = 4.dp)) {
                    items(controller.searchHits) { line ->
                        Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 1.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalShortcutToolbar(
    controller: WorkspaceTerminalController,
    showSymbols: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().height(40.dp).horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            KeyButton("ESC") { controller.key(byteArrayOf(27)) }
            KeyButton("CTRL", active = controller.ctrl) { controller.ctrl = !controller.ctrl }
            KeyButton("ALT", active = controller.alt) { controller.alt = !controller.alt }
            KeyButton("TAB") { controller.key(byteArrayOf(9)) }
            KeyButton("Ctrl+C") { controller.key(byteArrayOf(3)) }
            KeyButton("DEL") { controller.key(byteArrayOf(127)) }
            ArrowToggle(expanded = controller.showArrowKeys) { controller.toggleArrowKeys() }
            ToolbarDivider()
            TerminalToolChip(ToolIcon.Copy, "Copy") { controller.copy() }
            TerminalToolChip(ToolIcon.Copy, "Copy all") { controller.copyAll() }
            TerminalToolChip(ToolIcon.Paste, "Paste") { controller.paste() }
            TerminalToolChip(ToolIcon.Search, "Find") { controller.showSearch = !controller.showSearch }
            TerminalToolChip(ToolIcon.History, "History") { controller.showHistory = true }
            TerminalToolChip(ToolIcon.Clear, "Clear") { controller.clear() }
            TerminalToolChip(ToolIcon.Refresh, "Restart") { controller.restart() }
            TerminalToolChip(ToolIcon.Close, "Kill", danger = true) { controller.killProcess() }
        }
        AnimatedVisibility(
            visible = controller.showArrowKeys,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Row(Modifier.fillMaxWidth().height(36.dp).horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                KeyButton("\u2191", description = "Arrow up") { controller.key(byteArrayOf(27, 91, 65)) }
                KeyButton("\u2193", description = "Arrow down") { controller.key(byteArrayOf(27, 91, 66)) }
                KeyButton("\u2190", description = "Arrow left") { controller.key(byteArrayOf(27, 91, 68)) }
                KeyButton("\u2192", description = "Arrow right") { controller.key(byteArrayOf(27, 91, 67)) }
            }
        }
        AnimatedVisibility(
            visible = showSymbols,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Row(Modifier.fillMaxWidth().height(36.dp).horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("/", "\\", "-", "_", "=", ":", ";", "(", ")", "{", "}", "[", "]", "\"", "'", "$", "&", "|", "~").forEach { symbol ->
                    KeyButton(symbol, compact = true) { controller.key(symbol.toByteArray(Charsets.UTF_8)) }
                }
            }
        }
    }
}

@Composable
private fun ToolbarDivider() {
    Box(Modifier.width(1.dp).height(20.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

@Composable
private fun ArrowToggle(expanded: Boolean, onClick: () -> Unit) {
    val rotation by animateFloatAsState(if (expanded) 90f else 0f, label = "arrow-rotation")
    val shape = RoundedCornerShape(7.dp)
    Row(Modifier.height(30.dp).widthIn(min = 52.dp).clip(shape)
        .background(if (expanded) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainer)
        .border(1.dp, if (expanded) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant, shape)
        .clickable(onClick = onClick)
        .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("Arrows", fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            fontWeight = if (expanded) FontWeight.SemiBold else FontWeight.Normal,
            color = if (expanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1)
        Spacer(Modifier.width(3.dp))
        ToolGlyph(ToolIcon.Chevron,
            if (expanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            Modifier.size(12.dp).rotate(rotation))
    }
}

@Composable
private fun TerminalToolChip(icon: ToolIcon, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val shape = RoundedCornerShape(7.dp)
    Row(Modifier.height(30.dp).clip(shape)
        .background(MaterialTheme.colorScheme.surfaceContainer)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
        .clickable(onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        ToolGlyph(icon, tint, Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
    }
}

@Composable
private fun KeyButton(
    label: String,
    active: Boolean = false,
    compact: Boolean = false,
    description: String? = null,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(7.dp)
    Box(Modifier.height(30.dp).widthIn(min = if (compact) 30.dp else 38.dp).clip(shape)
        .background(if (active) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainer)
        .border(1.dp, if (active) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant, shape)
        .clickable(onClick = onClick)
        .semantics { description?.let { contentDescription = it } }
        .padding(horizontal = if (compact) 6.dp else 8.dp),
        contentAlignment = Alignment.Center) {
        Text(label, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1)
    }
}

@Composable
private fun OnboardingDialog(controller: WorkspaceTerminalController) {
    val env = remember { controller.environment() }
    AlertDialog(
        onDismissRequest = { controller.dismissOnboarding() },
        title = { Text("CODRIOD TERMINAL") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Environment: Android", fontWeight = FontWeight.SemiBold)
                Text("Shell: Local Shell", fontWeight = FontWeight.SemiBold)
                Text("Status: Ready", fontWeight = FontWeight.SemiBold)
                Text("codriod@android:~$", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                HorizontalDivider()
                Text("Detected on this device", style = MaterialTheme.typography.labelMedium)
                Text(env.platform, style = MaterialTheme.typography.bodySmall)
                FlowRowTools(env.tools)
                HorizontalDivider()
                Text("Create more terminals", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                Text("Use + in the tab strip for a new session, Split to show two side by side, and x to close one.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Remote machines", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                Text("Open Profile > New remote connection to reach Windows PowerShell, Windows CMD or a Linux shell over SSH. " +
                    "Those shells run on the remote machine, never on Android.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Missing tools", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                Text("Python, Node.js, PHP and bash are not part of Android. Install the Ubuntu add-on from Add-ons, " +
                    "or connect a remote machine that has them.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { controller.dismissOnboarding() }) { Text("Get started") } }
    )
}

@Composable
private fun FlowRowTools(tools: List<ToolAvailability>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        tools.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { tool ->
                    Text("${if (tool.available) "+" else "-"} ${tool.name}", fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                        color = if (tool.available) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun HistoryDialog(controller: WorkspaceTerminalController) {
    AlertDialog(
        onDismissRequest = { controller.showHistory = false },
        title = { Text("Command history") },
        text = {
            if (controller.commandHistory.isEmpty()) {
                Text("Commands run from Codriod appear here. The shell keeps its own history for the arrow keys.",
                    style = MaterialTheme.typography.bodySmall)
            } else {
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(controller.commandHistory) { command ->
                        Row(Modifier.fillMaxWidth().clickable {
                            controller.showHistory = false
                            controller.execute(command)
                        }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("$", fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(6.dp))
                            Text(command, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { controller.commandHistory.clear() }) { Text("Clear all") } },
        dismissButton = { TextButton(onClick = { controller.showHistory = false }) { Text("Close") } }
    )
}

/**
 * Remote terminal manager: instructions, saved profiles and a real SSH connection form.
 * Passwords and private keys are never written to disk.
 */
@Composable
private fun RemoteTerminalManager(controller: WorkspaceTerminalController) {
    var editing by remember { mutableStateOf<RemoteProfile?>(null) }
    var showForm by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { controller.showRemoteManager = false },
        title = { Text("Remote terminals") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Windows PowerShell, Windows CMD and Linux shells run on another machine over SSH. " +
                    "Android cannot run them locally.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (controller.remoteProfiles.isEmpty()) {
                    Text("No saved connections yet.", style = MaterialTheme.typography.bodySmall)
                } else {
                    controller.remoteProfiles.forEach { profile ->
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(6.dp)) {
                            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(profile.label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text("${profile.username}@${profile.host}:${profile.port} - ${profile.preset.label}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = { editing = profile; showForm = true }) { Text("Edit", fontSize = 11.sp) }
                                TextButton(onClick = { controller.deleteRemoteProfile(profile.id) }) {
                                    Text("Delete", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { editing = null; showForm = true }) { Text("New connection") }
                    TextButton(onClick = { showHelp = true }) { Text("Windows setup") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { controller.showRemoteManager = false }) { Text("Close") } }
    )
    if (showForm) {
        RemoteConnectionForm(controller, editing) { showForm = false; editing = null }
    }
    if (showHelp) WindowsSshHelpDialog { showHelp = false }
}

@Composable
private fun WindowsSshHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect to Windows") },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("On the Windows PC", fontWeight = FontWeight.SemiBold)
                Text("1. Settings > System > Optional features > Add a feature > OpenSSH Server.", style = MaterialTheme.typography.bodySmall)
                Text("2. Services > OpenSSH SSH Server > Start; set Startup type to Automatic.", style = MaterialTheme.typography.bodySmall)
                Text("3. Allow port 22 (or your chosen port) in Windows Firewall.", style = MaterialTheme.typography.bodySmall)
                Text("4. Find the address with ipconfig (IPv4 Address).", style = MaterialTheme.typography.bodySmall)
                Text("5. In an Administrator PowerShell: Add-LocalGroupMember -Group 'OpenSSH Users' -Member <your-user> (if needed).",
                    style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Text("In Codriod", fontWeight = FontWeight.SemiBold)
                Text("Create a connection with the PC address, your Windows user name and port 22. " +
                    "Choose Windows PowerShell or Windows CMD as the shell. Test with \$PSVersionTable, Get-Location and Get-Date.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Both devices must be on the same network, or the PC must be reachable through a VPN.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } }
    )
}

@Composable
private fun RemoteConnectionForm(
    controller: WorkspaceTerminalController,
    initial: RemoteProfile?,
    onClose: () -> Unit
) {
    var label by remember { mutableStateOf(initial?.label.orEmpty()) }
    var host by remember { mutableStateOf(initial?.host.orEmpty()) }
    var port by remember { mutableStateOf((initial?.port ?: 22).toString()) }
    var username by remember { mutableStateOf(initial?.username.orEmpty()) }
    var preset by remember { mutableStateOf(initial?.preset ?: RemoteShellPreset.WINDOWS_POWERSHELL) }
    var startup by remember { mutableStateOf(initial?.startupCommand ?: preset.startupCommand) }
    var useKey by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var backend by remember { mutableStateOf<SshTerminalBackend?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }

    val status = backend?.status

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (initial == null) "New remote connection" else "Edit remote connection") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(label, { label = it }, label = { Text("Name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(host, { host = it }, label = { Text("Host or IP") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) },
                        label = { Text("Port") }, singleLine = true, modifier = Modifier.width(100.dp))
                    OutlinedTextField(username, { username = it }, label = { Text("User name") }, singleLine = true,
                        modifier = Modifier.weight(1f))
                }
                Text("SHELL ON THE REMOTE MACHINE", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    RemoteShellPreset.entries.forEach { option ->
                        FilterChip(selected = preset == option, onClick = {
                            preset = option
                            startup = option.startupCommand
                        }, label = { Text(option.label, fontSize = 11.sp) })
                    }
                }
                OutlinedTextField(startup, { startup = it }, label = { Text("Startup command (optional)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(preset.hint, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)

                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selected = !useKey, onClick = { useKey = false }, label = { Text("Password", fontSize = 11.sp) })
                    FilterChip(selected = useKey, onClick = { useKey = true }, label = { Text("Private key", fontSize = 11.sp) })
                }
                if (!useKey) {
                    OutlinedTextField(password, { password = it }, label = { Text("Password (not saved)") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth())
                } else {
                    OutlinedTextField(privateKey, { privateKey = it }, label = { Text("Private key (PEM/OpenSSH)") },
                        minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(passphrase, { passphrase = it }, label = { Text("Key passphrase (optional)") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth())
                }

                localError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                when (val state = status) {
                    is RemoteStatus.Connecting -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp)); Text("Connecting to $host...", style = MaterialTheme.typography.bodySmall)
                    }
                    is RemoteStatus.Connected -> Text("Connected to $host", color = MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.bodySmall)
                    is RemoteStatus.Disconnected -> Text("Disconnected", style = MaterialTheme.typography.bodySmall)
                    is RemoteStatus.Failed -> {
                        Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        state.unknownHost?.let { unknown ->
                            HostKeyPrompt(unknown) {
                                backend?.trustReportedHostKey()
                            }
                        }
                    }
                    else -> Unit
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (status is RemoteStatus.Connected) TextButton(onClick = onClose) { Text("Done") }
                else Button(onClick = {
                    localError = null
                    val parsedPort = port.toIntOrNull() ?: 22
                    val profile = RemoteProfile(
                        id = initial?.id ?: java.util.UUID.randomUUID().toString(),
                        label = label.trim().ifEmpty { "${username.trim()}@${host.trim()}" },
                        host = host.trim(), port = parsedPort, username = username.trim(),
                        preset = preset, startupCommand = startup.trim()
                    )
                    validateRemoteProfile(profile)?.let { localError = it; return@Button }
                    val secret: SshSecret = if (useKey) {
                        if (privateKey.isBlank()) { localError = "Paste your private key or choose Password"; return@Button }
                        SshSecret.PrivateKey(privateKey, passphrase.ifBlank { null })
                    } else {
                        if (password.isEmpty()) { localError = "Enter the password for this connection"; return@Button }
                        SshSecret.Password(password)
                    }
                    backend = controller.connectRemote(profile, secret)
                }) { Text(if (backend == null) "Connect" else "Reconnect") }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } }
    )
}

@Composable
private fun HostKeyPrompt(unknown: UnknownHostKey, onTrust: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(6.dp)) {
        Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Verify this host key with the machine owner before trusting it.",
                style = MaterialTheme.typography.labelSmall)
            Text("${unknown.host} ${unknown.type}", fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            Text(unknown.fingerprint, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            TextButton(onClick = onTrust) { Text("Trust this host") }
        }
    }
}
