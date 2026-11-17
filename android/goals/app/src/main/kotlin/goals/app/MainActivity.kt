package goals.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import goals.app.data.SampleStatusSource
import goals.app.data.StatusStore

/** The app's own screen: the home screen, or the gate while today is locked. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = StatusStore(this)
        setContent { GoalsApp(store, SampleStatusSource) }
    }
}
