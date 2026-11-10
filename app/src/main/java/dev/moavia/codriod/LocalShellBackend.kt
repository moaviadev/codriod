package dev.moavia.codriod

import android.content.Context
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalViewClient
import java.io.File

class LocalShellBackend(private val context: Context) : TerminalExecutionBackend {
    override val displayName = "Local"

    override fun createSession(
        viewClient: TerminalViewClient,
        sessionClient: TerminalSessionClient
    ): TerminalSession {
        val baseDir = context.filesDir
        val projectsDir = File(baseDir, "projects/Codriod").apply { mkdirs() }

        // Setup sample project files matching Codriod structure if they don't exist
        File(projectsDir, "app").mkdirs()
        File(projectsDir, "src").mkdirs()
        File(projectsDir, "gradle").mkdirs()
        File(projectsDir, "docs").mkdirs()

        val readme = File(projectsDir, "README.md")
        if (!readme.exists()) {
            readme.writeText("# Codriod\nPro Android Terminal & Workspace.\nCodriod is Awesome!\n")
        }
        val buildGradle = File(projectsDir, "build.gradle")
        if (!buildGradle.exists()) {
            buildGradle.writeText("// Top-level build file for Codriod\n")
        }
        val settingsGradle = File(projectsDir, "settings.gradle")
        if (!settingsGradle.exists()) {
            settingsGradle.writeText("rootProject.name = 'Codriod'\n")
        }
        val localProps = File(projectsDir, "local.properties")
        if (!localProps.exists()) {
            localProps.writeText("# Local project configuration\n")
        }
        val gradlew = File(projectsDir, "gradlew")
        if (!gradlew.exists()) {
            gradlew.writeText("#!/system/bin/sh\necho \"Gradlew stub for Codriod\"\n")
            gradlew.setExecutable(true)
        }

        val shrc = File(baseDir, ".shrc")
        val coloredPrompt = "\u001b[1;32mcodriod@android\u001b[0m:\u001b[1;34m~/projects/Codriod\u001b[0m$ "
        shrc.writeText(
            """
            export PS1='$coloredPrompt'
            alias ll='ls -la'
            alias la='ls -a'
            cd "$projectsDir" 2>/dev/null
            """.trimIndent()
        )

        val home = projectsDir.absolutePath
        val env = arrayOf(
            "HOME=$home",
            "TMPDIR=${context.cacheDir.absolutePath}",
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "LANG=C.UTF-8",
            "PATH=/sbin:/system/sbin:/product/bin:/apex/com.android.runtime/bin:/system/bin:/system/xbin:/vendor/bin:$home",
            "SHELL=/system/bin/sh",
            "PWD=$home",
            "ENV=${shrc.absolutePath}",
            "PS1=$coloredPrompt"
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
