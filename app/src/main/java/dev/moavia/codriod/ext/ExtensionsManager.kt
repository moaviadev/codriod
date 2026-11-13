package dev.moavia.codriod.ext

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import dev.moavia.codriod.jdk.JavaKitEnvironment
import dev.moavia.codriod.linux.AddonInstaller
import dev.moavia.codriod.linux.AddonFailure
import dev.moavia.codriod.linux.LinuxEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * Add-ons → More extensions: installs and manages language servers inside the Ubuntu rootfs, reports the
 * bundled debuggers' availability, and switches the built-in editor's colour theme. State lives in its own
 * SharedPreferences file ("extensions").
 */
class ExtensionsManager(
    context: Context,
    private val scope: kotlinx.coroutines.CoroutineScope,
    val linux: LinuxEnvironment,
    val javaKit: JavaKitEnvironment
) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("extensions", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    val inst = AddonInstaller(context, scope, linux)

    /** extension id → installed version (""). */
    val installed = mutableStateMapOf<String, String>()
    /** ids the user switched off (they stay installed). */
    val disabled = mutableStateListOf<String>()
    /** Live probe results for things that cannot be installed (bundled debuggers): id → version or "". */
    val availability = mutableStateMapOf<String, String>()
    var checking by mutableStateOf(false)
        private set

    /** Active editor theme id, or null for the app's own default (dark/light follows the system). */
    var activeThemeId by mutableStateOf(prefs.getString("theme", null))
        private set
    val activeTheme: EditorTheme? get() = EditorThemes.byId(activeThemeId)

    private fun ui(block: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block) }

    init {
        runCatching {
            val json = JSONObject(prefs.getString("installed", "{}")!!)
            json.keys().forEach { installed[it] = json.optString(it) }
            disabled.addAll(prefs.getString("disabled", "")!!.split(',').filter { it.isNotBlank() })
        }
    }

    private fun save() {
        val json = JSONObject(); installed.forEach { (k, v) -> json.put(k, v) }
        prefs.edit().putString("installed", json.toString())
            .putString("disabled", disabled.joinToString(",")).apply()
    }

    val enabledCount get() = installed.size + (activeThemeId?.let { if (it.isNotBlank()) 1 else 0 } ?: 0)

    /** "2 servers · Dracula" style summary for the Add-ons card. */
    fun summary(): String = buildList {
        val servers = installed.size
        if (servers > 0) add(if (servers == 1) "1 language server" else "$servers language servers")
        activeTheme?.let { add(it.name.substringBefore(" (")) }
    }.joinToString(" · ").ifBlank { "Set up" }

    fun isEnabled(id: String) = id !in disabled

    fun setEnabled(id: String, value: Boolean) {
        if (value) disabled.remove(id) else if (id !in disabled) disabled.add(id)
        save()
    }

    fun setTheme(id: String?) {
        activeThemeId = id
        prefs.edit().putString("theme", id).apply()
    }

    /** The version command that proves an installed extension runs, run in Ubuntu. */
    private fun versionCommand(spec: ExtSpec): String? = when (spec.category) {
        ExtCategory.LanguageServer -> when (spec.id) {
            "java-ls" -> "cat /opt/jdtls/.codriod-version 2>/dev/null"
            "kotlin-ls" -> "cat /opt/kotlin-lsp/.codriod-version 2>/dev/null"
            else -> "${spec.binary} --version 2>&1 | head -n1"
        }
        else -> null
    }

    /** Probes Ubuntu for what exists right now (installed servers, bundled debuggers). */
    fun refresh() {
        if (!linux.ready || inst.busy || checking) return
        checking = true
        scope.launch(Dispatchers.IO) {
            val script = StringBuilder("export PATH=/opt/jdk/bin:/opt/kotlin/bin:/opt/gradle/bin:\$PATH\n")
            ExtensionsCatalog.all.filter { it.category == ExtCategory.Debugger }.forEach {
                script.append("if command -v ${it.binary} >/dev/null 2>&1; then\n")
                script.append("  echo \"::ok ${it.id} \$(")
                script.append(when (it.id) {
                    "jdb" -> "jdb -version 2>&1 | head -n1 | sed 's/.*version \\([0-9][^ )]*\\).*/\\1/'"
                    "node-inspect" -> "node --version | sed 's/^v//'"
                    else -> "python3 --version 2>&1 | sed 's/^Python //'"
                })
                script.append(")\"\nelse echo \"::absent ${it.id}\"; fi\n")
            }
            ExtensionsCatalog.all.filter { it.category == ExtCategory.LanguageServer && it.installScript.isNotBlank() }.forEach { spec ->
                val present = when (spec.id) {
                    "java-ls" -> "test -x /opt/jdtls/bin/jdtls"
                    "kotlin-ls" -> "test -e /usr/local/bin/kotlin-lsp"
                    else -> "command -v ${spec.binary}"
                }
                script.append("if $present >/dev/null 2>&1; then echo \"::ok ${spec.id}\"; else echo \"::absent ${spec.id}\"; fi\n")
            }
            val found = LinkedHashMap<String, String>()
            val r = linux.exec(listOf("/bin/bash", "-c", script.toString()), timeoutSeconds = 240)
            r.output.lines().forEach { line ->
                when {
                    line.startsWith("::ok ") -> {
                        val rest = line.removePrefix("::ok ").trim()
                        val id = rest.split(' ').firstOrNull().orEmpty()
                        val spec = ExtensionsCatalog.byId(id) ?: return@forEach
                        found[id] = if (spec.category == ExtCategory.Debugger) rest.removePrefix(id).trim() else installed[id].orEmpty()
                    }
                    line.startsWith("::absent ") -> found.remove(line.removePrefix("::absent ").trim())
                }
            }
            ui {
                availability.clear(); availability.putAll(found.filterKeys { ExtensionsCatalog.byId(it)?.category == ExtCategory.Debugger })
                // keep the install state honest: forget servers that no longer exist
                installed.keys.filter { it !in found }.forEach { installed.remove(it) }
                found.filterKeys { ExtensionsCatalog.byId(it)?.category == ExtCategory.LanguageServer }.forEach { (k, v) ->
                    if (k !in installed && v != null) installed[k] = v
                }
                save()
                checking = false
            }
        }
    }

    /** Installs or updates one language server into Ubuntu. */
    fun install(id: String) {
        val spec = ExtensionsCatalog.byId(id) ?: return
        if (!linux.ready || inst.busy || checking) return
        val plan = ArrayList<Pair<String, String>>()
        plan += "check" to "Checking Ubuntu"
        if (spec.needsJava && !javaKit.hasJava) plan += "jdk" to "Installing the JDK (needed first)"
        plan += "install" to "Installing ${spec.name}"
        plan += "verify" to "Testing ${spec.name}"
        inst.run("Installing ${spec.name}", plan) {
            inst.step("check") {
                if (!File(linux.rootfs, "usr/bin/env").exists()) throw AddonFailure("Ubuntu is not installed — set it up in Add-ons → Linux & AI CLIs first")
                "ok"
            }
            if (spec.needsJava && !javaKit.hasJava) {
                inst.step("jdk") { inst.script("jdk", dev.moavia.codriod.jdk.JavaKitScripts.JDK) }
                javaKit.probeBlocking()
                if (!javaKit.hasJava) throw AddonFailure("The JDK did not install, so ${spec.name} cannot be installed")
            }
            inst.step("install") { inst.script("install", spec.installScript) }
            inst.step("verify") {
                val version = probeVersion(spec)
                ui { installed[id] = version; save() }
                "${spec.name} runs${if (version.isBlank()) "" else " ($version)"}"
            }
        }
    }

    /** Runs the version command again after an install; "" counts as installed-but-version-unknown. */
    private suspend fun probeVersion(spec: ExtSpec): String {
        val cmd = versionCommand(spec) ?: return ""
        val r = linux.exec(listOf("/bin/bash", "-c", "export PATH=/opt/jdk/bin:/opt/kotlin/bin:/opt/gradle/bin:\$PATH; $cmd"), timeoutSeconds = 240)
        val out = r.output.lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (out.isBlank() && r.code != 0) throw AddonFailure("${spec.name} was installed, but it does not run — see the log")
        return out
    }

    /** Uninstalls a language server (runs its remove script and forgets it). */
    fun remove(id: String) {
        val spec = ExtensionsCatalog.byId(id) ?: return
        if (!linux.ready || inst.busy || spec.installScript.isBlank()) return
        ui { installed.remove(id); save() }
        scope.launch(Dispatchers.IO) {
            linux.runScript(spec.removeScript) { }
            refresh()
        }
    }

    /** The bundled debuggers cannot be installed or removed — their card shows what they depend on. */
    fun debuggerAvailable(id: String): Pair<Boolean, String> {
        val spec = ExtensionsCatalog.byId(id) ?: return false to ""
        val present = availability.containsKey(id)
        val version = availability[id].orEmpty()
        val requirement = when (spec.id) {
            "jdb" -> "needs the JDK (Java & Kotlin add-on)"
            "node-inspect" -> "needs Node.js (comes with the AI CLIs)"
            else -> "needs Python (installed with Ubuntu's basic tools)"
        }
        return present to if (present) "Available${if (version.isBlank()) "" else " · $version"}" else "Not available · $requirement"
    }
}
