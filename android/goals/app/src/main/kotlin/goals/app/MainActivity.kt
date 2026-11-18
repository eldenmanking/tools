package goals.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import goals.app.data.StatusSources
import goals.app.data.StatusStore
import goals.app.ui.SetupScreen
import goals.app.watch.WatchService

/**
 * The app's own screen: the setup checklist until the required permissions are granted
 * (the optional ones until skipped), then the home screen, which never gates the app itself.
 * Starts the watcher.
 */
class MainActivity : ComponentActivity() {
    private var missing by mutableStateOf(emptyList<Need>())
    private var skippedOptional by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = StatusStore(this)
        setContent {
            val required = missing.any { it.required }
            if (required || (missing.isNotEmpty() && !skippedOptional)) {
                SetupScreen(
                    missing = missing,
                    onOpen = { startActivity(Permissions.settingsIntent(this, it)) },
                    onSkip = if (required) null else ({ skippedOptional = true }),
                )
            } else {
                GoalsApp(store, StatusSources.default)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check every time: the user comes back here from the settings screens.
        missing = Permissions.missing(this)
        WatchService.start(this)
    }
}
