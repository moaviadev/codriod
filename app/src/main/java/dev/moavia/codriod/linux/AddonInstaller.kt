package dev.moavia.codriod.linux

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import java.io.File

/** Error thrown by an add-on step; [message] is shown to the user (and the AI agent). */
class AddonFailure(message: String, val code: Int = 1) : Exception(message)

/**
 * Install machinery shared by the newer add-ons (Java & Kotlin, More extensions): step list with progress,
 * time estimate, a notification and the Codriod Doctor retries. Runs scripts through the Ubuntu
 * [LinuxEnvironment], exactly like the AI CLI installs do.
 */
class AddonInstaller(context: Context, private val scope: CoroutineScope, val linux: LinuxEnvironment) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    val steps = mutableStateListOf<LinuxStep>()
    val log = mutableStateListOf<String>()
    var problem by mutableStateOf<String?>(null)
        private set
    var overall by mutableFloatStateOf(0f)
        private set
    var timeLeft by mutableStateOf("")
        private set
    var usualTime by mutableStateOf("")
        private set
    var elapsed by mutableStateOf("")
        private set

    private var logFile = File(linux.base, "addons.log")
    private var estimator = InstallEstimator()
    private var ticker: Job? = null
    private var job: Job? = null
    private var busyState by mutableStateOf(false)
    /** Observable so every card that shows it recomposes when an install finishes. */
    val busy get() = busyState || job?.isActive == true

    private fun ui(block: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block) }

    private fun logLine(line: String) {
        runCatching { logFile.appendText(line + "\n") }
        ui {
            log += line
            if (log.size > 600) log.removeRange(0, log.size - 500)
        }
    }

    fun logText(): String = runCatching { logFile.readText().takeLast(60_000) }.getOrDefault(log.joinToString("\n"))

    fun cancel() { job?.cancel() }

    private fun setStep(id: String, status: StepStatus, detail: String? = null) {
        ui {
            steps.firstOrNull { it.id == id }?.let {
                it.status = status
                if (detail != null) it.detail = detail
            }
        }
    }

    /**
     * Runs a plan of steps ([id to title] pairs). [block] does the work; failures land in [problem] with the
     * log kept for "Ask Codriod AI" and copying.
     */
    fun run(title: String, plan: List<Pair<String, String>>, block: suspend () -> Unit) {
        if (busy || !linux.ready) return
        steps.clear()
        plan.forEach { (id, stepTitle) -> steps += LinuxStep(id, stepTitle) }
        problem = null
        log.clear()
        runCatching { logFile.delete() }
        estimator = InstallEstimator().apply { plan(steps.map { it.id }) }
        usualTime = estimator.usualRange()
        overall = 0f; timeLeft = "estimating time…"; elapsed = "0:00"
        InstallNotification.start(app, title)
        busyState = true
        ticker?.cancel()
        ticker = scope.launch(Dispatchers.Main) {
            while (isActive) {
                overall = estimator.overall()
                timeLeft = InstallEstimator.left(estimator.secondsLeft())
                elapsed = InstallEstimator.clock(estimator.elapsedTotalSeconds())
                steps.firstOrNull { it.status == StepStatus.Running || it.status == StepStatus.Fixing }?.let {
                    it.progress = estimator.stepFraction()
                }
                val running = steps.firstOrNull { it.status == StepStatus.Running || it.status == StepStatus.Fixing }
                InstallNotification.update(app, title, (running?.title ?: "Working") + " · " + timeLeft, (overall * 100).toInt())
                delay(1000)
            }
        }
        job = scope.launch(Dispatchers.IO) {
            var ok = false
            try {
                block()
                ok = problem == null
                if (ok) logLine("✓ Done.")
            } catch (error: CancellationException) {
                logLine("Stopped.")
            } catch (error: Exception) {
                val message = error.message ?: error.javaClass.simpleName
                ui { problem = problem ?: message }
            } finally {
                ui {
                    if (ok) { overall = 1f; timeLeft = "done" }
                }
                ticker?.cancel()
                InstallNotification.stop(app, if (ok) "Done" else "Stopped", if (ok) "$title finished" else problem ?: "Open Codriod to see what happened")
            }
        }
        // Flips busy only once the job is fully finished — otherwise a recomposition between the finally
        // block and the coroutine's completion reads busy=true again and nothing invalidates it afterwards.
        job?.invokeOnCompletion { ui { busyState = false } }
    }

    /** Marks one step as running, runs [block] and records success, failure or stop. */
    suspend fun <T> step(id: String, block: suspend () -> T): T {
        val s = steps.firstOrNull { it.id == id }
        estimator.start(id)
        setStep(id, StepStatus.Running)
        logLine("▶ ${s?.title ?: id}")
        try {
            val result = block()
            estimator.finish(id)
            setStep(id, StepStatus.Done, (result as? String).orEmpty().take(200))
            return result
        } catch (error: CancellationException) {
            setStep(id, StepStatus.Failed, "stopped"); throw error
        } catch (error: Exception) {
            estimator.finish(id)
            val message = error.message ?: error.javaClass.simpleName
            logLine("✗ $message")
            setStep(id, StepStatus.Failed, message.take(200))
            ui { problem = "${s?.title ?: id}: $message" }
            throw AddonFailure(message)
        }
    }

    /** Skips a step that is already satisfied (e.g. "already installed"). */
    fun skip(id: String, detail: String) {
        estimator.skip(id)
        setStep(id, StepStatus.Skipped, detail)
    }

    /**
     * Runs an install script with progress reporting; on failure Codriod Doctor applies the fixes it can
     * (network, apt locks, npm cache…) and the script runs again — up to four attempts.
     */
    suspend fun script(id: String, script: String): String {
        val tried = HashSet<String>()
        var attempt = 0
        while (true) {
            attempt++
            val result = linux.runScript(script) { line ->
                if (line.startsWith("::step ")) setStep(id, if (attempt > 1) StepStatus.Fixing else StepStatus.Running, line.removePrefix("::step "))
                val parsed = InstallEstimator.parseLine(line)
                if (parsed != null) {
                    estimator.report(id, parsed.first)
                    parsed.second?.let { msg -> setStep(id, if (attempt > 1) StepStatus.Fixing else StepStatus.Running, msg) }
                }
                if (!InstallEstimator.isProgressLine(line)) logLine(line)
            }
            if (result.code == 0)
                return result.output.lines().lastOrNull { it.startsWith("::step ") }?.removePrefix("::step ").orEmpty()
            val failLine = result.output.lines().lastOrNull { it.startsWith("::fail ") }?.removePrefix("::fail ")
            if (failLine != null) throw AddonFailure(failLine, result.code)
            val fixes = LinuxDoctor.diagnose(result.output, result.code).filter { it.id !in tried }
            if (fixes.isEmpty() || attempt >= 4) {
                val summary = LinuxDoctor.summary(result.output)
                logLine("✗ Codriod Doctor could not fix this automatically: $summary")
                throw AddonFailure(summary, result.code)
            }
            for (fix in fixes) {
                tried += fix.id
                setStep(id, StepStatus.Fixing, fix.title)
                logLine("🩺 Doctor: ${fix.title}")
                if (!applyFix(fix)) { setStep(id, StepStatus.Failed, fix.title); throw AddonFailure(fix.title, result.code) }
            }
        }
    }

    /** Applies a Doctor fix; false when this add-on cannot apply it (then the user or the AI has to act). */
    private suspend fun applyFix(fix: LinuxDoctor.Fix): Boolean = when (val action = fix.action) {
        is LinuxDoctor.Action.Shell -> { linux.runScript(action.script) { logLine("   $it") }; true }
        is LinuxDoctor.Action.Wait -> { delay(action.seconds * 1000L); true }
        is LinuxDoctor.Action.Advice -> { logLine("   ℹ ${action.message}"); true }
        is LinuxDoctor.Action.Host -> when (action.kind) {
            LinuxDoctor.HostFix.RotateDns -> { rotateDns(); true }
            LinuxDoctor.HostFix.CleanTemp -> { runCatching { File(linux.rootfs, "tmp").listFiles()?.forEach { it.deleteRecursively() } }; true }
            LinuxDoctor.HostFix.DisableSeccomp -> { app.getSharedPreferences("linux", Context.MODE_PRIVATE).edit().putBoolean("no_seccomp", true).apply(); true }
            else -> false // Reextract / DeletePartialDownload / RewriteConfig belong to the Ubuntu installer
        }
    }

    private fun rotateDns() {
        val prefs = app.getSharedPreferences("linux", Context.MODE_PRIVATE)
        val next = prefs.getInt("dns", 0) + 1
        prefs.edit().putInt("dns", next).apply()
        runCatching {
            val content = RootfsSetup.files(next, "", linux.rootfs.absolutePath)["/etc/resolv.conf"].orEmpty()
            val file = File(linux.rootfs, "etc/resolv.conf")
            file.parentFile?.mkdirs()
            file.writeText(content)
        }
        logLine("   DNS switched to set $next")
    }
}
