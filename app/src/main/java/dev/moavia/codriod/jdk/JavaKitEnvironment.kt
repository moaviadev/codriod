package dev.moavia.codriod.jdk

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import dev.moavia.codriod.linux.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * Add-ons → Java & Kotlin: JDK (Temurin 21), the Kotlin compiler and Gradle, installed into the existing
 * Ubuntu rootfs next to Node.js and the AI CLIs. Detects what is already there before downloading anything,
 * verifies the tools actually run, and keeps versions in its own SharedPreferences file ("javakit").
 */
class JavaKitEnvironment(context: Context, private val scope: kotlinx.coroutines.CoroutineScope, val linux: LinuxEnvironment) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("javakit", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    val inst = AddonInstaller(context, scope, linux)

    /** tool → version ("java", "kotlin", "gradle"). */
    val versions = mutableStateMapOf<String, String>()
    /** Where the tool came from: "opt" (installed by this add-on) or "apt" (e.g. the Android SDK's JDK). */
    private val srcs = mutableStateMapOf<String, String>()
    var checking by mutableStateOf(false)
        private set
    /** Result of the last Verify Installation: tool → ok. */
    var verifyResults by mutableStateOf<Map<String, Boolean>>(emptyMap())
        private set
    var verifying by mutableStateOf(false)
        private set
    var verifyLog by mutableStateOf<List<String>>(emptyList())
        private set

    private fun ui(block: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block) }

    init {
        runCatching {
            val json = JSONObject(prefs.getString("versions", "{}")!!)
            json.keys().forEach { versions[it] = json.optString(it) }
            val src = JSONObject(prefs.getString("sources", "{}")!!)
            src.keys().forEach { srcs[it] = src.optString(it) }
        }
    }

    private fun save() {
        val v = JSONObject(); versions.forEach { (k, x) -> v.put(k, x) }
        val s = JSONObject(); srcs.forEach { (k, x) -> s.put(k, x) }
        prefs.edit().putString("versions", v.toString()).putString("sources", s.toString()).apply()
    }

    val ready get() = linux.ready
    val hasJava get() = versions.containsKey("java")
    fun source(tool: String) = srcs[tool].orEmpty()
    fun has(tool: String) = versions.containsKey(tool)
    fun short(tool: String): String? = versions[tool]?.split('.')?.take(2)?.joinToString(".")

    /** "Java 21 · Kotlin 2.2 · Gradle 9.2" for the Add-ons card, or "Set up" when nothing is installed. */
    fun summary(): String = listOf(
        short("java")?.let { "Java $it" },
        short("kotlin")?.let { "Kotlin $it" },
        short("gradle")?.let { "Gradle $it" }
    ).filterNotNull().joinToString(" · ").ifBlank { "Set up" }

    /** Detects existing installations before anything is downloaded (and keeps the UI honest afterwards). */
    fun refresh() {
        if (!linux.ready || inst.busy || checking) return
        checking = true
        scope.launch(Dispatchers.IO) { probe() }
    }

    /** Runs the probe and updates the state; also awaited (synchronously through [refreshBlocking]) by installs. */
    private suspend fun probe() {
        val found = LinkedHashMap<String, String>()
        val src = LinkedHashMap<String, String>()
        val r = linux.exec(listOf("/bin/bash", "-c", JavaKitScripts.PROBE), timeoutSeconds = 240)
        r.output.lines().forEach { line ->
            when {
                line.startsWith("::tool ") -> {
                    val parts = line.removePrefix("::tool ").trim().split(' ', limit = 2)
                    if (parts[0].isNotBlank()) found[parts[0]] = parts.getOrNull(1)?.trim().orEmpty()
                }
                line.startsWith("::src ") -> {
                    val parts = line.removePrefix("::src ").trim().split(' ')
                    if (parts.size == 2) src[parts[0]] = parts[1]
                }
                line.startsWith("::absent ") -> found.remove(line.removePrefix("::absent ").trim())
            }
        }
        ui {
            versions.clear(); versions.putAll(found)
            srcs.clear(); srcs.putAll(src)
            save()
            checking = false
        }
    }

    private suspend fun refreshBlocking() = probe()

    /** Lets other add-ons (e.g. extensions needing the JDK) update the detection after installing it. */
    suspend fun probeBlocking() = probe()

    /** Installs or updates one tool; Kotlin and Gradle pull in the JDK first when Java is missing. */
    fun install(tool: String) {
        if (!linux.ready || inst.busy || checking) return
        val plan = ArrayList<Pair<String, String>>()
        plan += "check" to "Checking Ubuntu"
        if (tool != "java" && !hasJava) plan += "tool:java" to "Installing the JDK (needed first)"
        plan += "tool:$tool" to when (tool) {
            "java" -> "Downloading & installing Temurin JDK 21"
            "kotlin" -> "Downloading & installing the Kotlin compiler"
            else -> "Downloading & installing Gradle"
        }
        plan += "verify" to when (tool) {
            "java" -> "Testing java"
            "kotlin" -> "Testing kotlinc"
            else -> "Testing gradle"
        }
        val label = when (tool) { "java" -> "JDK 21"; "kotlin" -> "Kotlin"; else -> "Gradle" }
        inst.run("Installing $label", plan) {
            inst.step("check") {
                if (!File(linux.rootfs, "usr/bin/env").exists()) throw AddonFailure("Ubuntu is not installed — set it up in Add-ons → Linux & AI CLIs first")
                "ok"
            }
            if (tool != "java" && !hasJava) {
                inst.step("tool:java") { inst.script("tool:java", JavaKitScripts.JDK) }
                refreshBlocking()
                if (!hasJava) throw AddonFailure("The JDK did not install, so $label cannot be installed")
            }
            inst.step("tool:$tool") { inst.script("tool:$tool", scriptFor(tool)) }
            refreshBlocking()
            inst.step("verify") {
                val v = versions[tool]
                if (v == null) throw AddonFailure("$label was installed, but it does not run — see the log")
                "$label runs (version $v)"
            }
        }
    }

    private fun scriptFor(tool: String) = when (tool) {
        "java" -> JavaKitScripts.JDK; "kotlin" -> JavaKitScripts.KOTLIN; else -> JavaKitScripts.GRADLE
    }

    /** Removes a tool that this add-on installed (apt's JDK stays untouched — the Android SDK owns it). */
    fun remove(tool: String) {
        if (!linux.ready || inst.busy || checking) return
        if (tool == "java" && source("java") == "apt") return
        ui { versions.remove(tool); srcs.remove(tool); save() }
        scope.launch(Dispatchers.IO) {
            linux.runScript(JavaKitScripts.remove(tool)) { }
            probe()
        }
    }

    /** Verify Installation: compiles and runs Java and Kotlin hello-worlds and runs a small Gradle build. */
    fun verify() {
        if (!linux.ready || inst.busy || checking || verifying) return
        verifying = true
        verifyResults = emptyMap()
        verifyLog = emptyList()
        scope.launch(Dispatchers.IO) {
            val r = linux.exec(listOf("/bin/bash", "-c", JavaKitScripts.VERIFY), timeoutSeconds = 1800) { line ->
                ui { verifyLog = verifyLog + line }
            }
            ui {
                verifyResults = mapOf(
                    "java" to r.output.contains("java-ok"),
                    "kotlin" to r.output.contains("kotlin-ok"),
                    "gradle" to r.output.contains("gradle-ok")
                ).filterKeys { it in versions.keys }
                verifying = false
            }
        }
    }
}
