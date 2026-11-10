package dev.moavia.codriod.workspace

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Phone commands for Codriod's terminal and agent: `codriod open-url`, `share`, `clipboard`, `notify`, … plus
 * Termux-style aliases such as `termux-open-url`.
 *
 * Android 10+ forbids executing files from an app's data folder, so the commands are shell functions
 * (loaded from an rc file) that talk to the app through request files in a private folder.
 */
object CodriodCommands {
    /** What the commands need from the open workspace. */
    interface Host {
        fun browse(url: String)
        /** Saves a screenshot of the browser to [file]; returns null on success or an error. */
        fun screenshot(file: File): String?
        /** Opens [file] in Codriod's editor if it belongs to the open project. */
        fun openInEditor(file: File): Boolean
    }

    @Volatile var host: Host? = null
    private var started = false
    private val main by lazy { Handler(Looper.getMainLooper()) }

    fun baseDir(context: Context) = File(context.filesDir, "codriod")
    fun rcFile(context: Context) = File(baseDir(context), "codriod.rc")
    fun ipcDir(context: Context) = File(baseDir(context), "ipc")

    fun env(context: Context): Map<String, String> =
        mapOf("CODRIOD_IPC" to ipcDir(context).absolutePath, "CODRIOD_RC" to rcFile(context).absolutePath)

    /** Line to put in a shell rc or before an agent command. */
    fun sourceLine(context: Context) = "[ -f '${rcFile(context).absolutePath}' ] && . '${rcFile(context).absolutePath}'"

    @Synchronized
    fun install(context: Context) {
        val app = context.applicationContext
        val ipc = ipcDir(app)
        File(ipc, "req").mkdirs(); File(ipc, "res").mkdirs()
        rcFile(app).writeText(rcScript(ipc.absolutePath))
        if (started) return
        started = true
        Thread({ loop(app) }, "codriod-commands").apply { isDaemon = true; start() }
    }

    private fun loop(app: Context) {
        val ipc = ipcDir(app)
        while (true) {
            runCatching { processPending(ipc) { cwd, args, stdin -> handle(app, cwd, args, stdin) } }
            Thread.sleep(80)
        }
    }

