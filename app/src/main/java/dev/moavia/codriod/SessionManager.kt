package dev.moavia.codriod

import android.content.Context
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalViewClient

class SessionManager(context: Context) {
    private val backends: Map<String, TerminalExecutionBackend> = mapOf(
        "Local" to LocalShellBackend(context),
        "SSH" to SshBackend()
    )
    var session: TerminalSession? = null
        private set
    var activeBackend: TerminalExecutionBackend = backends.getValue("Local")
        private set

    val workingDirectory: String = context.filesDir.absolutePath
    var lastError: Throwable? = null
        private set

    fun create(
        backendName: String,
        viewClient: TerminalViewClient,
        sessionClient: TerminalSessionClient
    ): TerminalSession {
        terminate()
        activeBackend = backends[backendName] ?: backends.getValue("Local")
        return try {
            val created = activeBackend.createSession(viewClient, sessionClient)
            session = created
            lastError = null
            created
        } catch (error: Throwable) {
            lastError = error
            throw error
        }
    }

    fun resize(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) {
        session?.updateSize(columns, rows, cellWidth, cellHeight)
    }

    fun write(data: ByteArray) {
        val current = session ?: return
        if (!current.isRunning || data.isEmpty()) return
        current.write(data, 0, data.size)
    }

    fun writeText(text: String) {
        write(text.toByteArray(Charsets.UTF_8))
    }

    fun terminate() {
        session?.let { if (it.isRunning) it.finishIfRunning() }
        session = null
    }
}
