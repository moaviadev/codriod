package dev.moavia.codriod.workspace

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Runs the generated shell functions in a real shell (mksh, Android's shell, when installed) against the request loop. */
class CodriodCommandsTest {
    @get:Rule val temp = TemporaryFolder()
    private val seen = CopyOnWriteArrayList<Triple<String, List<String>, String?>>()
    private lateinit var ipc: File
    private lateinit var rc: File
    @Volatile private var running = true
    private lateinit var loop: Thread
    private val shell = listOf("/usr/bin/mksh", "/system/bin/sh", "/bin/sh").first { File(it).canExecute() }

    @Before fun start() {
        ipc = temp.newFolder("ipc")
        rc = File(temp.root, "codriod.rc").apply { writeText(CodriodCommands.rcScript(ipc.absolutePath)) }
        loop = Thread {
            while (running) {
                CodriodCommands.processPending(ipc) { cwd, args, stdin ->
                    seen += Triple(cwd.path, args, stdin)
                    if (args.firstOrNull() == "fail") 2 to "bad thing" else 0 to "ok " + args.joinToString("|")
                }
                Thread.sleep(20)
            }
        }.apply { isDaemon = true; start() }
    }

    @After fun stop() { running = false; loop.join(1000) }

    private fun run(vararg command: String, input: String? = null, env: Map<String, String> = emptyMap()): String {
        val p = ProcessBuilder(*command).directory(temp.root).redirectErrorStream(true).apply { environment().putAll(env) }.start()
        if (input != null) p.outputStream.use { it.write(input.toByteArray()) } else p.outputStream.close()
        check(p.waitFor(20, TimeUnit.SECONDS)) { "shell timed out" }
        return p.inputStream.readBytes().toString(Charsets.UTF_8)
    }

    @Test fun argumentsStdinAndExitCodes() {
        val out = run(shell, "-c", ". '${rc.path}'\ncodriod share 'a b' --app whatsapp; echo rc=\$?\n" +
            "printf 'x\\ny' | codriod clipboard set; echo rc=\$?\n" +
            "codriod notify \"\$(printf 'l1\\nl2')\"; echo rc=\$?\ncodriod fail; echo rc=\$?")
        assertTrue(out, out.contains("ok share|a b|--app|whatsapp\nrc=0"))
        assertTrue(out, out.contains("ok clipboard|set\nrc=0"))
        assertTrue(out, out.contains("bad thing\nrc=2"))
        assertEquals(listOf("share", "a b", "--app", "whatsapp"), seen[0].second)
        assertEquals(temp.root.canonicalPath, File(seen[0].first).canonicalPath)
        assertEquals("x\ny", seen[1].third)
        assertEquals(listOf("notify", "l1\nl2"), seen[2].second)
        assertTrue(File(ipc, "res").listFiles()!!.isEmpty())
    }

    @Test fun termuxNamesWorkInTheInteractiveTerminal() {
        assumeTrue("needs mksh", shell.endsWith("mksh"))
        val out = run(shell, "-i", input = "termux-open-url https://wa.me\ntermux-clipboard-get\nexit\n", env = mapOf("ENV" to rc.path))
        assertTrue(out, out.contains("ok open-url|https://wa.me"))
        assertTrue(out, out.contains("ok clipboard|get"))
    }
}
