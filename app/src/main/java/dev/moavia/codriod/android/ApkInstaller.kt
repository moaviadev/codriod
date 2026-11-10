package dev.moavia.codriod.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Installs, uninstalls and starts apps the user (or an AI agent) built: `codriod install app.apk`,
 * `codriod uninstall com.example.app`, `codriod launch com.example.app`.
 * Android always shows its own confirmation for installs from Codriod; the result (installed, cancelled, failed and why)
 * comes back to the command so agents know what happened.
 */
object ApkInstaller {
    private const val ACTION = "dev.moavia.codriod.PACKAGE_STATUS"
    private class Waiter { val latch = CountDownLatch(1); val result = AtomicReference<Pair<Int, String>>() }
    private val waiting = ConcurrentHashMap<Int, Waiter>()

    fun canInstall(context: Context) = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** Opens Android's "Install unknown apps" switch for Codriod. */
    fun openInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) runCatching {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun sender(context: Context, id: Int, kind: String): android.content.IntentSender {
        val intent = Intent(context, PackageStatusReceiver::class.java).setAction(ACTION).putExtra("codriod_id", id).putExtra("kind", kind)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(context, id, intent, flags).intentSender
    }

    private fun await(id: Int, minutes: Long): Pair<Int, String> {
        val w = waiting[id] ?: return 1 to "codriod: internal error"
        val done = w.latch.await(minutes, TimeUnit.MINUTES)
        waiting.remove(id)
        return if (done) w.result.get() else 1 to "codriod: no answer from the installer after $minutes minutes"
    }

    /** Blocks until Android reports the result (the user confirms in Android's dialog). */
    fun install(context: Context, apk: File): Pair<Int, String> {
        if (!apk.isFile) return 2 to "codriod: $apk: no such file"
        if (!apk.name.endsWith(".apk", true)) return 2 to "codriod: ${apk.name} is not an .apk file"
        if (!canInstall(context)) {
            openInstallPermission(context)
            return 3 to "codriod: allow Codriod to install apps (the settings page is open: turn on “Allow from this source”), then run the command again"
        }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setSize(apk.length())
        val id = installer.createSession(params)
        waiting[id] = Waiter()
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; session.fsync(out) }
                session.commit(sender(context, id, "install"))
            }
        } catch (error: Exception) {
            waiting.remove(id); runCatching { installer.abandonSession(id) }
            return 1 to "codriod: could not start the install: ${error.message}"
        }
        return await(id, 10)
    }

    fun uninstall(context: Context, packageName: String): Pair<Int, String> {
        val id = (System.nanoTime() and 0x3fffffff).toInt()
        waiting[id] = Waiter()
        return try {
            context.packageManager.packageInstaller.uninstall(packageName, sender(context, id, "uninstall"))
            await(id, 5)
        } catch (error: Exception) { waiting.remove(id); 1 to "codriod: could not uninstall $packageName: ${error.message}" }
    }

    fun launch(context: Context, packageName: String): Pair<Int, String> {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return 1 to "codriod: $packageName is not installed or has no launcher screen"
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return 0 to "✓ started $packageName"
    }

    /** Human-readable result for an installer status. */
    fun describe(kind: String, status: Int, message: String?, packageName: String?): Pair<Int, String> {
        val pkg = packageName?.let { " $it" }.orEmpty()
        return when (status) {
            PackageInstaller.STATUS_SUCCESS -> 0 to (if (kind == "uninstall") "✓ uninstalled$pkg" else "✓ installed$pkg")
            PackageInstaller.STATUS_FAILURE_ABORTED -> 4 to "codriod: cancelled in Android's dialog"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> 5 to "codriod: an app with the same package name but a different signature is installed. Uninstall it first: codriod uninstall <package>"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> 6 to "codriod: the app is not compatible with this phone (minSdk, CPU (ABI) or a newer version is installed). ${message.orEmpty()}"
            PackageInstaller.STATUS_FAILURE_INVALID -> 7 to "codriod: the APK is invalid or not signed. Build a debug APK (signed automatically) or sign it. ${message.orEmpty()}"
            PackageInstaller.STATUS_FAILURE_STORAGE -> 8 to "codriod: not enough storage to install"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> 9 to "codriod: Android blocked the install (Play Protect or a device policy). ${message.orEmpty()}"
            else -> 1 to "codriod: $kind failed (${status}): ${message.orEmpty()}"
        }
    }

    internal fun onStatus(context: Context, intent: Intent) {
        val id = intent.getIntExtra("codriod_id", -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION") val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirm != null) runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { finish(id, 1 to "codriod: could not show Android's install dialog: ${it.message}") }
            return
        }
        finish(id, describe(intent.getStringExtra("kind") ?: "install", status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
            intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)))
    }

    private fun finish(id: Int, result: Pair<Int, String>) {
        val w = waiting[id] ?: return
        w.result.set(result); w.latch.countDown()
    }
}

class PackageStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = ApkInstaller.onStatus(context, intent)
}
