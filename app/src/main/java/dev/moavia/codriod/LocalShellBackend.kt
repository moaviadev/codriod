package dev.moavia.codriod

import android.content.Context
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalViewClient

class LocalShellBackend(private val context: Context) : TerminalExecutionBackend {
    override val displayName = "Local"

    override fun createSession(
        viewClient: TerminalViewClient,
        sessionClient: TerminalSessionClient
    ): TerminalSession {
        val home = context.filesDir.absolutePath
        val env = arrayOf(
            "HOME=$home",
            "TMPDIR=${context.cacheDir.absolutePath}",
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "LANG=C.UTF-8",
            "PATH=/sbin:/system/sbin:/product/bin:/apex/com.android.runtime/bin:/system/bin:/system/xbin:/vendor/bin",
            "SHELL=/system/bin/sh",
            "PWD=$home",
            "PS1=codriod:\\w\\$ "
        )
        return TerminalSession(
            "/system/bin/sh",
            home,
            arrayOf("-l"),
            env,
            2000,
            sessionClient
        )
    }
}
