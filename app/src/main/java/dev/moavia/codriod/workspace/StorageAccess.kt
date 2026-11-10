package dev.moavia.codriod.workspace

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * Where projects live. With "All files access" (Android 11+) or the storage permission (Android 10 and older),
 * projects are normal folders in the phone's main storage (Internal storage/Codriod/…), visible to file managers,
 * other apps and computers, and opened folders are edited in place. Add-ons (Ubuntu, CLIs) stay in app storage.
 */
object StorageAccess {
    fun granted(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else context.checkPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE, android.os.Process.myPid(), android.os.Process.myUid()) ==
            PackageManager.PERMISSION_GRANTED

    /** Android 11+: the settings page where the user allows "All files access" for Codriod. */
    fun settingsIntent(context: Context): Intent = if (Build.VERSION.SDK_INT >= 30)
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + context.packageName))
    else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))

    /** Android 10 and older ask with the normal permission dialog. */
    val legacyPermissions = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    val usesSettingsPage get() = Build.VERSION.SDK_INT >= 30

    @Suppress("DEPRECATION")
    fun phoneStorage(): File = Environment.getExternalStorageDirectory()

    /** Internal storage/Codriod */
    fun projectsHome(): File = File(phoneStorage(), "Codriod")

    /** "Internal storage/Codriod/My App" for showing a path to people. */
    fun displayPath(file: File): String {
        val root = phoneStorage().absolutePath
        return if (file.absolutePath.startsWith(root)) "Internal storage" + file.absolutePath.removePrefix(root) else file.absolutePath
    }
}
