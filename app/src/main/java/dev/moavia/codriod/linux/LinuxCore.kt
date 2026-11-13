package dev.moavia.codriod.linux

import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

// =====================================================================================================
// Tar extraction (ustar, GNU long names, pax), with symlinks; hard links become copies because Android
// does not let apps create hard links.
// =====================================================================================================

/** File system calls that differ between Android (android.system.Os) and the JVM (tests). */
interface FileOps {
    fun symlink(target: String, path: File)
    fun chmod(path: File, mode: Int)
    fun isSymlink(path: File): Boolean
}

class CountingInputStream(input: InputStream, private val onCount: (Long) -> Unit) : FilterInputStream(input) {
    var count = 0L
        private set
    override fun read(): Int = super.read().also { if (it >= 0) { count++; onCount(count) } }
    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) { count += it; onCount(count) } }
}

object TarExtractor {
    class BadArchive(message: String) : IOException(message)

    private fun readFully(input: InputStream, buffer: ByteArray, length: Int = buffer.size): Boolean {
        var read = 0
        while (read < length) {
            val n = input.read(buffer, read, length - read)
            if (n < 0) { if (read == 0) return false; throw BadArchive("The download is incomplete (archive ends early)") }
            read += n
        }
        return true
    }

    private fun skip(input: InputStream, bytes: Long) {
        var left = bytes
        val buffer = ByteArray(8192)
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) throw BadArchive("The download is incomplete (archive ends early)")
            left -= n
        }
    }

    private fun text(header: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && header[end] != 0.toByte()) end++
        return String(header, offset, end - offset, Charsets.UTF_8)
    }

    internal fun number(header: ByteArray, offset: Int, length: Int): Long {
        if (header[offset].toInt() and 0x80 != 0) { // GNU base-256
            var value = 0L
            for (i in offset + 1 until offset + length) value = (value shl 8) or (header[i].toLong() and 0xff)
            return value
        }
        val s = text(header, offset, length).trim().trim('\u0000')
        return if (s.isEmpty()) 0 else s.toLong(8)
    }

    private fun paxRecords(data: ByteArray): Map<String, String> {
        val result = HashMap<String, String>()
        var i = 0
        while (i < data.size) {
            val space = (i until data.size).firstOrNull { data[it] == ' '.code.toByte() } ?: break
            val length = String(data, i, space - i).toIntOrNull() ?: break
            if (length <= 0 || i + length > data.size) break
            val record = String(data, space + 1, i + length - space - 2, Charsets.UTF_8)
            val eq = record.indexOf('=')
            if (eq > 0) result[record.substring(0, eq)] = record.substring(eq + 1)
            i += length
        }
        return result
    }

    /** Normalizes an entry name and refuses names that leave [dest]. */
    internal fun safePath(dest: File, name: String): File? {
        val clean = name.replace('\\', '/').trimStart('/').removePrefix("./").trimEnd('/')
        if (clean.isEmpty() || clean == ".") return null
        if (clean.split('/').any { it == ".." }) throw BadArchive("Unsafe path in archive: $name")
        return File(dest, clean)
    }

    /**
     * Extracts an uncompressed tar stream into [dest]. Returns the number of entries.
     * [stripComponents] drops leading path parts like `tar --strip-components`.
     */
    fun extract(input: InputStream, dest: File, ops: FileOps, stripComponents: Int = 0): Int {
        dest.mkdirs()
        val header = ByteArray(512)
        var longName: String? = null
        var longLink: String? = null
        var pax: Map<String, String> = emptyMap()
        var entries = 0
        val buffer = ByteArray(64 * 1024)
        val dirModes = ArrayList<Pair<File, Int>>()
        while (true) {
            if (!readFully(input, header)) break
            if (header.all { it == 0.toByte() }) break
            val type = header[156].toInt().toChar()
            val size = number(header, 124, 12)
            val padded = (size + 511) / 512 * 512
            when (type) {
                'L', 'K' -> {
                    val data = ByteArray(size.toInt()); readFully(input, data); skip(input, padded - size)
                    val value = String(data, Charsets.UTF_8).trimEnd('\u0000')
                    if (type == 'L') longName = value else longLink = value
                    continue
                }
                'x' -> {
                    val data = ByteArray(size.toInt()); readFully(input, data); skip(input, padded - size)
                    pax = paxRecords(data); continue
                }
                'g' -> { skip(input, padded); continue }
            }
            val prefix = if (text(header, 257, 6).startsWith("ustar")) text(header, 345, 155) else ""
            var name = pax["path"] ?: longName ?: (if (prefix.isNotEmpty()) "$prefix/" else "") + text(header, 0, 100)
            val link = pax["linkpath"] ?: longLink ?: text(header, 157, 100)
            longName = null; longLink = null; pax = emptyMap()
            val mode = number(header, 100, 8).toInt() and 0xfff
            if (stripComponents > 0) {
                val parts = name.trimStart('/').removePrefix("./").split('/')
                if (parts.size <= stripComponents) { skip(input, padded); continue }
                name = parts.drop(stripComponents).joinToString("/")
            }
            val target = safePath(dest, name)
            if (target == null) { skip(input, padded); continue }
            entries++
            when (type) {
                '5' -> { target.mkdirs(); dirModes += target to mode; skip(input, padded) }
                '2' -> {
                    target.parentFile?.mkdirs()
                    if (target.exists() || ops.isSymlink(target)) remove(target, ops)
                    ops.symlink(link, target)
                    skip(input, padded)
                }
                '1' -> {
                    val strippedLink = if (stripComponents > 0) link.trimStart('/').removePrefix("./").split('/').drop(stripComponents).joinToString("/") else link
                    val source = safePath(dest, strippedLink)
                    target.parentFile?.mkdirs()
                    if (target.exists() || ops.isSymlink(target)) remove(target, ops)
                    if (source != null && source.isFile) { source.copyTo(target, overwrite = true); ops.chmod(target, mode) }
                    skip(input, padded)
                }
                '0', '\u0000', '7' -> {
                    target.parentFile?.mkdirs()
                    if (ops.isSymlink(target) || target.isDirectory) remove(target, ops)
                    target.outputStream().use { out ->
                        var left = size
                        while (left > 0) {
                            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                            if (n < 0) throw BadArchive("The download is incomplete (archive ends early)")
                            out.write(buffer, 0, n); left -= n
                        }
                    }
                    skip(input, padded - size)
                    ops.chmod(target, mode and 0x1ff or 0x180) // keep owner read/write
                }
                else -> skip(input, padded) // devices and fifos are not needed
            }
        }
        dirModes.asReversed().forEach { (dir, mode) -> runCatching { ops.chmod(dir, (mode and 0x1ff) or 0x1c0) } }
        return entries
    }

    private fun remove(file: File, ops: FileOps) {
        if (ops.isSymlink(file)) { file.delete(); return }
        file.deleteRecursively()
    }
}

