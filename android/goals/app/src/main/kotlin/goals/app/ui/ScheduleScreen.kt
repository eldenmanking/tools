package goals.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import goals.core.REST
import goals.core.Status
import goals.core.defaultWeekErrors
import goals.core.isGymDay
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** The two views of the Schedule tab. */
enum class ScheduleMode(val label: String) { Week("This week"), Default("Default week") }

/**
 * The default week editor's state. [draft] is null until the schedule has loaded; [saved] is the
 * default it started from, so Save is offered only when something changed.
 */
class DefaultWeekUi(
    val draft: List<String>?,
    val saved: List<String>?,
    val workouts: List<String>,
    val minGymDays: Int,
    val loading: Boolean,
    val saving: Boolean,
    /** A load or save failure, or the server's reasons for refusing. */
    val errors: List<String>,
    /** After a save: when it starts. */
    val notice: String?,
    val onRetry: () -> Unit,
    val onPickDay: (Int) -> Unit,
    val onSave: () -> Unit,
)

/** The Schedule tab: this week's plan (read-only), or the default week editor. */
@Composable
fun ScheduleScreen(status: Status?, mode: ScheduleMode, onMode: (ScheduleMode) -> Unit, editor: DefaultWeekUi?) {
    TabPage(title = null) {
        InkSegments(ScheduleMode.entries.map { it.label }, mode.ordinal, { onMode(ScheduleMode.entries[it]) })
        when (mode) {
            ScheduleMode.Week -> ThisWeek(status)
            ScheduleMode.Default -> if (editor == null) {
                InkText("Add gate.url and gate.token to local.properties to edit the schedule.")
            } else {
                DefaultWeek(editor)
            }
        }
    }
}

@Composable
private fun ColumnScope.ThisWeek(status: Status?) {
    if (status == null || status.week.all { it.source == null }) {
        InkText("Nothing is planned yet. Set a default week to start.")
        return
    }
    InkList(
        modifier = Modifier.weight(1f, fill = false),
        rows = status.week.map { d ->
            @Composable { InkRow(dayHeader(d.date), d.workout, bold = d.date == status.today, valueBold = isGymDay(d.workout)) }
        },
    )
    InkText("Days up to today are locked. Editing a single week comes in a later step.", size = 20.sp)
}

@Composable
private fun ColumnScope.DefaultWeek(ui: DefaultWeekUi) {
    val draft = ui.draft
    if (draft == null) {
        InkText(if (ui.loading) "Loading…" else ui.errors.joinToString(" "))
        if (!ui.loading) InkButton("Try again", ui.onRetry, Modifier.fillMaxWidth(), filled = false)
        return
    }
    // The list and its note take the space left over; Save stays at the bottom.
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        InkList(
            modifier = Modifier.weight(1f, fill = false),
            rows = draft.mapIndexed { i, w ->
                @Composable { InkRow(weekdayName(i), w, onClick = { ui.onPickDay(i) }, valueBold = isGymDay(w)) }
            },
        )
        InkText("Tap a day to change it. Applies from tomorrow to weeks you haven't saved.", size = 20.sp)
    }
    val problems = defaultWeekErrors(draft, ui.workouts, ui.minGymDays)
    val changed = draft != ui.saved
    // One line: what's wrong, else the last save, else the gym day count.
    val gym = draft.count { isGymDay(it) }
    InkText(
        ((if (changed) problems else emptyList()) + ui.errors).joinToString(" ").ifEmpty {
            ui.notice?.takeIf { !changed } ?: "$gym gym ${if (gym == 1) "day" else "days"} a week."
        },
        size = 20.sp,
        bold = true,
    )
    val canSave = changed && problems.isEmpty() && !ui.saving
    InkButton(
        if (ui.saving) "Saving…" else "Save default week",
        { if (canSave) ui.onSave() },
        Modifier.fillMaxWidth(),
        filled = canSave,
    )
}

/** Full-page choice of a workout for one day: Rest, then [workouts]; the [current] one is checked. */
@Composable
fun DayPicker(day: Int, workouts: List<String>, current: String, onPick: (String) -> Unit, onCancel: () -> Unit) {
    TabPage(title = weekdayName(day)) {
        InkText("Default week", size = 20.sp)
        val options = listOf(REST) + workouts
        Column(Modifier.weight(1f)) {
            InkList(
                modifier = Modifier.weight(1f, fill = false),
                rows = options.map { w ->
                    @Composable { InkRow(w, if (w.equals(current, ignoreCase = true)) "✓" else "", onClick = { onPick(w) }, bold = isGymDay(w)) }
                },
            )
        }
        InkButton("Cancel", onCancel, Modifier.fillMaxWidth(), filled = false)
    }
}

/** 0 = Sunday ... 6 = Saturday -> "Sunday". */
fun weekdayName(i: Int): String = DayOfWeek.SUNDAY.plus(i.toLong()).getDisplayName(TextStyle.FULL, Locale.US)

/** A tab's page: an optional title, then [content], with the usual gutter and spacing. */
@Composable
fun TabPage(title: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(Ink.White).padding(Ink.Gutter),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (title != null) InkText(title, size = 44.sp, bold = true)
        content()
    }
}
