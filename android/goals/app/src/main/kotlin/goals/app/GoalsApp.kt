package goals.app

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import goals.app.data.SampleStatusSource
import goals.app.data.StatusSource
import goals.app.data.StatusSources
import goals.app.data.StatusStore
import goals.app.ui.AllowlistScreen
import goals.app.ui.AppFrame
import goals.app.ui.DayPicker
import goals.app.ui.GoalsScreen
import goals.app.ui.ScheduleMode
import goals.app.ui.GateScreen
import goals.app.ui.HomeCheck
import goals.app.ui.HomeScreen
import goals.app.ui.ScheduleScreen
import goals.app.ui.Tab
import goals.core.GateState
import goals.core.RefreshTrigger
import goals.core.StatusResult
import goals.core.gateState
import goals.core.shouldFetch
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Shows the gate or the home screen for the cached status, and checks for a fresh one
 * on start and on "Check again". The screen only changes when the status does, so e-ink
 * redraws stay rare. [onOpen] is set in GateActivity and runs when the gate state becomes
 * Open (it finishes). Without it this is the app's own screen, with Status, Schedule and
 * Allowlist tabs; it never gates itself, so Status shows home whenever the status is for today,
 * blocked or not.
 */
/** Fetch attempts in this (UI) process, for [shouldFetch]. */
private object Fetches {
    var lastAttempt: Instant? = null
    var failures = 0
}

@Composable
fun GoalsApp(store: StatusStore, source: StatusSource, onOpen: (() -> Unit)? = null) {
    var cached by remember { mutableStateOf(store.load()) }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableStateOf(Tab.Status) }
    var scheduleMode by rememberSaveable { mutableStateOf(ScheduleMode.Week) }
    var weekShown by rememberSaveable { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    val fetch: (RefreshTrigger) -> Unit = { trigger ->
        val now = Instant.now()
        val state = gateState(cached, now, ZoneId.systemDefault())
        if (!checking && shouldFetch(trigger, state, cached, now, Fetches.lastAttempt, Fetches.failures)) {
            checking = true
            Fetches.lastAttempt = now
            scope.launch {
                when (val r = source.fetch()) {
                    is StatusResult.Ok -> {
                        store.save(r.status)
                        cached = r.status
                        error = null
                        Fetches.failures = 0
                    }
                    is StatusResult.Error -> {
                        error = r.message
                        Fetches.failures++
                    }
                }
                checking = false
            }
        }
    }
    val check: () -> Unit = { fetch(RefreshTrigger.USER) }
    val client = StatusSources.client.takeIf { source === it }
    val schedule = remember(client) { client?.let { ScheduleModel(it, scope, onSaved = check) } }
    // The gate over a blocked app fetches less often than opening the app itself.
    LaunchedEffect(Unit) { fetch(if (onOpen != null) RefreshTrigger.GATED_APP_OPENED else RefreshTrigger.USER) }

    // The sheet's time zone once a status has given it, else the device's.
    val zone = cached?.timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
    val state = gateState(cached, Instant.now(), ZoneId.systemDefault())
    if (state == GateState.Open && onOpen != null) LaunchedEffect(Unit) { onOpen() }

    val status = cached
    val debugUnlock: (() -> Unit)? = if (BuildConfig.DEBUG && source === SampleStatusSource) {
        { SampleStatusSource.markDone(); check() }
    } else {
        null
    }
    val gate = @Composable {
        GateScreen(
            today = if (state is GateState.Locked) state.status.today else LocalDate.now(zone).toString(),
            status = status,
            unknownReason = (state as? GateState.Unknown)?.reason,
            checking = checking,
            error = error,
            onCheckAgain = check,
            onDebugUnlock = debugUnlock,
        )
    }
    if (onOpen != null) {
        gate()
        return
    }
    AppFrame(tab, { tab = it }) {
        when (tab) {
            Tab.Status ->
                if (status != null && (state == GateState.Open || state is GateState.Locked)) {
                    HomeScreen(status, HomeCheck(checking, error, check, debugUnlock))
                } else {
                    gate()
                }
            Tab.Schedule -> {
                LaunchedEffect(Unit) { schedule?.loadOnce() }
                val picking = schedule?.picking
                val draft = schedule?.draft
                if (schedule != null && picking != null && draft != null) {
                    BackHandler { schedule.picking = null }
                    DayPicker(picking, schedule.view?.workouts.orEmpty(), draft[picking], { schedule.pick(picking, it) }, { schedule.picking = null })
                } else {
                    // The schedule has this week and next; until it loads, the status has this week.
                    val weeks = schedule?.view?.weeks?.map { it.days }?.takeIf { it.isNotEmpty() } ?: listOfNotNull(status?.week)
                    ScheduleScreen(status, scheduleMode, { scheduleMode = it }, schedule?.editor(), weeks, weekShown, { weekShown = it })
                }
            }
            Tab.Goals -> {
                LaunchedEffect(Unit) { schedule?.loadOnce() }
                GoalsScreen(
                    goals = schedule?.view?.goals,
                    message = if (schedule == null) "Add gate.url and gate.token to local.properties to see your goals." else schedule.loadError,
                    onRetry = schedule?.takeIf { it.loadError != null && !it.loading }?.let { s -> { s.load() } },
                )
            }
            Tab.Allowlist -> AllowlistScreen(status)
        }
    }
}
