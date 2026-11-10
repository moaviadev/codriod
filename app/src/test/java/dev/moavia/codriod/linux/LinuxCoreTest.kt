package dev.moavia.codriod.linux

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit

object JvmFileOps : FileOps {
    override fun symlink(target: String, path: File) { Files.createSymbolicLink(path.toPath(), Paths.get(target)) }
    override fun chmod(path: File, mode: Int) {
        val all = PosixFilePermission.values()
        val set = (0 until 9).filter { mode and (1 shl (8 - it)) != 0 }.map { all[it] }.toSet()
        runCatching { Files.setPosixFilePermissions(path.toPath(), set) }
    }
    override fun isSymlink(path: File) = Files.isSymbolicLink(path.toPath())
}

class LinuxCoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun sh(dir: File, vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText(); p.waitFor(30, TimeUnit.SECONDS); return out
    }

    @Test fun extractsTarWithLinksLongNamesAndModes() {
        val src = tmp.newFolder("src")
        File(src, "usr/bin").mkdirs()
        File(src, "usr/bin/tool").apply { writeText("#!/bin/sh\necho hi\n"); setExecutable(true) }
        Files.createSymbolicLink(File(src, "bin").toPath(), Paths.get("usr/bin"))
        Files.createLink(File(src, "usr/bin/tool2").toPath(), File(src, "usr/bin/tool").toPath())
        val deep = "a/" + "very-long-directory-name-".repeat(6) + "/file.txt"
        File(src, deep).apply { parentFile.mkdirs(); writeText("deep") }
        File(src, "etc").mkdirs(); File(src, "etc/conf").writeText("x")
        val tar = File(tmp.root, "a.tar")
        sh(src, "tar", "--format=gnu", "-cf", tar.absolutePath, ".")
        val dest = tmp.newFolder("dest")
        val n = tar.inputStream().use { TarExtractor.extract(it, dest, JvmFileOps) }
        assertTrue(n >= 6)
        assertEquals("usr/bin", Files.readSymbolicLink(File(dest, "bin").toPath()).toString())
        assertTrue(File(dest, "usr/bin/tool").canExecute())
        assertEquals("#!/bin/sh\necho hi\n", File(dest, "usr/bin/tool2").readText())
        assertFalse(Files.isSymbolicLink(File(dest, "usr/bin/tool2").toPath()))
        assertEquals("deep", File(dest, deep).readText())
        // pax format with long names too
        val pax = File(tmp.root, "p.tar")
        sh(src, "tar", "--format=pax", "-cf", pax.absolutePath, ".")
        val dest2 = tmp.newFolder("dest2")
        pax.inputStream().use { TarExtractor.extract(it, dest2, JvmFileOps) }
        assertEquals("deep", File(dest2, deep).readText())
        // extracting again over existing files and links works
        tar.inputStream().use { TarExtractor.extract(it, dest, JvmFileOps) }
        assertEquals("usr/bin", Files.readSymbolicLink(File(dest, "bin").toPath()).toString())
    }

    @Test fun refusesPathsOutsideTheFolder() {
        val dest = tmp.newFolder("d")
        try { TarExtractor.safePath(dest, "../../etc/passwd"); fail() } catch (_: TarExtractor.BadArchive) {}
        assertEquals(File(dest, "usr/bin"), TarExtractor.safePath(dest, "./usr/bin/"))
        assertNull(TarExtractor.safePath(dest, "./"))
    }

    @Test fun truncatedArchiveIsReported() {
        val src = tmp.newFolder("s"); File(src, "big").writeBytes(ByteArray(100_000) { it.toByte() })
        val tar = File(tmp.root, "t.tar"); sh(src, "tar", "-cf", tar.absolutePath, "big")
        val cut = tar.readBytes().copyOf(30_000)
        try { TarExtractor.extract(cut.inputStream(), tmp.newFolder("o"), JvmFileOps); fail() }
        catch (e: TarExtractor.BadArchive) { assertTrue(e.message!!.contains("incomplete")) }
    }

    @Test fun picksNewestUbuntuBase() {
        val sums = """
            1111111111111111111111111111111111111111111111111111111111111111 *ubuntu-base-24.04-base-arm64.tar.gz
            2222222222222222222222222222222222222222222222222222222222222222 *ubuntu-base-24.04.3-base-arm64.tar.gz
            3333333333333333333333333333333333333333333333333333333333333333 *ubuntu-base-24.04.3-base-amd64.tar.gz
            4444444444444444444444444444444444444444444444444444444444444444 *ubuntu-base-24.04.3-base-arm64.manifest
        """.trimIndent()
        assertEquals("2222222222222222222222222222222222222222222222222222222222222222" to "ubuntu-base-24.04.3-base-arm64.tar.gz", UbuntuBase.pick(sums, "arm64"))
        assertEquals("ubuntu-base-24.04.3-base-amd64.tar.gz", UbuntuBase.pick(sums, "amd64")!!.second)
        assertNull(UbuntuBase.pick(sums, "riscv64"))
    }

    @Test fun prootCommandLine() {
        val spec = ProotSpec("/n/libproot.so", "/n/libproot-loader.so", null, "/n", "/f/tmp", "/f/ubuntu",
            listOf("/dev" to "/dev", "/p/x" to "/root/projects/x"), "/root/projects/x", mapOf("CODRIOD_MAP" to "/root/projects/x=/p/x"), noSeccomp = true)
        val argv = spec.argv(listOf("/bin/bash", "--login"))
        assertEquals("/n/libproot.so", argv[0])
        assertTrue(argv.containsAll(listOf("--kill-on-exit", "--link2symlink", "-0", "-L", "--sysvipc")))
        assertEquals("/f/ubuntu", argv[argv.indexOf("-r") + 1])
        assertTrue(argv.contains("/dev") && argv.contains("/p/x:/root/projects/x"))
        assertEquals("/root/projects/x", argv[argv.indexOf("-w") + 1])
        val envStart = argv.indexOf("-i") + 1
        assertTrue(argv.subList(envStart, argv.size).any { it.startsWith("PATH=/opt/node/bin:") })
        assertTrue(argv.contains("CODRIOD_MAP=/root/projects/x=/p/x"))
        assertEquals(listOf("/bin/bash", "--login"), argv.takeLast(2))
        val env = spec.hostEnv()
        assertEquals("/n/libproot-loader.so", env["PROOT_LOADER"])
        assertEquals("/n", env["LD_LIBRARY_PATH"])
        assertEquals("1", env["PROOT_NO_SECCOMP"])
        assertFalse(env.containsKey("PROOT_LOADER_32"))
        val binds = ProotBinds.standard("/f/ubuntu", mapOf("/proc/stat" to "/f/proc/stat"))
        assertTrue(binds.contains("/f/proc/stat" to "/proc/stat"))
        assertTrue(binds.contains("/f/ubuntu/tmp" to "/dev/shm"))
        assertTrue(ProotBinds.fakeProcFiles(8)["/proc/stat"]!!.contains("cpu7 "))
    }

    @Test fun rootfsFiles() {
        val files = RootfsSetup.files(1, "/data/ipc", "/data/ubuntu")
        assertEquals("nameserver 1.1.1.1\nnameserver 9.9.9.9\n", files["/etc/resolv.conf"])
        assertTrue(files["/etc/apt/apt.conf.d/99codriod"]!!.contains("APT::Sandbox::User \"root\";"))
        assertTrue(files.keys.containsAll(RootfsSetup.executables))
        assertTrue(LinuxScripts.base.contains(RootfsSetup.BASHRC_HOOK))
        assertTrue(LinuxScripts.node().contains("latest-v22.x") && LinuxScripts.node().contains("sha256sum -c"))
    }

    /** The Ubuntu-side `codriod` command speaks the app's file protocol and maps the project folder to the phone path. */
    @Test fun codriodScriptInUbuntu() {
        val ipc = tmp.newFolder("ipc")
        val script = File(tmp.root, "codriod").apply { writeText(RootfsSetup.codriodScript(ipc.absolutePath, "/data/ubuntu")); setExecutable(true) }
        val project = tmp.newFolder("guestproj")
        val answer = Thread {
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) {
                val req = File(ipc, "req").listFiles()?.firstOrNull { it.name.endsWith(".req") }
                if (req != null) {
                    val lines = req.readLines()
                    val id = req.name.removeSuffix(".req")
                    File(ipc, "res/$id.out").writeText("cwd=${lines[0]} args=${lines.drop(1).joinToString(",")}\n")
                    File(ipc, "res/$id.code").writeText("7")
                    File(ipc, "res/$id.done").writeText("")
                    req.delete(); return@Thread
                }
                Thread.sleep(20)
            }
        }.apply { start() }
        val p = ProcessBuilder("/bin/sh", script.absolutePath, "open-url", "https://x.dev/a b").directory(project).redirectErrorStream(true)
        p.environment()["CODRIOD_MAP"] = "${project.absolutePath}=/data/projects/p1"
        p.environment()["PWD"] = project.absolutePath
        val proc = p.start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor(15, TimeUnit.SECONDS); answer.join()
        assertEquals("cwd=/data/projects/p1 args=open-url,https://x.dev/a b\n", out)
        assertEquals(7, proc.exitValue())
    }

    @Test fun cliCatalog() {
        assertEquals("2.1.3", CliCatalog.parseVersion("2.1.3 (Claude Code)"))
        assertEquals("0.167.0", CliCatalog.parseVersion("codex-cli 0.167.0"))
        assertEquals("1.0.108", CliCatalog.parseVersion("GitHub Copilot CLI 1.0.108.\nRun 'copilot --help'"))
        assertNull(CliCatalog.parseVersion("command not found"))
        assertEquals(CliCatalog.all.size, CliCatalog.all.map { it.id }.toSet().size)
        assertTrue(CliCatalog.installScript(CliCatalog.byId("claude")!!).contains("npm install -g --no-fund --no-audit \"@anthropic-ai/claude-code@latest\""))
        assertTrue(CliCatalog.installScript(CliCatalog.byId("gh")!!).contains("apt-get install -y --no-install-recommends gh"))
        assertTrue(CliCatalog.all.filter { it.kind == CliKind.Npm }.all { it.needsNode })
    }

    @Test fun doctorRecognizesKnownErrors() {
        fun ids(text: String, code: Int? = null) = LinuxDoctor.diagnose(text, code).map { it.id }
        assertTrue("dns" in ids("Err:1 http://ports.ubuntu.com noble InRelease\n  Temporary failure resolving 'ports.ubuntu.com'"))
        assertTrue("apt-lock" in ids("E: Could not get lock /var/lib/dpkg/lock-frontend. It is held by process 123"))
        assertTrue("apt-lists" in ids("E: Failed to fetch … Hash Sum mismatch"))
        assertTrue("apt-lists" in ids("E: Release file for http://ports.ubuntu.com/ubuntu-ports/dists/noble-updates/InRelease is not valid yet (invalid for another 2h)"))
        assertTrue("npm-integrity" in ids("npm ERR! code EINTEGRITY\nnpm ERR! sha512-abc integrity checksum failed"))
        assertTrue("npm-notempty" in ids("npm error code ENOTEMPTY\nnpm error syscall rename"))
        assertTrue("network" in ids("npm ERR! code ECONNRESET"))
        assertTrue("node-old" in ids("npm WARN EBADENGINE Unsupported engine { required: { node: '>=22.0.0' } }"))
        assertTrue("node-missing" in ids("/usr/bin/env: 'node': No such file or directory"))
        assertTrue("disk" in ids("npm ERR! code ENOSPC"))
        assertTrue("proot-seccomp" in ids("proot warning: ... Function not implemented"))
        assertTrue("ssl" in ids("curl: (60) SSL certificate problem: unable to get local issuer certificate"))
        assertTrue("memory" in ids("Killed", 137))
        assertTrue("memory" in ids("something", 137))
        assertTrue(ids("everything is fine").isEmpty())
        val dns = LinuxDoctor.diagnose("Temporary failure resolving 'x'")
        assertEquals(LinuxDoctor.Action.Host(LinuxDoctor.HostFix.RotateDns), dns.first().action)
        assertEquals("curl: (6) Could not resolve host: nodejs.org",
            LinuxDoctor.summary("::step x\ncurl: (6) Could not resolve host: nodejs.org\nmore text"))
        assertEquals("E: Unable to locate package foo", LinuxDoctor.summary("Reading package lists...\nE: Unable to locate package foo\n"))
    }
}
