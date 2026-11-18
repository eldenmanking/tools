package goals.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import goals.app.data.GateClient
import goals.app.ui.DefaultWeekUi
import goals.app.ui.dayHeader
import goals.core.SaveResult
import goals.core.ScheduleResult
import goals.core.ScheduleView
import goals.core.editableDefault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The schedule (week plan, default week, goals) fetched from [client], and the default week being
 * edited. Loaded when the Schedule or Goals tab first shows; [onSaved] runs after a save so the
 * status (and its week strip) can be refreshed.
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
    /** The day (0 = Sunday) whose workout is being picked, if any. */
    var picking by mutableStateOf<Int?>(null)
    private var saving by mutableStateOf(false)
    private var saveErrors by mutableStateOf(emptyList<String>())
    private var notice by mutableStateOf<String?>(null)

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
                    loadError = null
                }
                is ScheduleResult.Error -> loadError = "Couldn't load the schedule: ${r.message}"
            }
            loading = false
        }
    }

    fun pick(day: Int, workout: String) {
        draft = draft?.toMutableList()?.also { it[day] = workout }
        saveErrors = emptyList()
        picking = null
    }

    private fun save() {
        val days = draft ?: return
        saving = true
        saveErrors = emptyList()
        scope.launch {
            when (val r = client.saveDefault(days)) {
                is SaveResult.Saved -> {
                    notice = "Saved. It starts ${r.effectiveFrom?.let(::dayHeader) ?: "tomorrow"}."
                    saving = false
                    load()
                    onSaved()
                }
                is SaveResult.Refused -> saveErrors = r.errors
                is SaveResult.Error -> saveErrors = listOf("Couldn't save: ${r.message}")
            }
            saving = false
        }
    }

    fun editor(): DefaultWeekUi {
        val v = view
        return DefaultWeekUi(
            draft = draft,
            saved = v?.let(::editableDefault),
            workouts = v?.workouts.orEmpty(),
            minGymDays = v?.minGymDays ?: 4,
            loading = loading,
            saving = saving,
            errors = if (draft == null) listOfNotNull(loadError) else saveErrors,
            notice = notice,
            onRetry = ::load,
            onPickDay = { picking = it },
            onSave = ::save,
        )
    }
}