// =====================================================================================================
// proot command line
// =====================================================================================================

/** Everything needed to start a program inside the Ubuntu folder with proot. */
data class ProotSpec(
    val proot: String,
    val loader: String,
    val loader32: String?,
    val libDir: String,
    val tmpDir: String,
    val rootfs: String,
    /** host path to guest path */
    val binds: List<Pair<String, String>> = emptyList(),
    val cwd: String = "/root",
    val env: Map<String, String> = emptyMap(),
    val noSeccomp: Boolean = false
) {
    /** The argv for ProcessBuilder / the terminal: proot … /usr/bin/env -i VARS… command… */
    fun argv(command: List<String>): List<String> = buildList {
        add(proot)
        add("--kill-on-exit")
        add("--link2symlink")
        add("-0")
        add("-r"); add(rootfs)
        add("-L")
        add("--sysvipc")
        binds.forEach { (host, guest) -> add("-b"); add(if (host == guest) host else "$host:$guest") }
        add("-w"); add(cwd)
        add("/usr/bin/env"); add("-i")
        (DEFAULT_ENV + env).forEach { (k, v) -> add("$k=$v") }
        addAll(command)
    }

    /** Environment of the proot process itself (on the Android side). */
    fun hostEnv(): Map<String, String> = buildMap {
        put("PROOT_LOADER", loader)
        loader32?.let { put("PROOT_LOADER_32", it) }
        put("PROOT_TMP_DIR", tmpDir)
        put("LD_LIBRARY_PATH", libDir)
        if (noSeccomp) put("PROOT_NO_SECCOMP", "1")
    }

    companion object {
        // /opt/jdk, /opt/kotlin and /opt/gradle hold the Java & Kotlin add-on's tools (when installed)
        const val PATH = "/opt/node/bin:/opt/jdk/bin:/opt/kotlin/bin:/opt/gradle/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/root/.local/bin"
        val DEFAULT_ENV = linkedMapOf(
            "HOME" to "/root", "USER" to "root", "LOGNAME" to "root", "SHELL" to "/bin/bash",
            "PATH" to PATH, "LANG" to "C.UTF-8", "LC_ALL" to "C.UTF-8", "TERM" to "xterm-256color", "COLORTERM" to "truecolor",
            "TMPDIR" to "/tmp", "BROWSER" to "/usr/local/bin/xdg-open", "DEBIAN_FRONTEND" to "noninteractive",
            "NODE_OPTIONS" to "--max-old-space-size=2048", "CODRIOD" to "1")
    }
}

/** Standard binds (like proot-distro), plus fake /proc files where Android hides the real ones. */
object ProotBinds {
    fun standard(rootfs: String, fakeProc: Map<String, String>): List<Pair<String, String>> = buildList {
        add("/dev" to "/dev")
        add("/dev/urandom" to "/dev/random")
        add("/proc" to "/proc")
        add("/proc/self/fd" to "/dev/fd")
        add("/proc/self/fd/0" to "/dev/stdin")
        add("/proc/self/fd/1" to "/dev/stdout")
        add("/proc/self/fd/2" to "/dev/stderr")
        add("/sys" to "/sys")
        add("$rootfs/tmp" to "/dev/shm")
        fakeProc.forEach { (guest, host) -> add(host to guest) }
    }

