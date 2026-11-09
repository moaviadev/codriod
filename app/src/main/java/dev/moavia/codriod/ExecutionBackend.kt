package dev.moavia.codriod

import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalViewClient

interface TerminalExecutionBackend {
    val displayName: String
    fun createSession(
        viewClient: TerminalViewClient,
        sessionClient: TerminalSessionClient
    ): TerminalSession
}
