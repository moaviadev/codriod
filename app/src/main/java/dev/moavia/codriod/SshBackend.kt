package dev.moavia.codriod

import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalViewClient

class SshBackend : TerminalExecutionBackend {
    override val displayName = "SSH"

    override fun createSession(
        viewClient: TerminalViewClient,
        sessionClient: TerminalSessionClient
    ): TerminalSession {
        throw UnsupportedOperationException("SSH execution is not implemented in the local prototype")
    }
}
