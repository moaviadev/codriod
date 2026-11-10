package dev.moavia.codriod.linux

/**
 * Codriod Doctor: recognizes known Linux, apt, npm, network and proot errors in a step's output and repairs
 * them without AI. Each fix is tried at most once per step; then the step runs again.
 */
object LinuxDoctor {
    sealed class Action {
        /** Runs inside Ubuntu. */
        data class Shell(val script: String) : Action()
        /** Done by the app on the phone side. */
        data class Host(val kind: HostFix) : Action()
        /** Nothing to run: retry after a pause (network blips). */
        data class Wait(val seconds: Int) : Action()
        /** Cannot be fixed automatically; tell the user what to do. */
        data class Advice(val message: String) : Action()
    }

    enum class HostFix { RotateDns, DisableSeccomp, Reextract, DeletePartialDownload, CleanTemp, RewriteConfig }

    data class Fix(val id: String, val title: String, val action: Action)

    private data class Rule(val id: String, val title: String, val patterns: List<Regex>, val actions: List<Action>)

    private fun r(vararg p: String) = p.map { Regex(it, RegexOption.IGNORE_CASE) }

    private const val APT_UNLOCK = "rm -f /var/lib/dpkg/lock /var/lib/dpkg/lock-frontend /var/lib/apt/lists/lock /var/cache/apt/archives/lock\n" +
        "dpkg --configure -a || true\n"
    private const val APT_LISTS = "apt-get clean\nrm -rf /var/lib/apt/lists/*\nmkdir -p /var/lib/apt/lists/partial\n" +
        "printf 'Acquire::Check-Valid-Until \"false\";\\nAcquire::Check-Date \"false\";\\n' > /etc/apt/apt.conf.d/98codriod-clock\n"
    private const val NPM_CACHE = "export PATH=/opt/node/bin:\$PATH\nnpm cache clean --force || rm -rf /root/.npm/_cacache\n"
    private const val NPM_NOTEMPTY = "rm -rf /opt/node/lib/node_modules/.[!.]*-* /root/.npm/_cacache/tmp 2>/dev/null || true\n"

