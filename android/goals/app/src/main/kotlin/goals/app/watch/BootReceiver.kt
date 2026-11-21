package goals.app.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the watcher after a reboot or an app update. Runs in :watch, so no UI process starts. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> WatchService.start(context)
        }
    }
}
