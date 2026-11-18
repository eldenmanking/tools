package goals.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import goals.app.data.StatusSources
import goals.app.data.StatusStore

/**
 * Full-screen gate shown over a gated app. Back goes to the launcher, never to the app
 * underneath; once a check says today is open, it finishes and the app is usable again.
 */
class GateActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })
        val store = StatusStore(this)
        setContent { GoalsApp(store, StatusSources.default, onOpen = { finish() }) }
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }
}