    private val rules = listOf(
        Rule("proot-seccomp", "proot: switching off seccomp acceleration",
            r("Function not implemented", "proot (error|warning).*seccomp", "Bad system call", "SIGSYS"),
            listOf(Action.Host(HostFix.DisableSeccomp))),
        Rule("rootfs-broken", "Ubuntu files are damaged: unpacking them again",
            r("""proot error: execve\("/usr/bin/env"\)""", "/usr/bin/env: No such file", "/bin/(ba)?sh: (No such file|not found)",
                "proot error: '/bin/(ba)?sh' not found", "can't sanitize binding"),
            listOf(Action.Host(HostFix.Reextract))),
        Rule("dns", "Network names don't resolve: trying other DNS servers",
            r("Temporary failure resolving", "Could not resolve host", "EAI_AGAIN", "getaddrinfo ENOTFOUND", "Name or service not known",
                "Temporary failure in name resolution"),
            listOf(Action.Host(HostFix.RotateDns), Action.Wait(3))),
        Rule("network", "Network was interrupted: retrying",
            r("ECONNRESET", "ETIMEDOUT", "ECONNREFUSED", "socket hang up", "network timeout", "ERR_SOCKET_TIMEOUT",
                "Connection timed out", "Connection reset by peer", "curl: \\(5[0-9]\\)", "curl: \\(28\\)", "curl: \\(18\\)",
                "Failed to fetch", "Unable to connect to", "503 Service Unavailable", "502 Bad Gateway"),
            listOf(Action.Wait(5))),
        Rule("ssl", "Secure connection failed: refreshing certificates",
            r("certificate verify failed", "SSL certificate problem", "UNABLE_TO_GET_ISSUER_CERT", "CERT_HAS_EXPIRED",
                "SELF_SIGNED_CERT", "server certificate verification failed"),
            listOf(Action.Shell("apt-get install -y --reinstall ca-certificates || true\nupdate-ca-certificates || true\n"),
                Action.Advice("If this keeps failing, check that the phone's date and time are correct."))),
        Rule("apt-lock", "Package manager was locked: unlocking",
            r("Could not get lock", "Unable to acquire the dpkg frontend lock", "dpkg was interrupted", "Unable to lock"),
            listOf(Action.Shell(APT_UNLOCK))),
        Rule("apt-lists", "Package lists are broken or out of date: refreshing",
            r("Hash Sum mismatch", "File has unexpected size", "is not valid yet", "Release file .* is expired",
                "Clearsigned file isn't valid", "The repository .* does not have a Release file", "Unable to locate package",
                "Package .* has no installation candidate"),
            listOf(Action.Shell(APT_LISTS + "apt-get update\n"))),
        Rule("apt-sandbox", "Package manager can't change user under proot: fixing its settings",
            r("seteuid", "setgroups .* failed", "_apt.*Permission denied", "Download is performed unsandboxed"),
            listOf(Action.Host(HostFix.RewriteConfig))),
        Rule("apt-broken", "Some packages are half installed: repairing",
            r("Unmet dependencies", "held broken packages", "dpkg: error processing", "Sub-process /usr/bin/dpkg returned an error",
                "dpkg: dependency problems"),
            listOf(Action.Shell(APT_UNLOCK + "apt-get -f install -y\n"))),
        Rule("npm-integrity", "npm's download cache is damaged: clearing it",
            r("EINTEGRITY", "integrity checksum failed", "_cacache", "Unexpected end of JSON input", "invalid json response body"),
            listOf(Action.Shell(NPM_CACHE))),
        Rule("npm-notempty", "A previous npm install was left half done: cleaning up",
            r("ENOTEMPTY", "EEXIST: file already exists", "npm ERR! code EEXIST"),
            listOf(Action.Shell(NPM_NOTEMPTY))),
        Rule("npm-perm", "File permissions are wrong: fixing them",
            r("EACCES", "EPERM: operation not permitted"),
            listOf(Action.Shell("chmod -R u+rwX /opt/node /root/.npm 2>/dev/null || true\n"))),
        Rule("node-missing", "Node.js is missing: installing it",
            r("node: not found", "npm: not found", "npm: command not found", "env: .?node.?: No such file", "/usr/bin/env: .?node"),
            listOf(Action.Shell(LinuxScripts.node()))),
        Rule("node-old", "Node.js is too old for this CLI: updating it",
            r("EBADENGINE", "Unsupported engine", "requires Node(\\.js)? (version )?>?=? ?v?2[0-9]", "SyntaxError: Unexpected token '\\?'"),
            listOf(Action.Shell(LinuxScripts.node()))),
        Rule("disk", "Phone storage is full: clearing caches",
            r("ENOSPC", "No space left on device"),
            listOf(Action.Shell("apt-get clean || true\n" + NPM_CACHE + "rm -rf /tmp/* /var/tmp/* 2>/dev/null || true\n"),
                Action.Host(HostFix.CleanTemp),
                Action.Advice("Storage is almost full. Free at least 1 GB on the phone if this fails again."))),
        Rule("download", "A download was cut off: downloading again",
            r("sha256sum: WARNING", "computed checksum did NOT match", ": FAILED$", "Unexpected end of input", "unexpected end of file",
                "gzip: stdin: unexpected end", "xz: \\(stdin\\)", "archive ends early"),
            listOf(Action.Host(HostFix.DeletePartialDownload), Action.Shell("rm -f /tmp/node-v*.tar.xz\n"))),
        Rule("gradle-memory", "Gradle ran out of memory: using less memory and no daemon",
            r("java.lang.OutOfMemoryError", "Java heap space", "Gradle build daemon disappeared unexpectedly", "Daemon .* disappeared",
                "GC overhead limit exceeded", "Metaspace"),
            listOf(Action.Shell("mkdir -p /root/.gradle && touch /root/.gradle/gradle.properties\n" +
                "sed -i '/^org.gradle.jvmargs/d;/^org.gradle.daemon/d' /root/.gradle/gradle.properties\n" +
                "printf 'org.gradle.jvmargs=-Xmx1024m -XX:+UseSerialGC\\norg.gradle.daemon=false\\n' >> /root/.gradle/gradle.properties\n"))),
        Rule("android-licenses", "Android SDK licenses were not accepted: accepting the ones you agreed to",
            r("licences have not been accepted", "licenses have not been accepted", "License for package .* not accepted"),
            listOf(Action.Shell(". /etc/profile.d/android.sh 2>/dev/null; yes | sdkmanager --licenses >/dev/null || true\n"))),
        Rule("aapt2", "aapt2 does not run on this phone: reinstall the Android SDK add-on (it installs the ARM build)",
            r("aapt2.*(Exec format error|cannot execute binary)", "AAPT2 aapt2-.* Daemon #\\d+: Daemon startup failed", "Failed to start AAPT2"),
            listOf(Action.Advice("Open Add-ons → Linux & AI CLIs → Android SDK and tap Reinstall."))),
        Rule("sdk-location", "Gradle can't find the Android SDK: use android-build (it writes local.properties)",
            r("SDK location not found", "ANDROID_HOME.*not set", "ANDROID_SDK_ROOT.*invalid"),
            listOf(Action.Advice("Run android-build in the project folder, or add sdk.dir=/opt/android-sdk to local.properties."))),
        Rule("memory", "The phone ran out of memory: retrying with less",
            r("^Killed$", "JavaScript heap out of memory", "Cannot allocate memory"),
            listOf(Action.Shell("rm -rf /tmp/npm-* 2>/dev/null || true\n"),
                Action.Advice("Close other apps to give Codriod more memory.")))
    )

    /** Fixes for an output, most specific first. [exitCode] 137 means the process was killed. */
    fun diagnose(output: String, exitCode: Int? = null): List<Fix> {
        val tail = output.takeLast(20_000)
        val lines = tail.lines()
        val found = rules.filter { rule -> rule.patterns.any { p -> lines.any { line -> p.containsMatchIn(line) } } }
            .toMutableList()
        if (exitCode == 137 && found.none { it.id == "memory" }) found += rules.first { it.id == "memory" }
        return found.flatMap { rule -> rule.actions.mapIndexed { i, action -> Fix(if (i == 0) rule.id else "${rule.id}-$i", rule.title, action) } }
    }

    /** A short human summary of the last error lines, for when nothing matched. */
    fun summary(output: String): String {
        val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("::") }
        val error = lines.lastOrNull { Regex("^(E: |npm ERR! (code|errno|syscall|network)|npm error (code|errno)|curl: \\(\\d+\\)|error:|Error:|fatal:|proot error|dpkg: error)").containsMatchIn(it) }
            ?: lines.lastOrNull { Regex("(?i)(error|failed|denied|not found|cannot|unable|fatal)").containsMatchIn(it) }
        return (error ?: lines.lastOrNull() ?: "The step failed without output").take(300)
    }
}
