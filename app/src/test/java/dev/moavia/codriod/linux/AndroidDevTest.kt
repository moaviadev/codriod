package dev.moavia.codriod.linux

import dev.moavia.codriod.workspace.CodriodCommands
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class AndroidDevTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun bash(dir: File, env: Map<String, String>, vararg cmd: String): Pair<Int, String> {
        val pb = ProcessBuilder("bash", *cmd).directory(dir).redirectErrorStream(true)
        pb.environment().putAll(env)
        val p = pb.start(); val out = p.inputStream.bufferedReader().readText(); p.waitFor(60, TimeUnit.SECONDS)
        return p.exitValue() to out
    }

    @Test fun scriptsAreValidBash() {
        for ((name, text) in listOf("sdk" to AndroidDev.sdkScript(), "build" to AndroidDev.BUILD_SCRIPT, "logs" to AndroidDev.LOGS_SCRIPT,
            "node" to LinuxScripts.node(), "base" to LinuxScripts.base)) {
            val f = File(tmp.root, "$name.sh").apply { writeText(text) }
            val (code, out) = bash(tmp.root, emptyMap(), "-n", f.absolutePath)
            assertEquals("$name: $out", 0, code)
        }
        val sdk = AndroidDev.sdkScript()
        assertTrue(sdk.contains("sha1sum -c"))
        assertTrue(sdk.contains("\"platforms;android-35\" \"build-tools;35.0.0\" \"platform-tools\""))
        assertTrue(sdk.contains("android.aapt2FromMavenOverride"))
        assertTrue(sdk.contains("openjdk-17-jdk-headless"))
    }

    @Test fun androidBuildFindsTheApkAndInstalls() {
        val sdk = tmp.newFolder("sdk"); File(sdk, "platforms").mkdirs()
        val bin = tmp.newFolder("bin")
        File(bin, "codriod").apply { writeText("#!/bin/sh\necho \"codriod $*\"\n"); setExecutable(true) }
        val project = tmp.newFolder("app")
        File(project, "settings.gradle").writeText("include ':app'\n")
        File(project, "gradlew").writeText("#!/bin/sh\necho \"gradle $1\"\nmkdir -p app/build/outputs/apk/debug\necho apk > app/build/outputs/apk/debug/app-debug.apk\n")
        val script = File(tmp.root, "android-build").apply { writeText(AndroidDev.BUILD_SCRIPT) }
        val env = mapOf("ANDROID_HOME" to sdk.absolutePath, "PATH" to bin.absolutePath + ":" + System.getenv("PATH"))
        val (code, out) = bash(project, env, script.absolutePath, "--install")
        assertEquals(out, 0, code)
        assertTrue(out.contains("gradle assembleDebug"))
        assertTrue(out.contains("APK: ./app/build/outputs/apk/debug/app-debug.apk"))
        assertTrue(out.contains("codriod install ./app/build/outputs/apk/debug/app-debug.apk"))
        assertEquals("sdk.dir=${sdk.absolutePath}", File(project, "local.properties").readText().trim())
        // without the SDK it explains where to install it
        val (code2, out2) = bash(project, mapOf("ANDROID_HOME" to "/nope"), script.absolutePath)
        assertEquals(3, code2); assertTrue(out2.contains("Android SDK is not installed"))
        // outside a project
        val (code3, _) = bash(tmp.newFolder("empty"), env, script.absolutePath)
        assertEquals(4, code3)
    }

    @Test fun instructionsKeepTheUsersText() {
        val first = AndroidDev.mergeInstructions(null, "A")
        assertEquals("${AndroidDev.BEGIN}\nA\n${AndroidDev.END}\n", first)
        val user = "# My rules\nUse tabs.\n"
        val merged = AndroidDev.mergeInstructions(user, "A")
        assertTrue(merged.startsWith("# My rules\nUse tabs."))
        val updated = AndroidDev.mergeInstructions(merged, "B")
        assertTrue(updated.contains("Use tabs.")); assertTrue(updated.contains("\nB\n")); assertFalse(updated.contains("\nA\n"))
        assertEquals(1, Regex(Regex.escape(AndroidDev.BEGIN)).findAll(updated).count())
        val notes = AndroidDev.agentInstructions(sdkInstalled = true, adbConnected = false)
        assertTrue(notes.contains("codriod install")); assertTrue(notes.contains("android-build --install"))
        assertTrue(notes.contains("connect wireless debugging") || notes.contains("must connect wireless debugging"))
        assertTrue(AndroidDev.agentInstructions(false, false).contains("NOT installed"))
        assertTrue(AndroidDev.instructionFiles.containsAll(listOf("/root/.codex/AGENTS.md", "/root/.gemini/GEMINI.md", "/root/.claude/CLAUDE.md")))
    }

    @Test fun doctorKnowsGradleErrors() {
        fun ids(t: String) = LinuxDoctor.diagnose(t).map { it.id }
        assertTrue("gradle-memory" in ids("Exception in thread \"main\" java.lang.OutOfMemoryError: Java heap space"))
        assertTrue("android-licenses" in ids("Failed to install the following Android SDK packages as some licences have not been accepted."))
        assertTrue("aapt2" in ids("AAPT2 aapt2-8.5.0-11315950-linux Daemon #0: Daemon startup failed"))
        assertTrue("sdk-location" in ids("SDK location not found. Define a valid SDK location with an ANDROID_HOME environment variable"))
    }

    @Test fun slowCommandsRunOnTheirOwnThread() {
        val ipc = tmp.newFolder("ipc"); File(ipc, "req").mkdirs()
        File(ipc, "req/a.req").writeText("/\ninstall\napp.apk\n")
        File(ipc, "req/b.req").writeText("/\ntoast\nhi\n")
        val started = System.currentTimeMillis()
        CodriodCommands.processPending(ipc, slow = { it.firstOrNull() == "install" }) { _, args, _ ->
            if (args.first() == "install") { Thread.sleep(700); 0 to "installed" } else 0 to "ok"
        }
        assertTrue("toast is not blocked by the install", System.currentTimeMillis() - started < 500)
        assertTrue(File(ipc, "res/b.done").exists())
        val deadline = System.currentTimeMillis() + 3000
        while (!File(ipc, "res/a.done").exists() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals("installed\n", File(ipc, "res/a.out").readText())
    }
}