    /** Contents for /proc files that Android 8+ does not let apps read (Node and others read them). */
    fun fakeProcFiles(cpus: Int): Map<String, String> = mapOf(
        "/proc/loadavg" to "0.12 0.07 0.02 2/165 765\n",
        "/proc/stat" to buildString {
            append("cpu  1957 0 2877 93280 262 342 254 87 0 0\n")
            repeat(cpus.coerceIn(1, 16)) { append("cpu$it 31 0 64 1220 4 4 3 1 0 0\n") }
            append("intr 63361 0 0 0 0 0 0 0 0 0\nctxt 38014093\nbtime 1694292441\nprocesses 26442\nprocs_running 1\nprocs_blocked 0\nsoftirq 75663 0 5903 6 25375 10774 0 243 11685 0 21677\n")
        },
        "/proc/uptime" to "124.08 932.80\n",
        "/proc/version" to "Linux version 6.2.1-codriod (codriod@android) #1 SMP PREEMPT_DYNAMIC\n",
        "/proc/vmstat" to "nr_free_pages 1743136\nnr_zone_inactive_anon 179281\nnr_zone_active_anon 7183\npgpgin 890508\npgpgout 0\n",
        "/proc/sys/kernel/cap_last_cap" to "40\n",
        "/proc/sys/fs/inotify/max_user_watches" to "4096\n")
}

// =====================================================================================================
// Files written into Ubuntu so it works under proot on a phone
// =====================================================================================================

object RootfsSetup {
    val nameservers = listOf(listOf("8.8.8.8", "1.1.1.1"), listOf("1.1.1.1", "9.9.9.9"), listOf("8.8.4.4", "208.67.222.222"))

    /** guest path to content. [dnsSet] picks the DNS servers (the Doctor rotates them). */
    fun files(dnsSet: Int = 0, ipcHostDir: String, hostRootfs: String): Map<String, String> = linkedMapOf(
        "/etc/resolv.conf" to nameservers[dnsSet.mod(nameservers.size)].joinToString("") { "nameserver $it\n" },
        "/etc/hosts" to "127.0.0.1 localhost localhost.localdomain\n::1 localhost ip6-localhost ip6-loopback\n",
        "/etc/hostname" to "codriod\n",
        "/etc/apt/apt.conf.d/99codriod" to """
            APT::Sandbox::User "root";
            Acquire::Retries "5";
            Acquire::http::Timeout "60";
            Acquire::https::Timeout "60";
            Dpkg::Use-Pty "0";
            APT::Install-Recommends "false";
            APT::Get::Assume-Yes "true";
        """.trimIndent() + "\n",
        "/etc/dpkg/dpkg.cfg.d/99codriod" to "force-unsafe-io\n",
        "/etc/profile.d/codriod.sh" to """
            # Codriod: written by the app
            export PATH="${ProotSpec.PATH}"
            export LANG=C.UTF-8 BROWSER=/usr/local/bin/xdg-open
            [ -n "${'$'}PS1" ] && PS1='\[\e[1;32m\]codriod@ubuntu\[\e[0m\]:\[\e[1;34m\]\w\[\e[0m\]# '
            alias ll='ls -la' la='ls -A'
        """.trimIndent() + "\n",
        "/root/.bashrc.codriod" to "[ -f /etc/profile.d/codriod.sh ] && . /etc/profile.d/codriod.sh\n",
        "/usr/local/bin/codriod" to codriodScript(ipcHostDir, hostRootfs),
        "/usr/local/bin/xdg-open" to "#!/bin/sh\nexec /usr/local/bin/codriod open \"\$@\"\n",
        "/usr/local/bin/termux-open-url" to "#!/bin/sh\nexec /usr/local/bin/codriod open-url \"\$@\"\n",
        "/usr/local/bin/termux-open" to "#!/bin/sh\nexec /usr/local/bin/codriod open \"\$@\"\n",
        "/usr/local/bin/termux-clipboard-get" to "#!/bin/sh\nexec /usr/local/bin/codriod clipboard get\n",
        "/usr/local/bin/termux-clipboard-set" to "#!/bin/sh\nexec /usr/local/bin/codriod clipboard set \"\$@\"\n",
        "/usr/local/bin/termux-share" to "#!/bin/sh\nexec /usr/local/bin/codriod share \"\$@\"\n",
        "/usr/local/bin/android-build" to AndroidDev.BUILD_SCRIPT,
        "/usr/local/bin/android-logs" to AndroidDev.LOGS_SCRIPT)

    val executables = setOf("/usr/local/bin/codriod", "/usr/local/bin/xdg-open", "/usr/local/bin/termux-open-url",
        "/usr/local/bin/termux-open", "/usr/local/bin/termux-clipboard-get", "/usr/local/bin/termux-clipboard-set",
        "/usr/local/bin/termux-share", "/usr/local/bin/android-build", "/usr/local/bin/android-logs")

