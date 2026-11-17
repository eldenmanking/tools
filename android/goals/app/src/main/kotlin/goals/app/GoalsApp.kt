package goals.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import goals.app.data.SampleStatusSource
import goals.app.data.StatusSource
import goals.app.data.StatusStore
import goals.app.ui.GateScreen
import goals.app.ui.HomeScreen
import goals.core.GateState
import goals.core.StatusResult
import goals.core.gateState
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * Shows the gate or the home screen for the cached status, and checks for a fresh one
 * on start and on "Check again". The screen only changes when the status does, so e-ink
 * redraws stay rare. [onOpen] runs when the gate state becomes Open (GateActivity finishes).
 */
@Composable
fun GoalsApp(store: StatusStore, source: StatusSource, onOpen: (() -> Unit)? = null) {
    var cached by remember { mutableStateOf(store.load()) }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val check: () -> Unit = {
        if (!checking) {
            checking = true
            scope.launch {
                when (val r = source.fetch()) {
                    is StatusResult.Ok -> {
                        store.save(r.status)
                        cached = r.status
                        error = null
                    }
                    is StatusResult.Error -> error = r.message
                }
                checking = false
            }
        }
    }
    LaunchedEffect(Unit) { check() }

    val state = gateState(cached, Instant.now(), ZoneId.systemDefault())
    if (state == GateState.Open && onOpen != null) LaunchedEffect(Unit) { onOpen() }

    val status = cached
    if (state == GateState.Open && status != null) {
        HomeScreen(
            status = status,
            notice = notice,
            onSchedule = { notice = "The schedule editor comes in a later step." },
            onAllowlist = { notice = "The allowlist picker comes in a later step." },
        )
    } else {
        GateScreen(
            status = status,
            unknownReason = (state as? GateState.Unknown)?.reason,
            checking = checking,
            error = error,
            onCheckAgain = check,
            onDebugUnlock = if (BuildConfig.DEBUG && source === SampleStatusSource) {
                { SampleStatusSource.markDone(); check() }
            } else {
                null
            },
        )
    }
}