    /** Answers every waiting request in [ipc]. Kept free of Android types so it can be tested with a real shell. */
    fun processPending(ipc: File, handler: (File, List<String>, String?) -> Pair<Int, String>) {
        val req = File(ipc, "req")
        req.listFiles { f -> f.name.endsWith(".req") }?.sortedBy { it.lastModified() }?.forEach { request ->
            val id = request.name.removeSuffix(".req")
            val lines = request.readText().split('\n').let { if (it.lastOrNull() == "") it.dropLast(1) else it }
            val input = File(req, "$id.in")
            val stdin = input.takeIf { it.isFile }?.readText()
            request.delete(); input.delete()
            val cwd = File(lines.firstOrNull().orEmpty().ifBlank { "/" })
            val args = lines.drop(1).map { it.replace('\u001e', '\n') }
            val (code, out) = try { handler(cwd, args, stdin) } catch (error: Exception) { 1 to "codriod: ${error.message ?: error.javaClass.simpleName}" }
            val res = File(ipc, "res").apply { mkdirs() }
            File(res, "$id.out").writeText(if (out.isEmpty() || out.endsWith("\n")) out else out + "\n")
            File(res, "$id.code").writeText(code.toString())
            File(res, "$id.done").writeText("")
        }
        // Stale leftovers from a killed shell.
        req.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 120_000 }?.forEach { it.delete() }
    }

    /** Runs [block] on the main thread and waits for it. */
    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val result = AtomicReference<Result<T>>()
        val latch = CountDownLatch(1)
        main.post { result.set(runCatching(block)); latch.countDown() }
        check(latch.await(15, TimeUnit.SECONDS)) { "Codriod is busy, try again" }
        return result.get().getOrThrow()
    }

    fun handle(app: Context, cwd: File, args: List<String>, stdin: String?): Pair<Int, String> {
        val command = args.firstOrNull() ?: return 0 to HELP
        val rest = args.drop(1)
        return when (command) {
            "open-url", "url" -> {
                val url = rest.firstOrNull()?.trim() ?: return 2 to "usage: codriod open-url URL"
                openUrl(app, url)
                0 to "✓ opened $url"
            }
            "open", "xdg-open" -> {
                val target = rest.firstOrNull()?.trim() ?: return 2 to "usage: codriod open FILE|URL"
                if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(target) && !target.startsWith("/")) { openUrl(app, target); return 0 to "✓ opened $target" }
                val file = resolve(cwd, target)
                require(file.exists()) { "$target: no such file" }
                if (onMain { host?.openInEditor(file) } == true) 0 to "✓ opened ${file.name} in the editor"
                else { openFile(app, file); 0 to "✓ opened ${file.name}" }
            }
            "browse" -> {
                val url = rest.firstOrNull()?.trim() ?: return 2 to "usage: codriod browse URL"
                val h = host ?: return 1 to "codriod: open a project to use the browser"
                onMain { h.browse(url) }
                0 to "✓ opened $url in Codriod browser"
            }
            "share" -> share(app, cwd, rest, stdin)
            "clipboard", "clip" -> when (rest.firstOrNull()) {
                "get", null -> 0 to onMain { clipboard(app).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(app)?.toString().orEmpty() }
                "set" -> {
                    val text = rest.drop(1).joinToString(" ").ifEmpty { stdin?.removeSuffix("\n").orEmpty() }
                    onMain { clipboard(app).setPrimaryClip(ClipData.newPlainText("Codriod", text)) }
                    0 to "✓ copied ${text.length} characters"
                }
                else -> 2 to "usage: codriod clipboard get|set [TEXT]"
            }
            "notify", "notification" -> notify(app, rest)
            "toast" -> {
                val text = rest.joinToString(" ").ifBlank { stdin.orEmpty() }.ifBlank { return 2 to "usage: codriod toast TEXT" }
                onMain { Toast.makeText(app, text.take(300), Toast.LENGTH_LONG).show() }
                0 to "✓"
            }
            "vibrate" -> {
                val ms = rest.zipWithNext().firstOrNull { it.first == "-d" }?.second?.toLongOrNull() ?: 300L
                vibrate(app, ms.coerceIn(10, 5000)); 0 to "✓ vibrated ${ms} ms"
            }
            "screenshot" -> {
                val h = host ?: return 1 to "codriod: open a project and the browser first"
                val file = resolve(cwd, rest.firstOrNull() ?: "screenshot-${System.currentTimeMillis()}.png")
                val error = onMain { h.screenshot(file) }
                if (error == null) 0 to "✓ saved ${file.absolutePath}" else 1 to "codriod: $error"
            }
            "help", "--help", "-h" -> 0 to HELP
            else -> 2 to "codriod: unknown command '$command'. Try: codriod help"
        }
    }

    private fun resolve(cwd: File, path: String) = File(path).let { if (it.isAbsolute) it else File(cwd, path) }.canonicalFile

    private fun clipboard(app: Context) = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun openUrl(app: Context, url: String) {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase()
        require(scheme != null && scheme !in setOf("file", "content", "intent", "javascript")) { "unsupported link: $url" }
        onMain { app.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun contentUri(app: Context, file: File): Uri = FileProvider.getUriForFile(app, app.packageName + ".files", file)

    private fun mime(file: File) = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"

    private fun openFile(app: Context, file: File) = onMain {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(contentUri(app, file), mime(file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(Intent.createChooser(intent, "Open ${file.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private val appPackages = mapOf("whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"), "telegram" to listOf("org.telegram.messenger"),
        "gmail" to listOf("com.google.android.gm"), "drive" to listOf("com.google.android.apps.docs"), "messenger" to listOf("com.facebook.orca"))

    private fun share(app: Context, cwd: File, rest: List<String>, stdin: String?): Pair<Int, String> {
        var target: String? = null
        val words = mutableListOf<String>()
        var i = 0
        while (i < rest.size) {
            if (rest[i] == "--app" || rest[i] == "-a") { target = rest.getOrNull(i + 1)?.lowercase(); i += 2 } else { words += rest[i]; i++ }
        }
        val single = words.singleOrNull()?.let { resolve(cwd, it) }?.takeIf { it.isFile }
        val intent = Intent(Intent.ACTION_SEND)
        val label: String
        if (single != null) {
            intent.type = mime(single)
            intent.putExtra(Intent.EXTRA_STREAM, contentUri(app, single)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            label = "${single.name}, ${single.length() / 1024} KB"
        } else {
            val text = words.joinToString(" ").ifEmpty { stdin.orEmpty() }.ifBlank { return 2 to "usage: codriod share FILE|TEXT [--app whatsapp]" }
            intent.type = "text/plain"; intent.putExtra(Intent.EXTRA_TEXT, text)
            label = "${text.length} characters"
        }
        val pkg = target?.let { name -> (appPackages[name] ?: listOf(name)).firstOrNull { installed(app, it) } }
        onMain {
            if (pkg != null) app.startActivity(intent.setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            else app.startActivity(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
        return 0 to (if (pkg != null) "✓ shared to $target ($label)" else if (target != null) "✓ $target is not installed; share sheet opened ($label)"
            else "✓ share sheet opened ($label)")
    }

    private fun installed(app: Context, pkg: String) = runCatching { app.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    private fun notify(app: Context, rest: List<String>): Pair<Int, String> {
        var title = "Codriod"
        val text = mutableListOf<String>()
        var i = 0
        while (i < rest.size) when (rest[i]) {
            "-t", "--title" -> { title = rest.getOrNull(i + 1) ?: title; i += 2 }
            "-c", "--content" -> { text += rest.getOrNull(i + 1).orEmpty(); i += 2 }
            else -> { text += rest[i]; i++ }
        }
        val body = text.joinToString(" ").ifBlank { return 2 to "usage: codriod notify [-t TITLE] TEXT" }
        val allowed = Build.VERSION.SDK_INT < 33 || app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed) {
            onMain { Toast.makeText(app, "$title: $body", Toast.LENGTH_LONG).show() }
            return 0 to "✓ shown as a toast (allow notifications for Codriod in Android settings to get real notifications)"
        }
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("codriod", "Codriod", NotificationManager.IMPORTANCE_DEFAULT))
        manager.notify((System.currentTimeMillis() % 100000).toInt(), NotificationCompat.Builder(app, "codriod")
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setAutoCancel(true).build())
        return 0 to "✓ notification sent"
    }

    @Suppress("DEPRECATION")
    private fun vibrate(app: Context, ms: Long) {
        val vibrator = app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)) else vibrator.vibrate(ms)
    }

    const val HELP = """Codriod commands (Termux names work too, e.g. termux-open-url):
  codriod open-url URL           open a link in another app (browser, WhatsApp, maps…)
  codriod browse URL             open a page in Codriod's browser
  codriod open FILE|URL          open a file in the editor or another app
  codriod share FILE|TEXT [--app whatsapp|telegram|gmail]
                                 Android share sheet
  codriod clipboard get          print the phone clipboard
  codriod clipboard set [TEXT]   copy TEXT (or stdin) to the clipboard
  codriod notify [-t TITLE] TEXT show a notification
  codriod toast TEXT             show a short message
  codriod vibrate [-d MS]        vibrate the phone
  codriod screenshot [FILE.png]  save a screenshot of Codriod's browser"""

    /** mksh-compatible functions. Only uses toybox commands that exist on every Android version. */
    fun rcScript(ipc: String): String = """
# Codriod phone commands — generated by the app; edits are overwritten.
codriod() {
  _cd_ipc="${'$'}{CODRIOD_IPC:-$ipc}"
  mkdir -p "${'$'}_cd_ipc/req" "${'$'}_cd_ipc/res" 2>/dev/null
  _cd_id="${'$'}${'$'}-${'$'}RANDOM-${'$'}RANDOM"
  {
    printf '%s\n' "${'$'}PWD"
    for _cd_a in "${'$'}@"; do printf '%s' "${'$'}_cd_a" | tr '\n' '\036'; printf '\n'; done
  } > "${'$'}_cd_ipc/req/${'$'}_cd_id.tmp"
  if [ "${'$'}1" = clipboard ] && [ "${'$'}2" = set ] && [ ${'$'}# -lt 3 ] && [ ! -t 0 ]; then cat > "${'$'}_cd_ipc/req/${'$'}_cd_id.in"; fi
  mv "${'$'}_cd_ipc/req/${'$'}_cd_id.tmp" "${'$'}_cd_ipc/req/${'$'}_cd_id.req"
  _cd_n=0
  while [ ! -f "${'$'}_cd_ipc/res/${'$'}_cd_id.done" ]; do
    sleep 0.1 2>/dev/null || sleep 1
    _cd_n=${'$'}((_cd_n + 1))
    if [ ${'$'}_cd_n -gt 200 ]; then
      echo "codriod: the Codriod app did not answer; keep it open and try again" >&2
      rm -f "${'$'}_cd_ipc/req/${'$'}_cd_id.req" "${'$'}_cd_ipc/req/${'$'}_cd_id.in"
      return 1
    fi
  done
  cat "${'$'}_cd_ipc/res/${'$'}_cd_id.out" 2>/dev/null
  _cd_code=${'$'}(cat "${'$'}_cd_ipc/res/${'$'}_cd_id.code" 2>/dev/null)
  rm -f "${'$'}_cd_ipc/res/${'$'}_cd_id.out" "${'$'}_cd_ipc/res/${'$'}_cd_id.code" "${'$'}_cd_ipc/res/${'$'}_cd_id.done"
  return ${'$'}{_cd_code:-1}
}
alias termux-open-url='codriod open-url' 2>/dev/null
alias termux-open='codriod open' 2>/dev/null
alias termux-share='codriod share' 2>/dev/null
alias termux-clipboard-get='codriod clipboard get' 2>/dev/null
alias termux-clipboard-set='codriod clipboard set' 2>/dev/null
alias termux-toast='codriod toast' 2>/dev/null
alias termux-notification='codriod notify' 2>/dev/null
alias termux-vibrate='codriod vibrate' 2>/dev/null
alias xdg-open='codriod open' 2>/dev/null
""".trimStart()
}
