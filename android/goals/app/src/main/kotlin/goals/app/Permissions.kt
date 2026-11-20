package goals.app

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.os.Process
import android.provider.Settings

/** What the gate needs the user to grant in system settings, in the order to ask. */
enum class Need(val title: String, val why: String, val required: Boolean) {
    USAGE("Usage access", "To see which app is open.", required = true),
    OVERLAY("Display over other apps", "To show the gate over a locked app.", required = true),
    NOTIFICATIONS("Notifications", "To show your streak and today's status.", required = false),
    BATTERY("Unrestricted battery", "So the tablet doesn't stop the gate in the background.", required = false),
}

/** Checks and settings screens for [Need]. Plain Android, safe to use from the :watch process. */
object Permissions {
    fun missing(context: Context): List<Need> = Need.entries.filter { !granted(context, it) }

    /** The gate can run (it may still lack the optional ones). */
    fun canWatch(context: Context): Boolean = missing(context).none { it.required }

    fun granted(context: Context, need: Need): Boolean = when (need) {
        Need.USAGE -> context.getSystemService(AppOpsManager::class.java)
            .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) ==
            AppOpsManager.MODE_ALLOWED
        Need.OVERLAY -> Settings.canDrawOverlays(context)
        Need.NOTIFICATIONS -> context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        Need.BATTERY -> context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }

    /** The system screen where the user grants [need]. */
    fun settingsIntent(context: Context, need: Need): Intent {
        val pkg = Uri.parse("package:${context.packageName}")
        return when (need) {
            Need.USAGE -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            Need.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)
            Need.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            Need.BATTERY -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
        }
    }
}