    /**
     * The `codriod` command inside Ubuntu: same file protocol as the phone shell's function, with the current
     * folder translated to the phone's path (CODRIOD_MAP="guest=host" for the bound project).
     */
    fun codriodScript(ipcHostDir: String, hostRootfs: String) = """
#!/bin/sh
# Codriod phone commands inside Ubuntu (written by the app).
ipc="${'$'}{CODRIOD_IPC:-$ipcHostDir}"
p="${'$'}PWD"
if [ -n "${'$'}CODRIOD_MAP" ]; then
  g="${'$'}{CODRIOD_MAP%%=*}"; h="${'$'}{CODRIOD_MAP#*=}"
  case "${'$'}p" in "${'$'}g"|"${'$'}g"/*) p="${'$'}h${'$'}{p#${'$'}g}" ;; *) p="$hostRootfs${'$'}p" ;; esac
else
  p="$hostRootfs${'$'}p"
fi
mkdir -p "${'$'}ipc/req" "${'$'}ipc/res" 2>/dev/null
id="u${'$'}${'$'}-${'$'}(date +%s%N 2>/dev/null || date +%s)"
{
  printf '%s\n' "${'$'}p"
  for a in "${'$'}@"; do printf '%s' "${'$'}a" | tr '\n' '\036'; printf '\n'; done
} > "${'$'}ipc/req/${'$'}id.tmp"
if [ "${'$'}1" = clipboard ] && [ "${'$'}2" = set ] && [ ${'$'}# -lt 3 ] && [ ! -t 0 ]; then cat > "${'$'}ipc/req/${'$'}id.in"; fi
mv "${'$'}ipc/req/${'$'}id.tmp" "${'$'}ipc/req/${'$'}id.req"
n=0
while [ ! -f "${'$'}ipc/res/${'$'}id.done" ]; do
  sleep 0.1 2>/dev/null || sleep 1
  n=${'$'}((n + 1))
  limit=300; case "${'$'}1" in install|uninstall) limit=6000 ;; esac
  if [ ${'$'}n -gt ${'$'}limit ]; then echo "codriod: the Codriod app did not answer; keep it open and try again" >&2; rm -f "${'$'}ipc/req/${'$'}id.req"; exit 1; fi
done
cat "${'$'}ipc/res/${'$'}id.out" 2>/dev/null
code=${'$'}(cat "${'$'}ipc/res/${'$'}id.code" 2>/dev/null)
rm -f "${'$'}ipc/res/${'$'}id.out" "${'$'}ipc/res/${'$'}id.code" "${'$'}ipc/res/${'$'}id.done"
exit ${'$'}{code:-1}
""".trimStart()

    /** Lines appended once to /root/.bashrc. */
    const val BASHRC_HOOK = "[ -f /root/.bashrc.codriod ] && . /root/.bashrc.codriod"
}

// =====================================================================================================
// Install scripts (run inside Ubuntu). Lines starting with "::step " report progress to the app.
// =====================================================================================================

object LinuxScripts {
    const val BASE_PACKAGES = "ca-certificates curl wget git xz-utils unzip zip less nano procps openssh-client " +
        "python3 python3-pip python3-venv gnupg file tzdata"

    val base = """
set -e
export DEBIAN_FRONTEND=noninteractive
echo "::step Updating package lists"
echo "::progress 2"
apt-get update
echo "::step Installing basic tools (git, curl, python)"
echo "::progress 10"
apt-get install -y --no-install-recommends -o APT::Status-Fd=1 $BASE_PACKAGES
grep -qF '${RootfsSetup.BASHRC_HOOK}' /root/.bashrc 2>/dev/null || echo '${RootfsSetup.BASHRC_HOOK}' >> /root/.bashrc
echo "::step Basic tools ready"
""".trimStart()

    /** Installs the newest Node.js [major] from nodejs.org into /opt/node, checking the SHA-256. */
    fun node(major: Int = 22) = """
set -e
case "${'$'}(uname -m)" in
  aarch64|arm64) arch=arm64 ;;
  x86_64|amd64) arch=x64 ;;
  *) echo "::fail Node.js needs a 64-bit phone (this one is ${'$'}(uname -m))"; exit 3 ;;
esac
base=https://nodejs.org/dist/latest-v$major.x
echo "::step Finding the latest Node.js $major"
echo "::progress 3"
sums=${'$'}(curl -fsSL --retry 4 "${'$'}base/SHASUMS256.txt")
file=${'$'}(printf '%s\n' "${'$'}sums" | grep -o "node-v[0-9.]*-linux-${'$'}arch.tar.xz" | head -n 1)
[ -n "${'$'}file" ] || { echo "::fail Could not find Node.js for ${'$'}arch"; exit 4; }
echo "::step Downloading ${'$'}file"
echo "::progress 8"
curl -fL --retry 4 --retry-delay 3 -o "/tmp/${'$'}file" "${'$'}base/${'$'}file"
echo "::step Checking the download"
echo "::progress 65"
printf '%s\n' "${'$'}sums" | grep " ${'$'}file${'$'}" | (cd /tmp && sha256sum -c -)
echo "::step Unpacking Node.js"
echo "::progress 70"
rm -rf /opt/node.new && mkdir -p /opt/node.new
tar -xJf "/tmp/${'$'}file" -C /opt/node.new --strip-components=1
rm -rf /opt/node && mv /opt/node.new /opt/node && rm -f "/tmp/${'$'}file"
# npm/npx/corepack as small scripts instead of symlinks: work the same on every proot version
for t in npm:npm/bin/npm-cli.js npx:npm/bin/npx-cli.js corepack:corepack/dist/corepack.js; do
  n=${'$'}{t%%:*}; js=/opt/node/lib/node_modules/${'$'}{t#*:}
  if [ -f "${'$'}js" ]; then rm -f "/opt/node/bin/${'$'}n"; printf '#!/bin/sh\nexec /opt/node/bin/node %s "${'$'}@"\n' "${'$'}js" > "/opt/node/bin/${'$'}n"; chmod 755 "/opt/node/bin/${'$'}n"; fi
done
export PATH=/opt/node/bin:${'$'}PATH
echo "::progress 95"
npm config set fund false
npm config set audit false
npm config set update-notifier false
npm config set fetch-retries 5
npm config set fetch-retry-maxtimeout 120000
echo "::step Node.js ${'$'}(node --version), npm ${'$'}(npm --version)"
""".trimStart()

