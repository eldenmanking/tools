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
import goals.app.data.UnlockStore
import goals.app.ui.CodeScreen
import goals.app.ui.GateExtras
import goals.app.ui.WindowEditor
import goals.core.CodeResult
import goals.core.Window
import goals.core.allowanceReady
import goals.core.clockText
import goals.core.minuteOfDay
import goals.core.planParts
import goals.core.tryCode
import goals.core.windowLocked
import goals.core.windowsOn
import kotlinx.coroutines.delay
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

/** How often the screen re-judges the time: windows opening and closing, the allowance running out. */
private const val TICK_MS = 15_000L

@Composable
fun GoalsApp(store: StatusStore, unlockStore: UnlockStore, source: StatusSource, onOpen: (() -> Unit)? = null) {
    var cached by remember { mutableStateOf(store.load()) }
    var unlocks by remember { mutableStateOf(unlockStore.load()) }
    var now by remember { mutableStateOf(Instant.now()) }
    var enteringCode by remember { mutableStateOf(false) }
    var codeMessage by remember { mutableStateOf<String?>(null) }
    var editingWindow by rememberSaveable { mutableStateOf(false) }
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
    LaunchedEffect(Unit) {
        while (true) {
            delay(TICK_MS)
            now = Instant.now()
            unlocks = unlockStore.load()
        }
    }

    // The sheet's time zone once a status has given it, else the device's.
    val zone = cached?.timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
    val minute = minuteOfDay(now, zone)
    // [state] decides whether the gate goes away; the Status tab shows the status itself ([plain]).
    val state = gateState(cached, now, ZoneId.systemDefault(), unlocks)
    val plain = gateState(cached, now, ZoneId.systemDefault())
    if (state == GateState.Open && onOpen != null) LaunchedEffect(Unit) { onOpen() }

    fun keep(u: goals.core.Unlocks) {
        unlocks = u
        unlockStore.save(u)
    }
    val extras = GateExtras(
        allowanceAt = allowanceReady(unlocks, now)?.let { clockText(minuteOfDay(it, zone)) },
        onAllowance = {
            val t = Instant.now()
            if (allowanceReady(unlocks, t) == null) { now = t; keep(unlocks.copy(allowanceStart = t.toEpochMilli())) }
        },
        onCode = { codeMessage = null; enteringCode = true },
    )
    if (enteringCode) {
        BackHandler { enteringCode = false }
        CodeScreen(
            codeMessage,
            onSubmit = { code ->
                val t = Instant.now()
                val token = BuildConfig.GATE_TOKEN
                if (token.isBlank()) {
                    codeMessage = "This build has no gate.token, so it can't check codes."
                } else {
                    val (r, u) = tryCode(unlocks, code, token, t.atZone(zone).toLocalDate().toString(), t)
                    keep(u)
                    now = t
                    codeMessage = when (r) {
                        CodeResult.Opened -> { enteringCode = false; null }
                        is CodeResult.Wrong -> "Wrong code. ${r.triesLeft} ${if (r.triesLeft == 1) "try" else "tries"} left."
                        is CodeResult.Wait -> "Too many wrong codes. Try again at ${clockText(minuteOfDay(r.until, zone))}."
                    }
                }
            },
            onCancel = { enteringCode = false },
        )
        return
    }

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
            session = (state as? GateState.Locked)?.session,
            extras = extras,
        )
    }
    if (onOpen != null) {
        gate()
        return
    }
    AppFrame(tab, { tab = it }) {
        when (tab) {
            Tab.Status ->
                if (status != null && (plain == GateState.Open || plain is GateState.Locked)) {
                    HomeScreen(status, HomeCheck(checking, error, check, debugUnlock, skip), minute)
                } else {
                    gate()
                }
            Tab.Schedule -> {
                LaunchedEffect(Unit) { schedule?.loadOnce() }
                val picking = schedule?.picking
                if (schedule != null && picking != null) {
                    BackHandler { schedule.picking = null }
                    val v = schedule.view
                    // The date picked (the default week: from tomorrow) gives the workouts' windows.
                    val date = when (picking) {
                        is PickTarget.Week -> schedule.savedWeek(picking.week).getOrNull(picking.day)?.date
                        is PickTarget.Default -> v?.today?.let { LocalDate.parse(it).plusDays(1).toString() }
                    }
                    val windows: (String) -> Window? = if (v != null && date != null) windowsOn(v, date) else ({ null })
                    // Saved workouts that start within the hour stay as they are.
                    val locked = if (picking is PickTarget.Week && date != null) {
                        planParts(schedule.savedWeek(picking.week)[picking.day].workout)
                            .filter { p -> windowLocked(LocalDate.parse(date), (p.window ?: windows(p.name) ?: Window.ALL_DAY).start, Instant.now(), zone) }
                            .map { it.name.lowercase() }.toSet()
                    } else {
                        emptySet()
                    }
                    DayPicker(picking.day, v?.workouts.orEmpty(), schedule.picked(picking), { schedule.pick(picking, it) }, { schedule.picking = null },
                        windowOf = windows, locked = locked)
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
                    val v = schedule?.view
                    ScheduleScreen(status, scheduleMode, { scheduleMode = it }, schedule?.editor(), weeks, shown, { weekShown = it }, weekEdit,
                        windowOf = { date, name -> v?.let { windowsOn(it, date)(name) } })
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
                    if (editingWindow) {
                        BackHandler { editingWindow = false }
                        WindowEditor(
                            "${draft[open].name.ifBlank { "Workout" }} locks apps",
                            draft[open].gated,
                            { w -> update(draft[open].copy(window = w.takeIf { !it.allDay }?.format())) },
                            { editingWindow = false },
                        )
                    } else if (addingGoal) {
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
                            onWindow = { editingWindow = true },
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
