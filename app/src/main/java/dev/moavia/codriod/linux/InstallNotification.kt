package dev.moavia.codriod.linux

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Keeps Codriod running while Ubuntu or a CLI installs, so the user can switch apps, and shows the progress with
 * the time left in the notification shade.
 */
class LinuxInstallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = InstallNotification.build(this, intent?.getStringExtra("title") ?: "Installing", "Starting…", 0, ongoing = true)
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) startForeground(InstallNotification.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(InstallNotification.ID, notification)
        }.onFailure { stopSelf() }
        return START_NOT_STICKY
    }
}

object InstallNotification {
    const val ID = 4107
    private const val CHANNEL = "linux_install"
    @Volatile private var running = false
    private var lastPercent = -1
    private var lastText = ""

    private fun channel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL) == null)
                manager.createNotificationChannel(NotificationChannel(CHANNEL, "Installs", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Progress of Ubuntu and AI CLI installs" })
        }
    }

    fun build(context: Context, title: String, text: String, percent: Int, ongoing: Boolean): android.app.Notification {
        channel(context)
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0))
        }
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(if (ongoing) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .apply { if (ongoing) setProgress(100, percent.coerceIn(0, 100), percent <= 0) }
            .apply { open?.let { setContentIntent(it) } }
            .build()
    }

    fun start(context: Context, title: String) {
        running = true; lastPercent = -1
        runCatching {
            val intent = Intent(context, LinuxInstallService::class.java).putExtra("title", title)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
    }

    fun update(context: Context, title: String, text: String, percent: Int) {
        if (!running || (percent == lastPercent && text == lastText)) return
        lastPercent = percent; lastText = text
        runCatching { context.getSystemService(NotificationManager::class.java)?.notify(ID, build(context, title, "$percent% · $text", percent, ongoing = true)) }
    }

    fun stop(context: Context, title: String, text: String) {
        if (!running) return
        running = false
        runCatching { context.stopService(Intent(context, LinuxInstallService::class.java)) }
        runCatching { context.getSystemService(NotificationManager::class.java)?.notify(ID + 1, build(context, title, text, 100, ongoing = false)) }
    }
}