    fun npmInstall(pkg: String) = """
set -e
export PATH=/opt/node/bin:${'$'}PATH
command -v npm >/dev/null || { echo "node: not found"; exit 127; }
echo "::step Installing $pkg"
npm install -g --no-fund --no-audit ${pkg.split(' ').joinToString(" ") { "\"$it@latest\"" }}
""".trimStart()

    fun npmRemove(pkg: String) = "export PATH=/opt/node/bin:\$PATH\nnpm uninstall -g \"$pkg\"\n"

    fun apt(packages: String) = "set -e\nexport DEBIAN_FRONTEND=noninteractive\necho \"::step Installing $packages\"\n" +
        "apt-get install -y --no-install-recommends -o APT::Status-Fd=1 $packages\n"

    fun shell(command: String) = "set -e\nexport PATH=/opt/node/bin:\$PATH\n$command\n"
}

// =====================================================================================================
// The official CLIs Codriod can install
// =====================================================================================================

enum class CliKind { Npm, Apt, Script }

data class CliSpec(
    val id: String,
    val name: String,
    val vendor: String,
    val command: String,
    val description: String,
    val signIn: String,
    val kind: CliKind,
    val packageName: String,
    /** Typed in the terminal to sign in; null = start the CLI, it asks on first run. */
    val loginCommand: String? = null,
    val versionCommand: String = "$command --version",
    val color: Long = 0xFF7C3AED,
    val needsNode: Boolean = kind == CliKind.Npm,
    val docs: String = "",
    val experimental: Boolean = false,
    val installScript: String? = null,
    /** "codex" (app-server, full panel), "acp" (Agent Client Protocol panel) or null (terminal only). */
    val protocol: String? = null,
    /** Ways to start ACP mode, tried in order. */
    val acpCommands: List<List<String>> = emptyList(),
    /** Extra npm packages installed with the CLI (e.g. the ACP adapter). */
    val extraPackages: List<String> = emptyList(),
    /** Copilot: each prompt uses a premium request. */
    val countsRequests: Boolean = false
)

object CliCatalog {
    val all = listOf(
        CliSpec("claude", "Claude Code", "Anthropic", "claude",
            "Anthropic's coding agent in the terminal.", "Claude Pro/Max account or Anthropic API key",
            CliKind.Npm, "@anthropic-ai/claude-code", loginCommand = "claude", color = 0xFFD97757, docs = "https://docs.claude.com/en/docs/claude-code",
            protocol = "acp", acpCommands = listOf(listOf("claude-code-acp")), extraPackages = listOf("@zed-industries/claude-code-acp")),
        CliSpec("codex", "Codex CLI", "OpenAI", "codex",
            "OpenAI's coding agent.", "ChatGPT Plus/Pro account or OpenAI API key",
            CliKind.Npm, "@openai/codex", loginCommand = "codex login", color = 0xFF10A37F, docs = "https://developers.openai.com/codex/cli",
            protocol = "codex"),
        CliSpec("gemini", "Gemini CLI", "Google", "gemini",
            "Google's open-source agent with a free tier.", "Google account (free) or Gemini API key",
            CliKind.Npm, "@google/gemini-cli", loginCommand = "gemini", color = 0xFF4285F4, docs = "https://github.com/google-gemini/gemini-cli",
            protocol = "acp", acpCommands = listOf(listOf("gemini", "--experimental-acp"), listOf("gemini", "--acp"))),
        CliSpec("copilot", "GitHub Copilot CLI", "GitHub", "copilot",
            "Copilot's coding agent in the terminal.", "GitHub account with Copilot",
            CliKind.Npm, "@github/copilot", loginCommand = "copilot login", color = 0xFF24292F, docs = "https://docs.github.com/copilot",
            protocol = "acp", acpCommands = listOf(listOf("copilot", "--acp"), listOf("copilot", "--acp", "--stdio")), countsRequests = true),
        CliSpec("qwen", "Qwen Code", "Alibaba", "qwen",
            "Qwen's coding agent with a free sign-in.", "Qwen account (free) or API key",
            CliKind.Npm, "@qwen-code/qwen-code", loginCommand = "qwen", color = 0xFF615CED, docs = "https://github.com/QwenLM/qwen-code",
            protocol = "acp", acpCommands = listOf(listOf("qwen", "--experimental-acp"), listOf("qwen", "--acp"))),
        CliSpec("opencode", "opencode", "SST", "opencode",
            "Open-source agent for any provider.", "Your provider keys or accounts",
            CliKind.Npm, "opencode-ai", loginCommand = "opencode auth login", color = 0xFF111827, docs = "https://opencode.ai/docs",
            protocol = "acp", acpCommands = listOf(listOf("opencode", "acp"))),
        CliSpec("kilo", "Kilo Code CLI", "Kilo", "kilocode",
            "Kilo Code's agent in the terminal.", "Kilo account or provider keys",
            CliKind.Npm, "@kilocode/cli", color = 0xFFF8F675, docs = "https://kilocode.ai/docs", experimental = true,
            protocol = "acp", acpCommands = listOf(listOf("kilocode", "--acp"), listOf("kilocode", "acp"))),
        CliSpec("crush", "Crush", "Charm", "crush",
            "Glamorous terminal agent for many providers.", "Your provider keys",
            CliKind.Npm, "@charmland/crush", color = 0xFFFF5FD2, docs = "https://github.com/charmbracelet/crush", experimental = true),
        CliSpec("amp", "Amp", "Amp", "amp",
            "Amp's frontier coding agent.", "Amp account",
            CliKind.Npm, "@ampcode/cli", loginCommand = "amp login", color = 0xFFF34E3F, docs = "https://ampcode.com/manual", experimental = true),
        CliSpec("gh", "GitHub CLI", "GitHub", "gh",
            "Pull requests, issues and Actions from the terminal.", "GitHub account (gh auth login)",
            CliKind.Apt, "gh", loginCommand = "gh auth login", color = 0xFF24292F, needsNode = false, docs = "https://cli.github.com/manual")
    )

