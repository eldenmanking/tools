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
import androidx.compose.ui.platform.LocalContext
import goals.app.data.AppEntry
import goals.app.data.launchableApps
import goals.app.ui.AddGoal
import goals.app.ui.AllowlistUi
import goals.app.ui.GoalsUi
import goals.app.ui.WeekEditUi
import goals.app.ui.WorkoutEditor
import goals.core.Workout
import goals.core.editableAllowlist
import goals.core.editableGoals
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
import goals.core.SaveResult
import goals.core.ScheduleJson
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
    var addingGoal by rememberSaveable { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // [force] skips the refresh policy, after a change that the status must show at once.
    fun fetch(trigger: RefreshTrigger, force: Boolean = false) {
        val now = Instant.now()
        val state = gateState(cached, now, ZoneId.systemDefault())
        if (!checking && (force || shouldFetch(trigger, state, cached, now, Fetches.lastAttempt, Fetches.failures))) {
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
    // Spends a freeze token on today, then fetches the status that shows it open.
    val skip: (() -> Unit)? = client?.let { c ->
        {
            if (!checking) {
                checking = true
                scope.launch {
                    val r = c.save(ScheduleJson.skipToday())
                    checking = false
                    when (r) {
                        is SaveResult.Saved -> fetch(RefreshTrigger.USER, force = true)
                        is SaveResult.Refused -> error = r.errors.joinToString(" ")
                        is SaveResult.Error -> error = "couldn't skip: ${r.message}"
                    }
                }
            }
        }
    }
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
            onSkip = skip,
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
                    HomeScreen(status, HomeCheck(checking, error, check, debugUnlock, skip))
                } else {
                    gate()
                }
            Tab.Schedule -> {
                LaunchedEffect(Unit) { schedule?.loadOnce() }
                val picking = schedule?.picking
                if (schedule != null && picking != null) {
                    BackHandler { schedule.picking = null }
                    DayPicker(picking.day, schedule.view?.workouts.orEmpty(), schedule.picked(picking), { schedule.pick(picking, it) }, { schedule.picking = null })
                } else {
                    // The schedule has this week and next; until it loads, the status has this week.
                    val weeks = schedule?.view?.weeks?.map { it.days }?.takeIf { it.isNotEmpty() } ?: listOfNotNull(status?.week)
                    val shown = weekShown.coerceIn(0, maxOf(0, weeks.size - 1))
                    val weekEdit = schedule?.view?.let { v ->
                        WeekEditUi(
                            draft = schedule.weekDrafts[shown],
                            workouts = v.workouts,
                            saving = schedule.weekSaver.saving,
                            errors = schedule.weekSaver.errors,
                            notice = schedule.weekSaver.notice,
                            onPickDay = { schedule.picking = PickTarget.Week(shown, it) },
                            onSave = { schedule.saveWeek(shown) },
                        )
                    }
                    ScheduleScreen(status, scheduleMode, { scheduleMode = it }, schedule?.editor(), weeks, shown, { weekShown = it }, weekEdit)
                }
            }
            Tab.Goals -> {
                LaunchedEffect(Unit) { schedule?.loadOnce() }
                val draft = schedule?.goalsDraft
                val open = schedule?.editingWorkout
                if (schedule != null && draft != null && open != null && open in draft.indices) {
                    fun update(w: Workout?) {
                        schedule.goalsDraft = draft.toMutableList().also { if (w == null) it.removeAt(open) else it[open] = w }
                        schedule.goalsSaver.errors = emptyList()
                    }
                    if (addingGoal) {
                        BackHandler { addingGoal = false }
                        AddGoal({ r -> update(draft[open].copy(rules = draft[open].rules + r)); addingGoal = false }, { addingGoal = false })
                    } else {
                        BackHandler { schedule.editingWorkout = null }
                        WorkoutEditor(
                            draft[open],
                            ::update,
                            onAddGoal = { addingGoal = true },
                            onDelete = { update(null); schedule.editingWorkout = null },
                            onDone = { schedule.editingWorkout = null },
                        )
                    }
                } else {
                    val v = schedule?.view
                    GoalsScreen(
                        goals = v?.goals,
                        message = if (schedule == null) "Add gate.url and gate.token to local.properties to see your goals." else schedule.loadError,
                        onRetry = schedule?.takeIf { it.loadError != null && !it.loading }?.let { s -> { s.load() } },
                        ui = if (v != null && draft != null) {
                            GoalsUi(
                                draft = draft,
                                saved = editableGoals(v),
                                inEffect = v.goals.current,
                                pendingFrom = v.goals.pending?.from,
                                saving = schedule.goalsSaver.saving,
                                errors = schedule.goalsSaver.errors,
                                notice = schedule.goalsSaver.notice,
                                onOpen = { schedule.editingWorkout = it },
                                onAdd = {
                                    schedule.goalsDraft = draft + Workout("")
                                    schedule.editingWorkout = draft.size
                                },
                                onSave = schedule::saveGoals,
                            )
                        } else {
                            null
                        },
                    )
                }
            }
            Tab.Allowlist -> {
                LaunchedEffect(Unit) {
                    schedule?.loadOnce()
                    if (apps == null) apps = launchableApps(context)
                }
                val v = schedule?.view
                val draft = schedule?.allowDraft
                AllowlistScreen(
                    status,
                    if (v != null && draft != null) {
                        AllowlistUi(
                            apps = apps,
                            draft = draft,
                            saved = editableAllowlist(v),
                            inEffect = v.allowlist.current,
                            pending = v.allowlist.pending,
                            saving = schedule.allowSaver.saving,
                            errors = schedule.allowSaver.errors,
                            notice = schedule.allowSaver.notice,
                            onToggle = { p ->
                                schedule.allowDraft = if (p in draft) draft - p else draft + p
                                schedule.allowSaver.errors = emptyList()
                            },
                            onSave = schedule::saveAllowlist,
                        )
                    } else {
                        null
                    },
                    loading = schedule != null && schedule.view == null && schedule.loadError == null,
                    loadError = schedule?.loadError,
                )
            }
        }
    }
}
