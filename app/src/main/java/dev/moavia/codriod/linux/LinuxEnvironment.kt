package dev.moavia.codriod.linux

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import androidx.compose.runtime.*
import dev.moavia.codriod.workspace.CodriodCommands
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

enum class LinuxPhase { Unsupported, NotInstalled, Installing, Ready, Broken }
enum class StepStatus { Waiting, Running, Fixing, Done, Failed, Skipped }

class LinuxStep(val id: String, val title: String) {
    var status by mutableStateOf(StepStatus.Waiting)
    var detail by mutableStateOf("")
    /** 0..1 while running. */
    var progress by mutableFloatStateOf(0f)
}

data class ExecResult(val code: Int, val output: String, val timedOut: Boolean = false)

/** Android implementation of the calls tar extraction needs. */
object AndroidFileOps : FileOps {
    override fun symlink(target: String, path: File) { Os.symlink(target, path.absolutePath) }
    override fun chmod(path: File, mode: Int) { runCatching { Os.chmod(path.absolutePath, mode) } }
    override fun isSymlink(path: File): Boolean =
        runCatching { OsConstants.S_ISLNK(Os.lstat(path.absolutePath).st_mode) }.getOrDefault(false)
}

/**
 * Ubuntu inside Codriod: proot (bundled in the APK's native library folder) runs an Ubuntu base system unpacked
 * in the app's private storage. Installs Node.js and the official AI CLIs with one tap; Codriod Doctor repairs
 * known errors automatically. Everything stays on the phone; CLIs sign in with the user's own accounts.
 */
