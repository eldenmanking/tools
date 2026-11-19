package goals.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import goals.app.data.GateClient
import goals.app.ui.DefaultWeekUi
import goals.app.ui.dayHeader
import goals.core.SaveResult
import goals.core.ScheduleJson
import goals.core.ScheduleResult
import goals.core.ScheduleView
import goals.core.WeekDay
import goals.core.Workout
import goals.core.editableAllowlist
import goals.core.editableDefault
import goals.core.editableGoals
import goals.core.REST
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Which day's plan the day picker is choosing. */
sealed interface PickTarget {
    val day: Int
    data class Default(override val day: Int) : PickTarget
    data class Week(val week: Int, override val day: Int) : PickTarget
}

/** One editor's save: in progress, the server's refusal or the failure, and what the last save did. */
class Saver(private val scope: CoroutineScope) {
    var saving by mutableStateOf(false)
        private set
    var errors by mutableStateOf(emptyList<String>())
    var notice by mutableStateOf<String?>(null)

    /** Sends [body]; on success shows [saved] (given when it starts) and runs [then]. */
    fun run(client: GateClient, body: String, saved: (String) -> String, then: () -> Unit) {
        saving = true
        errors = emptyList()
        scope.launch {
            when (val r = client.save(body)) {
                is SaveResult.Saved -> {
                    notice = saved(r.effectiveFrom?.let(::dayHeader) ?: "tomorrow")
                    then()
                }
                is SaveResult.Refused -> errors = r.errors
                is SaveResult.Error -> errors = listOf("Couldn't save: ${r.message}")
            }
            saving = false
        }
    }
}

/**
 * The schedule (weeks, default week, goals, allowlist) fetched from [client], and a draft for each
 * editor. Loaded when a tab that needs it first shows; [onSaved] runs after a save so the status
 * (and its week strip) can be refreshed.
 */
class ScheduleModel(private val client: GateClient, private val scope: CoroutineScope, private val onSaved: () -> Unit) {
    var view by mutableStateOf<ScheduleView?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set

    var draft by mutableStateOf<List<String>?>(null)
        private set
    /** Edited weeks, by index into [ScheduleView.weeks]: 7 names, Sunday first. */
    var weekDrafts by mutableStateOf(emptyMap<Int, List<String>>())
        private set
    var allowDraft by mutableStateOf<List<String>?>(null)
    var goalsDraft by mutableStateOf<List<Workout>?>(null)
    /** The workout open in the goals editor, by index into [goalsDraft]. */
    var editingWorkout by mutableStateOf<Int?>(null)

    var picking by mutableStateOf<PickTarget?>(null)

    val defaultSaver = Saver(scope)
    val weekSaver = Saver(scope)
    val allowSaver = Saver(scope)
    val goalsSaver = Saver(scope)

    fun loadOnce() {
        if (view == null && !loading) load()
    }

    fun load() {
        loading = true
        scope.launch {
            when (val r = client.schedule()) {
                is ScheduleResult.Ok -> {
                    view = r.view
                    draft = editableDefault(r.view)
                    weekDrafts = emptyMap()
                    allowDraft = editableAllowlist(r.view)
                    goalsDraft = editableGoals(r.view)
                    loadError = null
                }
                is ScheduleResult.Error -> loadError = "Couldn't load the schedule: ${r.message}"
            }
            loading = false
        }
    }

    /** The days of week [index] as saved on the server. */
    fun savedWeek(index: Int): List<WeekDay> = view?.weeks?.getOrNull(index)?.days.orEmpty()

    /** The plan currently being picked. */
    fun picked(t: PickTarget): String = when (t) {
        is PickTarget.Default -> draft?.getOrNull(t.day)
        is PickTarget.Week -> (weekDrafts[t.week] ?: savedWeek(t.week).map { it.workout }).getOrNull(t.day)
    } ?: "Rest"

    fun pick(t: PickTarget, workout: String) {
        when (t) {
            is PickTarget.Default -> {
                draft = draft?.toMutableList()?.also { it[t.day] = workout }
                defaultSaver.errors = emptyList()
            }
            is PickTarget.Week -> {
                val days = (weekDrafts[t.week] ?: savedWeek(t.week).map { it.workout }).toMutableList()
                if (days.size == 7) days[t.day] = workout
                weekDrafts = weekDrafts + (t.week to days)
                weekSaver.errors = emptyList()
            }
        }
        picking = null
    }

    fun saveWeek(index: Int) {
        val days = weekDrafts[index] ?: return
        val start = view?.weeks?.getOrNull(index)?.weekStart ?: return
        weekSaver.run(client, ScheduleJson.saveWeek(start, days), { "Saved." }) { load(); onSaved() }
    }

    fun saveAllowlist() {
        val packages = allowDraft ?: return
        allowSaver.run(client, ScheduleJson.saveAllowlist(packages), { "Saved." }) { load(); onSaved() }
    }

    fun saveGoals() {
        val workouts = goalsDraft ?: return
        goalsSaver.run(client, ScheduleJson.saveGoals(workouts), { "Saved." }) { load(); onSaved() }
    }

    fun editor(): DefaultWeekUi {
        val v = view
        return DefaultWeekUi(
            draft = draft,
            saved = v?.let(::editableDefault),
            inEffect = v?.defaults?.current?.days ?: List(7) { REST },
            workouts = v?.workouts.orEmpty(),
            minGymDays = v?.minGymDays ?: 4,
            loading = loading,
            saving = defaultSaver.saving,
            errors = if (draft == null) listOfNotNull(loadError) else defaultSaver.errors,
            notice = defaultSaver.notice,
            onRetry = ::load,
            onPickDay = { picking = PickTarget.Default(it) },
            onSave = {
                draft?.let { d -> defaultSaver.run(client, ScheduleJson.saveDefault(d), { "Saved." }) { load(); onSaved() } }
            },
        )
    }
}
