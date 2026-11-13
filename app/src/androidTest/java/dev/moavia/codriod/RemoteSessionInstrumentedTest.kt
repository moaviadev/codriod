package dev.moavia.codriod

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Verifies the engine support for remote (SSH-style) sessions: bytes flow in both directions,
 * resizes reach the transport, and termination closes the session without a local process.
 */
@RunWith(AndroidJUnit4::class)
class RemoteSessionInstrumentedTest {
    private class RecordingClient : TerminalSessionClient {
        val finished = CountDownLatch(1)
        override fun onTextChanged(changedSession: TerminalSession) {}
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) { finished.countDown() }
        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {}
        override fun onPasteTextFromClipboard(session: TerminalSession?) {}
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
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

    @Test
    fun streamsBothDirectionsResizesAndFinishes() {
        val client = RecordingClient()
        val session = onMain { TerminalSession(2000, client) }
        val toTerminalOut = PipedOutputStream()
        val terminalInput = PipedInputStream(toTerminalOut, 64 * 1024)
        val terminalOutput = PipedOutputStream()
        val fromTerminal = PipedInputStream(terminalOutput, 64 * 1024)
        var terminated = false
        var resized: IntArray? = null

        onMain {
            session.setRemoteIo(
                terminalInput, terminalOutput,
                { terminated = true },
                { columns, rows, _, _ -> resized = intArrayOf(columns, rows) }
            )
        }
        onMain { session.updateSize(80, 24, 8, 20) }
        assertTrue(session.isRunning)
        assertEquals(-1, session.pid)

        // Remote machine -> terminal
        toTerminalOut.write("REMOTE_READY\r\n".toByteArray(Charsets.UTF_8))
        toTerminalOut.flush()
        assertTrue(waitUntil(5_000) { transcript(session).contains("REMOTE_READY") })

        // User input -> remote machine
        val typed = "echo hi\n".toByteArray(Charsets.UTF_8)
        session.write(typed, 0, typed.size)
        assertTrue(waitUntil(5_000) { readAvailable(fromTerminal).contains("echo hi") })

        // Window resize reaches the transport
        onMain { session.updateSize(100, 30, 8, 20) }
        assertTrue(waitUntil(2_000) { resized?.contentEquals(intArrayOf(100, 30)) == true })

        // Closing the transport ends the session
        onMain { session.finishIfRunning() }
        assertTrue(client.finished.await(5, TimeUnit.SECONDS))
        assertFalse(session.isRunning)
        assertTrue(terminated)
    }

    @Test
    fun remoteEofFinishesSession() {
        val client = RecordingClient()
        val session = onMain { TerminalSession(1000, client) }
        val toTerminalOut = PipedOutputStream()
        val terminalInput = PipedInputStream(toTerminalOut, 4096)
        val terminalOutput = PipedOutputStream()
        val fromTerminal = PipedInputStream(terminalOutput, 4096)
        onMain {
            session.setRemoteIo(terminalInput, terminalOutput, {}, { _, _, _, _ -> })
            session.updateSize(40, 12, 8, 20)
        }

        toTerminalOut.write("bye\r\n".toByteArray(Charsets.UTF_8))
        toTerminalOut.flush()
        assertTrue(waitUntil(5_000) { transcript(session).contains("bye") })

        toTerminalOut.close()
        assertTrue(client.finished.await(5, TimeUnit.SECONDS))
        assertFalse(session.isRunning)
    }

    private fun transcript(session: TerminalSession): String {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        return onMain { session.emulator?.screen?.transcriptText.orEmpty() }
    }

    private fun <T> onMain(block: () -> T): T {
        val holder = AtomicReference<T>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { holder.set(block()) }
        return holder.get()
    }

    private fun readAvailable(stream: PipedInputStream): String {
        val buffer = ByteArray(4096)
        val builder = StringBuilder()
        while (stream.available() > 0) {
            val read = stream.read(buffer, 0, minOf(buffer.size, stream.available()))
            if (read <= 0) break
            builder.append(String(buffer, 0, read, Charsets.UTF_8))
        }
        return builder.toString()
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }
}
