package dev.moavia.codriod

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import dev.moavia.codriod.workspace.WorkspaceTerminalController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class TerminalPtyInstrumentedTest {
    private class RecordingClient : TerminalSessionClient {
        val textChanged = CountDownLatch(1)
        val finished = CountDownLatch(1)
        var pid: Int = 0

        override fun onTextChanged(changedSession: TerminalSession) {
            textChanged.countDown()
        }
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {
            finished.countDown()
        }
        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {}
        override fun onPasteTextFromClipboard(session: TerminalSession?) {}
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun setTerminalShellPid(session: TerminalSession, pid: Int) { this.pid = pid }
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String, message: String) {}
        override fun logWarn(tag: String, message: String) {}
        override fun logInfo(tag: String, message: String) {}
        override fun logDebug(tag: String, message: String) {}
        override fun logVerbose(tag: String, message: String) {}
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
        override fun logStackTrace(tag: String, e: Exception) {}
    }

    @Test
    fun createsPtyWritesInputResizesAndTerminates() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val client = RecordingClient()
        val sessionHolder = AtomicReference<TerminalSession>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            sessionHolder.set(LocalShellBackend(context).createSession(
                object : com.termux.view.TerminalViewClient {
                override fun onScale(scale: Float) = 1f
                override fun onSingleTapUp(e: android.view.MotionEvent) {}
                override fun shouldBackButtonBeMappedToEscape() = false
                override fun shouldEnforceCharBasedInput() = true
                override fun shouldUseCtrlSpaceWorkaround() = false
                override fun isTerminalViewSelected() = true
                override fun copyModeChanged(copyMode: Boolean) {}
                override fun onKeyDown(keyCode: Int, e: android.view.KeyEvent, session: TerminalSession?) = false
                override fun onKeyUp(keyCode: Int, e: android.view.KeyEvent) = false
                override fun onLongPress(event: android.view.MotionEvent) = false
                override fun readControlKey() = false
                override fun readAltKey() = false
                override fun readShiftKey() = false
                override fun readFnKey() = false
                override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?) = false
                override fun onEmulatorSet() {}
                override fun logError(tag: String, message: String) {}
                override fun logWarn(tag: String, message: String) {}
                override fun logInfo(tag: String, message: String) {}
                override fun logDebug(tag: String, message: String) {}
                override fun logVerbose(tag: String, message: String) {}
                override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
                override fun logStackTrace(tag: String, e: Exception) {}
                },
                client
            ))
        }
        val session = sessionHolder.get()

        session.updateSize(80, 24, 8, 20)
        assertTrue(client.pid > 0)
        val command = "printf 'CODRIOD_PTY_OK\\n'\n".toByteArray(Charsets.UTF_8)
        session.write(command, 0, command.size)
        assertTrue("shell did not emit output", client.textChanged.await(5, TimeUnit.SECONDS))

        session.updateSize(100, 30, 8, 20)
        val emulator = session.getEmulator()
        assertEquals(100, emulator.getColumns())
        assertEquals(30, emulator.getRows())

        session.write(byteArrayOf(3), 0, 1)
        session.finishIfRunning()
        assertTrue(client.finished.await(5, TimeUnit.SECONDS))
        assertFalse(session.isRunning)
    }

    @Test
    fun restartIgnoresOldSessionExitCallback() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val (controller, view) = createAttachedTerminal(context)
        try {
            val oldSession = view.currentSession
            assertTrue(oldSession.isRunning)

            InstrumentationRegistry.getInstrumentation().runOnMainSync { controller.restart() }
            val newSession = view.currentSession

            assertNotSame(oldSession, newSession)
            assertTrue("old session did not exit", waitUntil(5_000) { !oldSession.isRunning })
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            assertEquals("Local shell", statusOnMain(controller))
            assertTrue(newSession.isRunning)

            val command = "echo __NEW__\$((1+1))__\n".toByteArray(Charsets.UTF_8)
            newSession.write(command, 0, command.size)
            assertTrue("new session did not respond", waitUntil(5_000) {
                transcriptContains(newSession, "__NEW__2__")
            })
        } finally {
            closeOnMain(controller)
        }
    }

    @Test
    fun currentSessionExitStillReportsStatus() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val (controller, view) = createAttachedTerminal(context)
        try {
            val session = view.currentSession
            assertTrue(session.isRunning)

            InstrumentationRegistry.getInstrumentation().runOnMainSync { session.finishIfRunning() }
            assertTrue("current session did not exit", waitUntil(5_000) { !session.isRunning })
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            assertEquals("Exited (-9) · Restart to continue", statusOnMain(controller))
        } finally {
            closeOnMain(controller)
        }
    }

    @Test
    fun softKeyboardInputReachesTheShell() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val (controller, view) = createAttachedTerminal(context)
        try {
            val session = view.currentSession
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                view.requestFocus()
                "pwd".forEach { view.inputCodePoint(0, it.code, false, false) }
                view.inputCodePoint(0, '\n'.code, false, false)
            }
            assertTrue("shell did not receive typed command", waitUntil(8_000) {
                transcriptContains(session, "pwd") && transcriptContains(session, "projects/Codriod")
            })
        } finally {
            closeOnMain(controller)
        }
    }

    private fun createAttachedTerminal(context: Context): Pair<WorkspaceTerminalController, TerminalView> {
        val root = File(context.cacheDir, "workspace-terminal-${System.nanoTime()}").apply { mkdirs() }
        val controller = WorkspaceTerminalController(context, root)
        val viewHolder = AtomicReference<TerminalView>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = TerminalView(context, null).apply {
                setTerminalViewClient(controller)
                setTextSize(24)
                measure(
                    View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY)
                )
                layout(0, 0, 800, 600)
            }
            controller.attach(view)
            viewHolder.set(view)
        }
        return controller to viewHolder.get()
    }

    private fun statusOnMain(controller: WorkspaceTerminalController): String {
        val holder = AtomicReference<String>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { holder.set(controller.status) }
        return holder.get()
    }

    private fun transcriptContains(session: TerminalSession, text: String): Boolean {
        val holder = AtomicReference(false)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            holder.set(session.emulator?.screen?.transcriptText?.contains(text) == true)
        }
        return holder.get()
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    private fun closeOnMain(controller: WorkspaceTerminalController) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { controller.close() }
    }
}