class LinuxEnvironment(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("linux", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    // ---- paths -------------------------------------------------------------------------------------------
    private val nativeDir = File(app.applicationInfo.nativeLibraryDir)
    val prootBinary = File(nativeDir, "libproot.so")
    private val loader = File(nativeDir, "libproot-loader.so")
    private val loader32 = File(nativeDir, "libproot-loader32.so")
    val base = File(app.filesDir, "linux")
    val rootfs = File(base, "ubuntu")
    private val tmp = File(base, "tmp")
    private val fakeProc = File(base, "proc")
    private val downloads = File(base, "downloads")
    private val logFile = File(base, "install.log")

    val ubuntuArch: String? = Build.SUPPORTED_ABIS.firstNotNullOfOrNull {
        when (it) { "arm64-v8a" -> "arm64"; "x86_64" -> "amd64"; else -> null }
    }

    // ---- state ---------------------------------------------------------------------------------------------
    var phase by mutableStateOf(initialPhase())
        private set
    val steps = mutableStateListOf<LinuxStep>()
    val log = mutableStateListOf<String>()
    var progress by mutableStateOf<Float?>(null)
        private set
    var progressLabel by mutableStateOf("")
        private set
    /** Whole install, 0..1, with the time left (e.g. "about 6 min left") and the usual total ("8–15 min"). */
    var overall by mutableFloatStateOf(0f)
        private set
    var timeLeft by mutableStateOf("")
        private set
    var usualTime by mutableStateOf("")
        private set
    var elapsed by mutableStateOf("")
        private set
    private var estimator = InstallEstimator()
    private var ticker: Job? = null
    /** Problem that Codriod Doctor could not fix, with the log tail, for the user or the AI agent. */
    var problem by mutableStateOf<String?>(null)
        private set
    /** cli id → installed version ("" = installed, version unknown). Missing = not installed. */
    val installed = mutableStateMapOf<String, String>()
    var busyCli by mutableStateOf<String?>(null)
        private set
    var nodeVersion by mutableStateOf(prefs.getString("node_version", null))
        private set
    /** CLIs shown as tabs on the editor's edge. */
    val pinned = mutableStateListOf<String>().apply { addAll(prefs.getString("pinned", "")!!.split(',').filter { it.isNotBlank() }) }

    private var job: Job? = null
    val busy get() = job?.isActive == true

    init {
        runCatching {
            val json = JSONObject(prefs.getString("installed", "{}")!!)
            json.keys().forEach { installed[it] = json.optString(it) }
        }
    }

    private fun initialPhase(): LinuxPhase = when {
        ubuntuArch == null || !prootBinary.isFile -> LinuxPhase.Unsupported
        prefs.getBoolean("ready", false) && File(rootfs, "usr/bin/env").exists() -> LinuxPhase.Ready
        prefs.getBoolean("ready", false) -> LinuxPhase.Broken
        else -> LinuxPhase.NotInstalled
    }

    val unsupportedReason: String get() = when {
        ubuntuArch == null -> "Ubuntu and the AI CLIs need a 64-bit phone (arm64). This phone is ${Build.SUPPORTED_ABIS.firstOrNull()}."
        !prootBinary.isFile -> "This build of Codriod does not include the Linux runtime (proot). Install the APK built by GitHub Actions."
        else -> ""
    }

    val ready get() = phase == LinuxPhase.Ready

    private fun saveInstalled() {
        val json = JSONObject(); installed.forEach { (k, v) -> json.put(k, v) }
        prefs.edit().putString("installed", json.toString()).apply()
    }

    fun togglePinned(id: String) {
        if (id in pinned) pinned.remove(id) else pinned.add(id)
        prefs.edit().putString("pinned", pinned.joinToString(",")).apply()
    }

    private fun ui(block: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block) }

    private fun logLine(line: String) {
        runCatching { logFile.parentFile?.mkdirs(); logFile.appendText(line + "\n") }
        ui {
            log += line
            if (log.size > 600) log.removeRange(0, log.size - 500)
        }
    }

    fun logText(): String = runCatching { logFile.readText().takeLast(60_000) }.getOrDefault(log.joinToString("\n"))

    // ---- proot ---------------------------------------------------------------------------------------------------

    private val noSeccomp get() = prefs.getBoolean("no_seccomp", false)

    private fun fakeProcBinds(): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val cpus = Runtime.getRuntime().availableProcessors()
        ProotBinds.fakeProcFiles(cpus).forEach { (guest, content) ->
            val readable = runCatching { File(guest).readText().isNotEmpty() }.getOrDefault(false)
            if (!readable) {
                val file = File(fakeProc, guest.removePrefix("/").replace('/', '_'))
                if (!file.exists()) runCatching { file.parentFile?.mkdirs(); file.writeText(content) }
                result[guest] = file.absolutePath
            }
        }
        return result
    }

    /** A proot spec; [project] is bound at /root/projects/<name>. */
    fun spec(project: Pair<File, String>? = null, cwd: String? = null, extraEnv: Map<String, String> = emptyMap()): ProotSpec {
        tmp.mkdirs()
        val ipc = CodriodCommands.ipcDir(app).apply { mkdirs() }
        val binds = ProotBinds.standard(rootfs.absolutePath, fakeProcBinds()).toMutableList()
        binds += ipc.absolutePath to ipc.absolutePath
        val env = LinkedHashMap<String, String>()
        env["CODRIOD_IPC"] = ipc.absolutePath
        var dir = cwd ?: "/root"
        if (project != null) {
            val guest = guestProjectPath(project.second)
            File(rootfs, guest.removePrefix("/")).mkdirs()
            binds += project.first.absolutePath to guest
            env["CODRIOD_MAP"] = "$guest=${project.first.absolutePath}"
            if (cwd == null) dir = guest
        }
        File(rootfs, ipc.absolutePath.removePrefix("/")).mkdirs()
        env.putAll(extraEnv)
        return ProotSpec(prootBinary.absolutePath, loader.absolutePath, loader32.takeIf { it.isFile }?.absolutePath,
            nativeDir.absolutePath, tmp.absolutePath, rootfs.absolutePath, binds, dir, env, noSeccomp)
    }

    fun guestProjectPath(name: String) = "/root/projects/" + name.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifBlank { "project" }

    /** Runs [script] with bash inside Ubuntu; every output line goes to [onLine]. */
    suspend fun runScript(script: String, project: Pair<File, String>? = null, cwd: String? = null, timeoutSeconds: Long = 3600,
        onLine: (String) -> Unit = {}): ExecResult = withContext(Dispatchers.IO) {
        val name = "codriod-run-${System.nanoTime()}.sh"
        val file = File(rootfs, "tmp/$name").apply { parentFile?.mkdirs(); writeText(script) }
        try { exec(listOf("/bin/bash", "/tmp/$name"), project, cwd, timeoutSeconds, onLine) } finally { file.delete() }
    }

    /** Starts a long-running program inside Ubuntu with separate stdin/stdout/stderr (for CLI protocols). */
    fun spawn(command: List<String>, project: Pair<File, String>? = null, cwd: String? = null): Process {
        val spec = spec(project, cwd)
        val builder = ProcessBuilder(spec.argv(command)).directory(base)
        builder.environment().putAll(spec.hostEnv())
        return builder.start()
    }

    /** Copies a phone file into Ubuntu's /tmp so CLIs can read it; returns the path inside Ubuntu. */
    fun shareIntoUbuntu(hostPath: String): String {
        val source = File(hostPath)
        val dir = File(rootfs, "tmp/codriod-attach").apply { mkdirs() }
        val target = File(dir, "${System.currentTimeMillis()}-${source.name}")
        source.copyTo(target, overwrite = true)
        return "/tmp/codriod-attach/${target.name}"
    }

    /** Runs a command inside Ubuntu and collects its output (stdout and stderr together). */
    suspend fun exec(command: List<String>, project: Pair<File, String>? = null, cwd: String? = null, timeoutSeconds: Long = 600,
        onLine: (String) -> Unit = {}): ExecResult = withContext(Dispatchers.IO) {
        val spec = spec(project, cwd)
        val builder = ProcessBuilder(spec.argv(command)).redirectErrorStream(true).directory(base)
        builder.environment().putAll(spec.hostEnv())
        val process = try { builder.start() } catch (error: IOException) {
            return@withContext ExecResult(126, "proot error: could not start (${error.message})")
        }
        val output = StringBuilder()
        val reader = launch {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val clean = line.replace(Regex("\u001B\\[[0-9;?]*[A-Za-z]"), "").replace("\r", "")
                    if (output.length < 400_000) output.append(clean).append('\n')
                    onLine(clean)
                }
            }
        }
        var timedOut = false
        try {
            val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
            while (true) {
                val code = runCatching { process.exitValue() }.getOrNull()
                if (code != null) break
                if (System.currentTimeMillis() > deadline) { timedOut = true; process.destroy(); break }
                delay(100)
            }
            withTimeoutOrNull(3000) { reader.join() }
        } catch (error: CancellationException) {
            process.destroy(); throw error
        }
        val code = if (timedOut) 124 else runCatching { process.exitValue() }.getOrDefault(1)
        ExecResult(code, output.toString(), timedOut)
    }

    // ---- install ---------------------------------------------------------------------------------------------------

    private fun step(id: String) = steps.firstOrNull { it.id == id }

    private fun setStep(id: String, status: StepStatus, detail: String? = null) {
        if (status == StepStatus.Skipped) estimator.skip(id)
        ui { step(id)?.let { it.status = status; if (detail != null) it.detail = detail } }
    }

    /** Starts time-left estimates and the progress notification for the steps now in [steps]. */
    private fun beginEstimate(title: String) {
        estimator = InstallEstimator().apply { plan(steps.map { it.id }) }
        usualTime = estimator.usualRange()
        overall = 0f; timeLeft = "estimating time…"; elapsed = "0:00"
        InstallNotification.start(app, title)
        ticker?.cancel()
        ticker = scope.launch(Dispatchers.Main) {
            while (isActive) {
                val left = estimator.secondsLeft()
                overall = estimator.overall()
                timeLeft = InstallEstimator.left(left)
                elapsed = InstallEstimator.clock(estimator.elapsedTotalSeconds())
                steps.firstOrNull { it.status == StepStatus.Running || it.status == StepStatus.Fixing }?.let { it.progress = estimator.stepFraction() }
                val running = steps.firstOrNull { it.status == StepStatus.Running || it.status == StepStatus.Fixing }
                InstallNotification.update(app, title, (running?.title ?: "Working") + " · " + timeLeft, (overall * 100).toInt())
                delay(1000)
            }
        }
    }

    private fun endEstimate(success: Boolean, message: String) {
        ticker?.cancel(); ticker = null
        ui { if (success) { overall = 1f; timeLeft = "done" } }
        InstallNotification.stop(app, if (success) "Done" else "Stopped", message)
    }

    /**
     * One tap: Ubuntu, basic tools, Node.js and the chosen CLIs. Steps that are already done are skipped, so a failed
     * install can simply be run again.
     */
    fun install(clis: List<String> = emptyList(), withNode: Boolean = false) {
        if (busy || phase == LinuxPhase.Unsupported) return
        val wanted = clis.mapNotNull(CliCatalog::byId)
        steps.clear()
        steps += LinuxStep("check", "Checking the phone")
        steps += LinuxStep("download", "Downloading Ubuntu 24.04")
        steps += LinuxStep("extract", "Unpacking Ubuntu")
        steps += LinuxStep("configure", "Setting up Ubuntu for the phone")
        steps += LinuxStep("test", "Starting Ubuntu")
        steps += LinuxStep("base", "Installing git, curl and python")
        if (withNode || wanted.any { it.needsNode }) steps += LinuxStep("node", "Installing Node.js 22")
        wanted.forEach { steps += LinuxStep("cli:${it.id}", "Installing ${it.name}") }
        problem = null
        phase = LinuxPhase.Installing
        log.clear()
        runCatching { logFile.delete() }
        beginEstimate("Installing Ubuntu" + if (wanted.isEmpty()) "" else " and ${wanted.size} CLI" + if (wanted.size > 1) "s" else "")
        var success = false
        job = scope.launch(Dispatchers.IO) {
            try {
                runStep("check") { checkPhone() }
                if (!File(rootfs, "usr/bin/env").exists() || !prefs.getBoolean("extracted", false)) {
                    val archive = runStep("download") { downloadUbuntu() }
                    runStep("extract") { extractUbuntu(archive) }
                } else { setStep("download", StepStatus.Skipped, "already downloaded"); setStep("extract", StepStatus.Skipped, "already unpacked") }
                runStep("configure") { configure() }
                runStep("test") {
                    val result = exec(listOf("/bin/sh", "-c", "echo codriod-ok; uname -m"), timeoutSeconds = 60) { logLine(it) }
                    if (!result.output.contains("codriod-ok")) throw StepFailure(result.output.ifBlank { "proot exited with ${result.code}" }, result.code)
                    "Ubuntu runs (${result.output.lines().getOrNull(1)?.trim().orEmpty()})"
                }
                if (prefs.getBoolean("base_done", false)) setStep("base", StepStatus.Skipped, "already installed")
                else runStep("base") { scriptStep("base", LinuxScripts.base); prefs.edit().putBoolean("base_done", true).apply(); "ready" }
                prefs.edit().putBoolean("ready", true).apply()
                ui { phase = LinuxPhase.Ready }
                if (step("node") != null) {
                    if (nodeOk()) setStep("node", StepStatus.Skipped, "Node.js ${nodeVersion.orEmpty()} already installed")
                    else runStep("node") { scriptStep("node", LinuxScripts.node()); refreshNode(); "Node.js ${nodeVersion.orEmpty()}" }
                }
                val failed = wanted.filter { cli ->
                    try { runStep("cli:${cli.id}") { installCliNow(cli) }; false } catch (_: StepFailure) { true }
                }
                success = failed.isEmpty()
                if (failed.isEmpty()) { logLine("✓ Everything is installed."); ui { problem = null } }
                else logLine("✗ Not installed: ${failed.joinToString { it.name }}. Tap Retry, or ask Codriod Agent to fix it.")
            } catch (error: CancellationException) {
                logLine("Stopped.")
                ui { if (phase == LinuxPhase.Installing) phase = if (prefs.getBoolean("ready", false)) LinuxPhase.Ready else LinuxPhase.NotInstalled }
            } catch (error: StepFailure) {
                ui { phase = if (prefs.getBoolean("ready", false)) LinuxPhase.Ready else LinuxPhase.Broken }
            } finally {
                ui { progress = null; progressLabel = "" }
                endEstimate(success, if (success) "Ubuntu and your CLIs are ready" else problem ?: "Open Codriod to see what happened")
            }
        }
    }

    fun cancel() { job?.cancel() }

    private class StepFailure(message: String, val code: Int = 1) : Exception(message)

    private suspend fun <T> runStep(id: String, block: suspend () -> T): T {
        estimator.start(id)
        setStep(id, StepStatus.Running)
        logLine("▶ ${step(id)?.title ?: id}")
        return try {
            val result = block()
            estimator.finish(id)
            setStep(id, StepStatus.Done, (result as? String) ?: "")
            result
        } catch (error: CancellationException) { setStep(id, StepStatus.Failed, "stopped"); throw error }
        catch (error: StepFailure) {
            setStep(id, StepStatus.Failed, error.message.orEmpty().take(200))
            ui { problem = "${step(id)?.title}: ${error.message}" }
            throw error
        } catch (error: Exception) {
            val message = error.message ?: error.javaClass.simpleName
            logLine("✗ $message")
            setStep(id, StepStatus.Failed, message.take(200))
            ui { problem = "${step(id)?.title}: $message" }
            throw StepFailure(message)
        }
    }

    /**
     * Runs an install script; on failure Codriod Doctor reads the output, applies the matching fixes and tries
     * again (up to four attempts). Throws when the error is unknown or keeps coming back.
     */
    private suspend fun scriptStep(id: String, script: String, project: Pair<File, String>? = null): String {
        val tried = HashSet<String>()
        var attempt = 0
        while (true) {
            attempt++
            val result = runScript(script) { line ->
                if (line.startsWith("::step ")) setStep(id, if (attempt > 1) StepStatus.Fixing else StepStatus.Running, line.removePrefix("::step "))
                val parsed = InstallEstimator.parseLine(line)
                if (parsed != null) {
                    estimator.report(id, parsed.first)
                    parsed.second?.let { msg -> setStep(id, if (attempt > 1) StepStatus.Fixing else StepStatus.Running, msg) }
                }
                if (!InstallEstimator.isProgressLine(line)) logLine(line)
            }
            if (result.code == 0) return result.output.lines().lastOrNull { it.startsWith("::step ") }?.removePrefix("::step ").orEmpty()
            val failLine = result.output.lines().lastOrNull { it.startsWith("::fail ") }?.removePrefix("::fail ")
            if (failLine != null) throw StepFailure(failLine, result.code)
            val fixes = LinuxDoctor.diagnose(result.output, result.code).filter { it.id !in tried }
            if (fixes.isEmpty() || attempt >= 4) {
                val summary = LinuxDoctor.summary(result.output)
                logLine("✗ Codriod Doctor could not fix this automatically: $summary")
                throw StepFailure(summary, result.code)
            }
            for (fix in fixes) {
                tried += fix.id
                setStep(id, StepStatus.Fixing, fix.title)
                logLine("🩺 Doctor: ${fix.title}")
                applyFix(fix)
            }
        }
    }

    private suspend fun applyFix(fix: LinuxDoctor.Fix) {
        when (val action = fix.action) {
            is LinuxDoctor.Action.Shell -> runScript(action.script) { logLine("   $it") }
            is LinuxDoctor.Action.Wait -> delay(action.seconds * 1000L)
            is LinuxDoctor.Action.Advice -> logLine("   ℹ ${action.message}")
            is LinuxDoctor.Action.Host -> when (action.kind) {
                LinuxDoctor.HostFix.RotateDns -> {
                    val next = prefs.getInt("dns", 0) + 1
                    prefs.edit().putInt("dns", next).apply()
                    writeConfig()
                }
                LinuxDoctor.HostFix.DisableSeccomp -> prefs.edit().putBoolean("no_seccomp", true).apply()
                LinuxDoctor.HostFix.Reextract -> {
                    prefs.edit().putBoolean("extracted", false).apply()
                    val archive = downloadUbuntu()
                    extractUbuntu(archive); configure()
                }
                LinuxDoctor.HostFix.DeletePartialDownload -> downloads.listFiles()?.forEach { it.delete() }
                LinuxDoctor.HostFix.CleanTemp -> { tmp.deleteRecursively(); tmp.mkdirs(); app.cacheDir.listFiles()?.forEach { it.deleteRecursively() } }
                LinuxDoctor.HostFix.RewriteConfig -> writeConfig()
            }
        }
    }

    private fun checkPhone(): String {
        val free = StatFs(app.filesDir.absolutePath).availableBytes
        if (free < 700L * 1024 * 1024) throw StepFailure("Only ${free / 1_048_576} MB free. Ubuntu with Node.js needs about 1.5 GB; free some space first.")
        if (!loader.isFile) throw StepFailure("The proot loader is missing from this build.")
        tmp.mkdirs(); downloads.mkdirs()
        return "64-bit (${ubuntuArch}) · ${"%.1f".format(free / 1_073_741_824.0)} GB free"
    }

    // ---- download & extract --------------------------------------------------------------------------------------

    private suspend fun downloadUbuntu(): File {
        val arch = ubuntuArch ?: throw StepFailure("64-bit phone needed")
        val sums = httpText(UbuntuBase.URL + "SHA256SUMS")
        val (sha, name) = UbuntuBase.pick(sums, arch) ?: throw StepFailure("Could not find Ubuntu base for $arch")
        val file = File(downloads, name)
        if (file.isFile && sha256(file) == sha) { logLine("Using the earlier download of $name"); return file }
        downloads.listFiles()?.filter { it.name.startsWith("ubuntu-base") && it.name != name && !it.name.startsWith(name) }?.forEach { it.delete() }
        var attempt = 0
        while (true) {
            attempt++
            try {
                download(UbuntuBase.URL + name, file, "Ubuntu")
                ui { progressLabel = "Checking the download" }
                val got = sha256(file)
                if (got != sha) { file.delete(); throw IOException("The download is damaged (checksum does not match)") }
                return file
            } catch (error: CancellationException) { throw error }
            catch (error: IOException) {
                logLine("Download problem: ${error.message}")
                if (attempt >= 4) throw StepFailure("Download failed: ${error.message}. Check the internet connection and try again.")
                logLine("🩺 Doctor: retrying the download (it resumes where it stopped)")
                delay(3000L * attempt)
            }
        }
    }

    private suspend fun download(url: String, file: File, label: String) = withContext(Dispatchers.IO) {
        val part = File(file.path + ".part")
        val start = if (part.isFile) part.length() else 0L
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000; connection.readTimeout = 60_000
        if (start > 0) connection.setRequestProperty("Range", "bytes=$start-")
        val code = connection.responseCode
        if (code !in 200..299) throw IOException("HTTP $code for $url")
        val resumed = code == 206
        val length = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: 0L
        val total = (if (resumed) start else 0L) + length.coerceAtLeast(0)
        var done = if (resumed) start else 0L
        val began = System.currentTimeMillis()
        val beganBytes = done
        java.io.FileOutputStream(part, resumed).use { out ->
            connection.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                var lastUi = 0L
                while (true) {
                    ensureActive()
                    val n = input.read(buffer); if (n < 0) break
                    out.write(buffer, 0, n); done += n
                    val now = System.currentTimeMillis()
                    if (now - lastUi > 200) {
                        lastUi = now
                        val fraction = if (total > 0) done.toFloat() / total else null
                        val seconds = (now - began) / 1000.0
                        if (seconds > 1.5) estimator.measuredSpeed((done - beganBytes) / seconds)
                        fraction?.let { estimator.report("download", it) }
                        ui { progress = fraction; progressLabel = "$label · ${done / 1_048_576} / ${total / 1_048_576} MB" }
                    }
                }
            }
        }
        connection.disconnect()
        if (total > 0 && part.length() < total) throw IOException("The download stopped early")
        file.delete(); part.renameTo(file)
        ui { progress = null }
    }

    private suspend fun extractUbuntu(archive: File): String = withContext(Dispatchers.IO) {
        prefs.edit().putBoolean("extracted", false).putBoolean("base_done", false).apply()
        val fresh = File(base, "ubuntu.new")
        fresh.deleteRecursively()
        val size = archive.length().coerceAtLeast(1)
        val entries = FileInputStream(archive).use { raw ->
            val counting = CountingInputStream(raw.buffered(256 * 1024)) { n ->
                if (n % (1 shl 20) < 65536) {
                    estimator.report("extract", n.toFloat() / size)
                    ui { progress = n.toFloat() / size; progressLabel = "Unpacking · ${100 * n / size}%" }
                }
            }
            TarExtractor.extract(GZIPInputStream(counting, 65536), fresh, AndroidFileOps)
        }
        if (!File(fresh, "usr/bin/env").exists() && !AndroidFileOps.isSymlink(File(fresh, "usr/bin/env")))
            throw StepFailure("The Ubuntu archive is incomplete")
        // Keep /root (the user's home: logins, settings) when Ubuntu is unpacked again.
        val oldHome = File(rootfs, "root")
        if (oldHome.isDirectory) { File(fresh, "root").deleteRecursively(); oldHome.renameTo(File(fresh, "root")) }
        rootfs.deleteRecursively()
        if (!fresh.renameTo(rootfs)) throw StepFailure("Could not move Ubuntu into place")
        prefs.edit().putBoolean("extracted", true).apply()
        ui { progress = null; progressLabel = "" }
        "$entries files"
    }

    private fun configure(): String {
        writeConfig()
        listOf("tmp", "root/projects", "var/tmp").forEach { File(rootfs, it).mkdirs() }
        AndroidFileOps.chmod(File(rootfs, "tmp"), 0x3ff) // 1777
        return "DNS, apt, codriod commands"
    }

    private fun writeConfig() {
        val ipc = CodriodCommands.ipcDir(app).apply { mkdirs() }
        RootfsSetup.files(prefs.getInt("dns", 0), ipc.absolutePath, rootfs.absolutePath).forEach { (guest, content) ->
            val file = File(rootfs, guest.removePrefix("/"))
            file.parentFile?.mkdirs()
            if (AndroidFileOps.isSymlink(file)) file.delete() // Ubuntu links resolv.conf to systemd
            file.writeText(content)
            AndroidFileOps.chmod(file, if (guest in RootfsSetup.executables) 0x1ed else 0x1a4)
        }
        writeAgentInstructions()
    }

    // ---- Android development: SDK, wireless debugging, installs ----------------------------------------------

    var androidSdk by mutableStateOf(prefs.getString("android_sdk", null))
        private set
    var adbConnected by mutableStateOf(false)
        private set
    var adbStatus by mutableStateOf("Not connected")
        private set
    var adbBusy by mutableStateOf(false)
        private set

    /**
     * Tells every coding agent (Codex, Copilot, Gemini, Claude Code, Qwen, opencode) about this phone: `codriod install`,
     * `android-build`, `android-logs`, adb. Kept between markers so the user's own text in those files stays.
     */
    fun writeAgentInstructions() {
        if (!File(rootfs, "usr").isDirectory) return
        val block = AndroidDev.agentInstructions(androidSdk != null, adbConnected)
        AndroidDev.instructionFiles.forEach { guest ->
            runCatching {
                val file = File(rootfs, guest.removePrefix("/"))
                file.parentFile?.mkdirs()
                file.writeText(AndroidDev.mergeInstructions(file.takeIf { it.isFile }?.readText(), block))
            }
        }
    }

    /** One tap: JDK 17, Android SDK (platform, build-tools, platform-tools), ARM aapt2 on phones, Gradle settings. */
    fun installAndroidSdk() {
        if (busy || !ready) return
        steps.clear()
        steps += LinuxStep("android-sdk", "Installing the Android SDK")
        problem = null
        beginEstimate("Installing the Android SDK")
        job = scope.launch(Dispatchers.IO) {
            var ok = false
            try {
                runStep("android-sdk") {
                    scriptStep("android-sdk", AndroidDev.sdkScript())
                    prefs.edit().putString("android_sdk", AndroidDev.PLATFORM).apply()
                    ui { androidSdk = AndroidDev.PLATFORM }
                    writeAgentInstructions()
                    "${AndroidDev.PLATFORM} · build-tools ${AndroidDev.BUILD_TOOLS} · JDK 17"
                }
                ok = true
            } catch (_: StepFailure) {} catch (_: CancellationException) {}
            finally { endEstimate(ok, if (ok) "Android SDK is ready: android-build --install" else "The Android SDK was not installed") }
        }
    }

    private suspend fun adb(vararg args: String, timeout: Long = 30): ExecResult =
        exec(listOf("/bin/sh", "-c", "command -v adb >/dev/null || { echo 'adb is not installed: install the Android SDK first'; exit 127; }; adb " +
            args.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }), timeoutSeconds = timeout)

    /** Wireless debugging step 2: pair with the code Android shows ("Pair device with pairing code"). */
    fun adbPair(host: String, port: String, code: String) = adbAction("Pairing…") {
        val r = adb("pair", "$host:$port", code)
        if (r.output.contains("Successfully paired", true)) "Paired. Now connect." else throw IOException(r.output.trim().ifBlank { "Pairing failed" })
    }

    /** Wireless debugging step 3: connect (finds the port by itself when [port] is blank). */
    fun adbConnect(host: String, port: String) = adbAction("Connecting…") {
        var target = if (port.isNotBlank()) "$host:$port" else null
        if (target == null) {
            val services = adb("mdns", "services").output
            target = Regex("_adb-tls-connect\\._tcp\\.?\\s+([0-9.]+:\\d+)").find(services)?.groupValues?.get(1)
                ?: throw IOException("Could not find the port automatically. Type the port shown under “IP address & Port”.")
        }
        val r = adb("connect", target)
        if (!r.output.contains("connected to", true) || r.output.contains("failed", true)) throw IOException(r.output.trim().ifBlank { "Connect failed" })
        prefs.edit().putString("adb_target", target).apply()
        "Connected ($target)"
    }

    /** Checks whether adb still has the phone (it drops when Wi-Fi changes or the phone restarts). */
    fun adbRefresh() = adbAction("Checking…") {
        val target = prefs.getString("adb_target", null)
        if (target != null) adb("connect", target, timeout = 15)
        val devices = adb("devices").output
        if (devices.lines().none { it.trim().endsWith("device") && !it.startsWith("List") }) throw IOException("Not connected")
        "Connected" + (target?.let { " ($it)" } ?: "")
    }

    private fun adbAction(working: String, block: suspend () -> String) {
        if (adbBusy) return
        adbBusy = true; adbStatus = working
        scope.launch(Dispatchers.IO) {
            try {
                val message = block()
                ui { adbStatus = message; adbConnected = message.startsWith("Connected") || adbConnected && !message.startsWith("Paired") }
            } catch (error: Exception) {
                ui { adbStatus = error.message ?: "Failed"; if (working != "Pairing…") adbConnected = false }
            } finally {
                ui { adbBusy = false }
                writeAgentInstructions()
            }
        }
    }

    /** For `codriod install`: silent install over adb when wireless debugging is connected, else null (use Android's dialog). */
    fun installWithAdb(apk: File): Pair<Int, String>? {
        if (!adbConnected || !ready) return null
        return runBlocking(Dispatchers.IO) {
            val guest = shareIntoUbuntu(apk.absolutePath)
            val r = adb("install", "-r", "-t", guest, timeout = 600)
            File(rootfs, guest.removePrefix("/")).delete()
            when {
                r.output.contains("Success") -> 0 to "✓ installed ${apk.name} (wireless debugging)"
                r.output.contains("no devices") || r.output.contains("device offline") || r.code == 127 -> { ui { adbConnected = false; adbStatus = "Disconnected" }; null }
                else -> 1 to "codriod: adb install failed: ${r.output.trim().lines().lastOrNull { it.isNotBlank() } ?: r.output.take(300)}"
            }
        }
    }

    private suspend fun httpText(url: String): String = withContext(Dispatchers.IO) {
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 20_000; c.readTimeout = 30_000
                if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
                return@withContext c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (error: IOException) { last = error; delay(2000L * (attempt + 1)) }
        }
        throw StepFailure("No internet connection (${last?.message})")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input -> val b = ByteArray(1 shl 16); while (true) { val n = input.read(b); if (n < 0) break; digest.update(b, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ---- node & CLIs ----------------------------------------------------------------------------------------------

    private suspend fun refreshNode() {
        val r = exec(listOf("/bin/sh", "-c", "PATH=/opt/node/bin:\$PATH node --version 2>/dev/null"), timeoutSeconds = 60)
        val v = r.output.trim().lines().lastOrNull()?.takeIf { it.startsWith("v") }
        ui { nodeVersion = v }
        prefs.edit().putString("node_version", v).apply()
    }

    private suspend fun nodeOk(): Boolean {
        refreshNode()
        return nodeVersion?.removePrefix("v")?.substringBefore('.')?.toIntOrNull()?.let { it >= 22 } == true
    }

    private suspend fun installCliNow(cli: CliSpec): String {
        if (cli.needsNode && !nodeOk()) {
            logLine("Node.js 22 is needed first")
            scriptStep("cli:${cli.id}", LinuxScripts.node()); refreshNode()
        }
        scriptStep("cli:${cli.id}", CliCatalog.installScript(cli))
        val version = versionOf(cli) ?: throw StepFailure("${cli.name} installed, but `${cli.command} --version` does not run")
        ui {
            val first = cli.id !in installed
            installed[cli.id] = version; saveInstalled()
            // A newly installed CLI gets its tab on the editor edge (can be hidden in Add-ons).
            if (first && cli.id !in pinned) togglePinned(cli.id)
        }
        return "${cli.name} $version"
    }

    private suspend fun versionOf(cli: CliSpec): String? {
        val r = exec(listOf("/bin/bash", "-c", "export PATH=${ProotSpec.PATH}; command -v ${cli.command} >/dev/null && ${cli.versionCommand} 2>&1 | head -n 3"),
            timeoutSeconds = 90)
        if (r.code != 0 && r.output.isBlank()) return null
        if (r.output.contains("not found") || r.output.isBlank()) return null
        return CliCatalog.parseVersion(r.output) ?: ""
    }

    /** Install or update one CLI (with Doctor). */
    fun installCli(id: String) {
        val cli = CliCatalog.byId(id) ?: return
        if (!ready) { install(listOf(id)); return }
        if (busy) return
        steps.clear()
        steps += LinuxStep("cli:$id", "Installing ${cli.name}")
        problem = null
        busyCli = id
        beginEstimate("Installing ${cli.name}")
        job = scope.launch(Dispatchers.IO) {
            var ok = false
            try { runStep("cli:$id") { installCliNow(cli) }; ok = true }
            catch (_: StepFailure) {} catch (_: CancellationException) {}
            finally { ui { busyCli = null }; endEstimate(ok, if (ok) "${cli.name} is ready" else "${cli.name} was not installed") }
        }
    }

    /** Installs or updates Node.js 22 (with Doctor). */
    fun installNode() {
        if (!ready) { install(withNode = true); return }
        if (busy) return
        steps.clear()
        steps += LinuxStep("node", "Installing Node.js 22")
        problem = null
        beginEstimate("Installing Node.js 22")
        job = scope.launch(Dispatchers.IO) {
            var ok = false
            try { runStep("node") { scriptStep("node", LinuxScripts.node()); refreshNode(); "Node.js ${nodeVersion.orEmpty()}" }; ok = true }
            catch (_: StepFailure) {} catch (_: CancellationException) {}
            finally { endEstimate(ok, if (ok) "Node.js ${nodeVersion.orEmpty()} is ready" else "Node.js was not installed") }
        }
    }

    fun removeCli(id: String) {
        val cli = CliCatalog.byId(id) ?: return
        if (busy) return
        busyCli = id
        job = scope.launch(Dispatchers.IO) {
            try {
                runScript(CliCatalog.removeScript(cli)) { logLine(it) }
                ui { installed.remove(id); pinned.remove(id); saveInstalled() }
            } finally { ui { busyCli = null } }
        }
    }

    // ---- health check -------------------------------------------------------------------------------------------

    data class Check(val title: String, val ok: Boolean, val detail: String)
    val checks = mutableStateListOf<Check>()
    var checking by mutableStateOf(false)
        private set

    /** Codriod Doctor's health check: runtime, Ubuntu, network, apt, Node.js and every installed CLI. */
    fun runChecks(repair: Boolean = false) {
        if (busy) return
        checking = true
        checks.clear()
        job = scope.launch(Dispatchers.IO) {
            try {
                fun add(c: Check) = ui { checks += c }
                add(Check("Linux runtime (proot)", prootBinary.isFile && loader.isFile, if (prootBinary.isFile) "bundled" else unsupportedReason))
                val files = File(rootfs, "usr/bin/env").exists() || AndroidFileOps.isSymlink(File(rootfs, "usr/bin/env"))
                add(Check("Ubuntu files", files, if (files) rootfs.absolutePath else "missing — reinstall"))
                if (!files) { if (repair) install(); return@launch }
                if (repair) writeConfig()
                val start = exec(listOf("/bin/sh", "-c", "echo codriod-ok"), timeoutSeconds = 60)
                val starts = start.output.contains("codriod-ok")
                add(Check("Ubuntu starts", starts, if (starts) "ok" else LinuxDoctor.summary(start.output)))
                if (!starts) {
                    if (repair) LinuxDoctor.diagnose(start.output, start.code).forEach { applyFix(it) }
                    return@launch
                }
                val net = exec(listOf("/bin/sh", "-c", "getent hosts registry.npmjs.org >/dev/null && echo dns-ok"), timeoutSeconds = 30)
                add(Check("Internet & DNS", net.output.contains("dns-ok"), if (net.output.contains("dns-ok")) "ok" else "names don't resolve"))
                if (!net.output.contains("dns-ok") && repair) applyFix(LinuxDoctor.Fix("dns", "", LinuxDoctor.Action.Host(LinuxDoctor.HostFix.RotateDns)))
                val apt = exec(listOf("/bin/sh", "-c", "dpkg --audit 2>&1; ls /var/lib/dpkg/lock-frontend >/dev/null 2>&1; echo apt-done"), timeoutSeconds = 60)
                val aptOk = apt.output.lines().none { it.isNotBlank() && it != "apt-done" }
                add(Check("Package manager (apt)", aptOk, if (aptOk) "ok" else "needs repair"))
                if (!aptOk && repair) runScript("rm -f /var/lib/dpkg/lock* /var/lib/apt/lists/lock\ndpkg --configure -a\napt-get -f install -y\n") { logLine(it) }
                if (installed.keys.any { CliCatalog.byId(it)?.needsNode == true } || nodeVersion != null) {
                    val ok = nodeOk()
                    add(Check("Node.js", ok, nodeVersion ?: "not installed"))
                    if (!ok && repair) scriptStep("node", LinuxScripts.node())
                }
                for (id in installed.keys.toList()) {
                    val cli = CliCatalog.byId(id) ?: continue
                    val v = versionOf(cli)
                    add(Check(cli.name, v != null, v?.let { "v$it" } ?: "does not start"))
                    if (v == null && repair) runCatching { installCliNow(cli) }
                }
                val free = StatFs(app.filesDir.absolutePath).availableBytes
                add(Check("Free storage", free > 500L * 1024 * 1024, "${free / 1_048_576} MB"))
            } catch (_: Exception) {
            } finally { ui { checking = false } }
        }
    }

    /** Unpacks Ubuntu again and reinstalls the basics; /root (logins, settings) and installed CLIs' list are kept. */
    fun reinstall() {
        if (busy) return
        prefs.edit().putBoolean("extracted", false).putBoolean("base_done", false).apply()
        install(installed.keys.toList())
    }

    /** Deletes Ubuntu. [keepHome] keeps /root (CLI logins and settings). */
    fun uninstall(keepHome: Boolean) {
        cancel()
        scope.launch(Dispatchers.IO) {
            val home = File(rootfs, "root")
            val saved = File(base, "home.saved")
            if (keepHome && home.isDirectory) { saved.deleteRecursively(); home.renameTo(saved) }
            rootfs.deleteRecursively(); downloads.deleteRecursively(); tmp.deleteRecursively()
            prefs.edit().remove("ready").remove("extracted").remove("base_done").remove("installed").remove("node_version").apply()
            ui { installed.clear(); nodeVersion = null; steps.clear(); phase = initialPhase() }
        }
    }

    /** What the AI agent should know about this Linux, for its system prompt. */
    fun agentNotes(projectName: String?): String = if (!ready) "" else buildString {
        append(AndroidDev.agentInstructions(androidSdk != null, adbConnected).substringAfter("\n\n"))
        appendLine("Linux: an Ubuntu 24.04 (${ubuntuArch}) environment runs on this phone with proot (no real root, no systemd, no Docker).")
        appendLine("Use the linux_run tool for Linux commands (apt-get, node, npm, python3, git, the AI CLIs). You are root there; do not use sudo.")
        if (projectName != null) appendLine("The open project is mounted at ${guestProjectPath(projectName)} (the same files as the editor); linux_run starts there.")
        appendLine("Node.js: ${nodeVersion ?: "not installed (linux_install node)"}. Installed CLIs: " +
            (installed.keys.mapNotNull { CliCatalog.byId(it)?.let { c -> "${c.command} ${installed[it]}" } }.joinToString().ifBlank { "none" }) + ".")
        appendLine("Interactive CLIs (claude, codex, gemini, copilot …) need a terminal; to sign in the user taps the CLI's tab on the editor edge or Add-ons → Linux & AI CLIs. Non-interactive modes work in linux_run, e.g. `claude -p \"…\"`, `codex exec \"…\"`, `gemini -p \"…\"`.")
        appendLine("Known fixes: 'Temporary failure resolving' → DNS (run linux_doctor); 'Could not get lock' → rm -f /var/lib/dpkg/lock* && dpkg --configure -a; " +
            "npm EINTEGRITY → npm cache clean --force; ENOTEMPTY → remove /opt/node/lib/node_modules/.<pkg>-*; EBADENGINE → linux_install node; " +
            "'Function not implemented' → proot seccomp (run linux_doctor). Long servers: run them in the background with nohup and check logs.")
    }

}