    fun byId(id: String) = all.firstOrNull { it.id == id }

    fun installScript(cli: CliSpec): String = cli.installScript ?: when (cli.kind) {
        CliKind.Npm -> LinuxScripts.npmInstall((listOf(cli.packageName) + cli.extraPackages).joinToString(" "))
        CliKind.Apt -> LinuxScripts.apt(cli.packageName)
        CliKind.Script -> error("no script")
    }

    fun removeScript(cli: CliSpec): String = when (cli.kind) {
        CliKind.Npm -> LinuxScripts.npmRemove(cli.packageName)
        CliKind.Apt -> "apt-get remove -y ${cli.packageName}\n"
        CliKind.Script -> "rm -f \"\$(command -v ${cli.command})\"\n"
    }

    /** The first version-looking token of a `--version` output. */
    fun parseVersion(output: String): String? =
        Regex("\\d+\\.\\d+(\\.\\d+)?([-+.][0-9A-Za-z.]+)?").find(output)?.value
}

object UbuntuBase {
    const val URL = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/"

    /** Picks the newest `ubuntu-base-24.04.x-base-<arch>.tar.gz` from SHA256SUMS: (sha256, file name). */
    fun pick(sums: String, arch: String): Pair<String, String>? = sums.lines().mapNotNull { line ->
        val m = Regex("^([0-9a-f]{64})\\s+\\*?(ubuntu-base-24\\.04(?:\\.(\\d+))?-base-$arch\\.tar\\.gz)$").find(line.trim()) ?: return@mapNotNull null
        Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3].toIntOrNull() ?: 0)
    }.maxByOrNull { it.third }?.let { it.first to it.second }
}

// =====================================================================================================
// Android development inside Ubuntu: SDK, Gradle builds, install and logs
// =====================================================================================================

object AndroidDev {
    const val SDK = "/opt/android-sdk"
    const val BUILD_TOOLS = "35.0.0"
    const val PLATFORM = "android-35"
    const val LICENSE_URL = "https://developer.android.com/studio/terms"
    /** Community ARM64 builds of AOSP's build tools (aapt2, aidl, zipalign…): Google ships these only for x86_64 Linux. */
    const val ARM_TOOLS_REPO = "lzhiyong/android-sdk-tools"

