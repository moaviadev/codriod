package dev.moavia.codriod

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
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
}