    /** Installs JDK 17, the Android command-line tools, platform, build-tools and platform-tools (adb), with ARM tools on phones. */
    fun sdkScript() = """
set -e
export DEBIAN_FRONTEND=noninteractive
echo "::step Installing Java 17, unzip and adb"
echo "::progress 2"
apt-get install -y --no-install-recommends -o APT::Status-Fd=1 openjdk-17-jdk-headless unzip adb
export ANDROID_HOME=$SDK
mkdir -p ${'$'}ANDROID_HOME/cmdline-tools
echo "::step Finding Google's Android command-line tools"
echo "::progress 30"
curl -fsSL --retry 4 https://dl.google.com/android/repository/repository2-3.xml -o /tmp/android-repo.xml
cat > /tmp/android-find.py <<'PY'
import re
x = open('/tmp/android-repo.xml').read()
best = None
for m in re.finditer(r'<archive>(.*?)</archive>', x, re.S):
    a = m.group(1)
    u = re.search(r'<url>(commandlinetools-linux-(\d+)_latest\.zip)</url>', a)
    c = re.search(r'<checksum[^>]*>([0-9a-f]{40})</checksum>', a)
    if u and c and (best is None or int(u.group(2)) > best[0]):
        best = (int(u.group(2)), u.group(1), c.group(1))
if best: print(best[1], best[2])
PY
set -- ${'$'}(python3 /tmp/android-find.py)
zip=${'$'}{1:-}; sha=${'$'}{2:-}
[ -n "${'$'}zip" ] || { echo "::fail Could not find the Android command-line tools"; exit 4; }
echo "::step Downloading ${'$'}zip"
echo "::progress 33"
curl -fL --retry 4 -o "/tmp/${'$'}zip" "https://dl.google.com/android/repository/${'$'}zip"
echo "${'$'}sha  /tmp/${'$'}zip" | sha1sum -c -
rm -rf /tmp/clt ${'$'}ANDROID_HOME/cmdline-tools/latest && unzip -q -o "/tmp/${'$'}zip" -d /tmp/clt
mv /tmp/clt/cmdline-tools ${'$'}ANDROID_HOME/cmdline-tools/latest && rm -rf /tmp/clt "/tmp/${'$'}zip"
export PATH=${'$'}ANDROID_HOME/cmdline-tools/latest/bin:${'$'}PATH
echo "::step Accepting the Android SDK License you agreed to"
echo "::progress 45"
yes | sdkmanager --sdk_root=${'$'}ANDROID_HOME --licenses >/dev/null
echo "::step Downloading the $PLATFORM platform and build tools"
echo "::progress 50"
sdkmanager --sdk_root=${'$'}ANDROID_HOME "platforms;$PLATFORM" "build-tools;$BUILD_TOOLS" "platform-tools"
BT=${'$'}ANDROID_HOME/build-tools/$BUILD_TOOLS
echo "::progress 85"
case "${'$'}(uname -m)" in aarch64|arm64)
  echo "::step Installing ARM build tools (aapt2, aidl, zipalign)"
  url=${'$'}(curl -fsSL --retry 4 https://api.github.com/repos/$ARM_TOOLS_REPO/releases/latest 2>/dev/null | python3 -c 'import sys,json
try: d=json.load(sys.stdin)
except Exception: d={}
a=[x["browser_download_url"] for x in d.get("assets",[]) if ("aarch64" in x["name"] or "arm64" in x["name"]) and x["name"].endswith((".zip",".tar.gz",".tar.xz",".tgz"))]
a.sort(key=lambda u: ("static" not in u, u))
print(a[0] if a else "")' || true)
  [ -n "${'$'}url" ] || url="https://github.com/$ARM_TOOLS_REPO/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip"
  rm -rf /tmp/arm-tools && mkdir -p /tmp/arm-tools
  if curl -fL --retry 4 -o /tmp/arm-tools.pkg "${'$'}url"; then
    case "${'$'}url" in *.zip) unzip -q -o /tmp/arm-tools.pkg -d /tmp/arm-tools ;; *) tar -xf /tmp/arm-tools.pkg -C /tmp/arm-tools ;; esac
  else
    echo "ARM build tools not found online; using Ubuntu's aapt package (older)"
    apt-get install -y --no-install-recommends aapt
    mkdir -p /tmp/arm-tools/ubuntu
    for t in aapt aapt2; do if command -v ${'$'}t >/dev/null; then cp "${'$'}(command -v ${'$'}t)" /tmp/arm-tools/ubuntu/; fi; done
  fi
  for t in aapt aapt2 aidl zipalign dexdump split-select; do
    f=${'$'}(find /tmp/arm-tools -type f -name "${'$'}t" | head -n 1)
    if [ -n "${'$'}f" ]; then install -m 755 "${'$'}f" "${'$'}BT/${'$'}t"; fi
  done
  rm -rf /tmp/arm-tools /tmp/arm-tools.pkg
  ln -sf /usr/bin/adb ${'$'}ANDROID_HOME/platform-tools/adb
  AAPT2_OVERRIDE="android.aapt2FromMavenOverride=${'$'}BT/aapt2"
  ;;
esac
"${'$'}BT/aapt2" version
echo "::step Configuring Gradle for the phone"
echo "::progress 95"
mkdir -p /root/.gradle
cat > /root/.gradle/gradle.properties <<EOF3
# Written by Codriod for building on a phone
org.gradle.jvmargs=-Xmx1536m -XX:+UseParallelGC -Dfile.encoding=UTF-8
org.gradle.workers.max=2
org.gradle.caching=true
org.gradle.parallel=false
android.useAndroidX=true
${'$'}{AAPT2_OVERRIDE:-}
EOF3
cat > /etc/profile.d/android.sh <<EOF4
export ANDROID_HOME=$SDK ANDROID_SDK_ROOT=$SDK
export JAVA_HOME=${'$'}(dirname ${'$'}(dirname ${'$'}(readlink -f ${'$'}(command -v javac))))
export PATH=${'$'}ANDROID_HOME/cmdline-tools/latest/bin:${'$'}ANDROID_HOME/platform-tools:${'$'}BT:\${'$'}PATH
EOF4
echo "::step Android SDK ready ($PLATFORM, build-tools $BUILD_TOOLS)"
""".trimStart()

    /** `android-build [--install] [--release] [TASK]` in an Android project folder. */
    val BUILD_SCRIPT = """
#!/bin/bash
# Codriod: builds the Android project in this folder with Gradle and can install it on this phone.
#   android-build              debug APK (signed with the debug key, installable)
#   android-build --install    build, then install with Android's dialog (or silently over wireless adb)
#   android-build --release    release build
#   android-build TASK         any Gradle task, e.g. test or lint
set -e
[ -f /etc/profile.d/android.sh ] && . /etc/profile.d/android.sh
if [ -z "${'$'}ANDROID_HOME" ] || [ ! -d "${'$'}ANDROID_HOME/platforms" ]; then
  echo "android-build: the Android SDK is not installed. In Codriod: Add-ons → Linux & AI CLIs → Android SDK." >&2; exit 3
fi
install=0; task=assembleDebug
for a in "${'$'}@"; do case "${'$'}a" in --install) install=1 ;; --release) task=assembleRelease ;; *) task="${'$'}a" ;; esac; done
if [ ! -f settings.gradle ] && [ ! -f settings.gradle.kts ]; then echo "android-build: run it in the project folder (settings.gradle not found)" >&2; exit 4; fi
grep -qs '^sdk.dir=' local.properties || echo "sdk.dir=${'$'}ANDROID_HOME" >> local.properties
start=${'$'}(date +%s)
if [ -f ./gradlew ]; then sh ./gradlew "${'$'}task" --console=plain
elif command -v gradle >/dev/null; then gradle "${'$'}task" --console=plain
else echo "android-build: no gradlew in this project. Create one on a computer (gradle wrapper) or add the wrapper files." >&2; exit 5; fi
apk=${'$'}(find . -path '*/build/outputs/apk/*' -name '*.apk' -newermt "@${'$'}start" 2>/dev/null | head -n 1)
[ -z "${'$'}apk" ] && apk=${'$'}(find . -path '*/build/outputs/apk/*' -name '*.apk' 2>/dev/null | head -n 1)
if [ -n "${'$'}apk" ]; then
  echo "APK: ${'$'}apk"
  if [ ${'$'}install = 1 ]; then codriod install "${'$'}apk"; fi
fi
""".trimStart()

    /** `android-logs [PACKAGE]`: recent logcat (needs wireless debugging connected). */
    val LOGS_SCRIPT = """
#!/bin/bash
# Codriod: recent Android logs, e.g. why an app crashed. Needs wireless debugging (Add-ons → Linux & AI CLIs).
if ! adb get-state >/dev/null 2>&1; then
  echo "android-logs: connect wireless debugging first (Add-ons → Linux & AI CLIs → Wireless debugging)" >&2; exit 3
fi
if [ -n "${'$'}1" ]; then
  pid=${'$'}(adb shell pidof -s "${'$'}1" 2>/dev/null | tr -d '\r')
  if [ -n "${'$'}pid" ]; then adb logcat -d -t 400 --pid="${'$'}pid"; else
    echo "(${'$'}1 is not running; showing recent crashes)"; adb logcat -d -t 400 -b crash; adb logcat -d -t 200 AndroidRuntime:E '*:S'; fi
else adb logcat -d -t 300 '*:W'; fi
""".trimStart()

    /** What every coding agent (Codex, Copilot, Gemini, Claude Code, Qwen, opencode, Codriod AI) should know. */
    fun agentInstructions(sdkInstalled: Boolean, adbConnected: Boolean) = """
# Codriod phone environment (written by Codriod)

You are running inside Codriod on an Android phone: Ubuntu 24.04 under proot (no systemd, no Docker, you are root, no sudo).
The project is at /root/projects/<name>; the user sees the same files in Codriod's editor.

Phone commands (work here and in Codriod's terminal):
- `codriod install path/to/app.apk`: install an APK on this phone. Android shows the user a confirmation, except when
  wireless debugging is connected (silent). The command waits and prints the result: installed, cancelled, or why it failed.
- `codriod uninstall com.example.app`, `codriod launch com.example.app` (start the app after installing).
- `codriod open-url URL`, `codriod browse URL` (Codriod's browser), `codriod share FILE`, `codriod notify TEXT`, `codriod help`.

Android apps:
""".trimStart() + (if (sdkInstalled) """
- The Android SDK is installed (${'$'}ANDROID_HOME=$SDK, $PLATFORM, build-tools $BUILD_TOOLS, JDK 17). Use compileSdk/targetSdk 35 or lower,
  AGP 8.x, Kotlin, a Gradle wrapper (gradlew). Builds are slower than on a computer: keep dependencies small.
- Build: `android-build` (debug APK) or `android-build --install` (build and install). Any task: `android-build test`.
- On ARM, aapt2 comes from $ARM_TOOLS_REPO through android.aapt2FromMavenOverride in ~/.gradle/gradle.properties: do not remove it.
- If Gradle runs out of memory, lower org.gradle.jvmargs in ~/.gradle/gradle.properties or add --no-daemon.
""" else """
- The Android SDK is NOT installed yet. Ask the user to install it in Codriod: Add-ons → Linux & AI CLIs → Android SDK.
  Until then, build with GitHub Actions (gh CLI) and install the downloaded APK with `codriod install`.
""") + (if (adbConnected) """
- Wireless debugging is connected: `adb` works (install, shell, logcat). After installing, run the app with `codriod launch PKG`,
  and if it crashes read `android-logs PKG`, fix the code and build again.
""" else """
- To read crash logs (`android-logs PKG`, `adb logcat`), the user must connect wireless debugging in Codriod
  (Add-ons → Linux & AI CLIs → Wireless debugging). Without it you cannot read logs of other apps.
""") + """
Workflow for "build me an app": create the project (with gradlew), `android-build --install`, `codriod launch PKG`,
check logs, fix, repeat. Tell the user to tap Install in Android's dialog when it appears.
"""

    /** Where each CLI reads global instructions, inside Ubuntu's /root. */
    val instructionFiles = listOf(
        "/root/.codex/AGENTS.md", "/root/.gemini/GEMINI.md", "/root/.claude/CLAUDE.md", "/root/.qwen/QWEN.md",
        "/root/.copilot/copilot-instructions.md", "/root/.config/opencode/AGENTS.md", "/root/CODRIOD.md")

    const val BEGIN = "<!-- codriod:begin -->"
    const val END = "<!-- codriod:end -->"

    /** Puts [block] between Codriod's markers, keeping anything the user wrote in the file. */
    fun mergeInstructions(existing: String?, block: String): String {
        val section = "$BEGIN\n${block.trimEnd()}\n$END\n"
        if (existing.isNullOrBlank()) return section
        val start = existing.indexOf(BEGIN)
        val end = existing.indexOf(END)
        return if (start >= 0 && end > start) existing.substring(0, start) + section + existing.substring(end + END.length).trimStart('\n')
        else existing.trimEnd() + "\n\n" + section
    }
}
