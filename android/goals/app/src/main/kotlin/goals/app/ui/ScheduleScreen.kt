package goals.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.style.TextAlign
import goals.core.WeekDay
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import goals.core.joinWorkouts
import goals.core.workoutParts
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

/**
 * The Schedule tab: a week's plan (read-only), or the default week editor. [weeks] are the weeks
 * that can be shown (this week, then next); [week] is the one showing.
 */
@Composable
fun ScheduleScreen(
    status: Status?,
    mode: ScheduleMode,
    onMode: (ScheduleMode) -> Unit,
    editor: DefaultWeekUi?,
    weeks: List<List<WeekDay>> = listOfNotNull(status?.week),
    week: Int = 0,
    onWeek: (Int) -> Unit = {},
) {
    TabPage(title = null) {
        InkSegments(ScheduleMode.entries.map { it.label }, mode.ordinal, { onMode(ScheduleMode.entries[it]) })
        when (mode) {
            ScheduleMode.Week -> ThisWeek(status?.today, weeks, week.coerceIn(0, maxOf(0, weeks.size - 1)), onWeek)
            ScheduleMode.Default -> if (editor == null) {
                InkText("Add gate.url and gate.token to local.properties to edit the schedule.")
            } else {
                DefaultWeek(editor)
            }
        }
    }
}

@Composable
private fun ColumnScope.ThisWeek(today: String?, weeks: List<List<WeekDay>>, index: Int, onWeek: (Int) -> Unit) {
    val days = weeks.getOrNull(index).orEmpty()
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (days.all { it.source == null }) {
            InkText("Nothing is planned for this week. A saved default week fills it from the day after you save.")
        } else {
            InkList(
                modifier = Modifier.weight(1f, fill = false),
                rows = days.map { d ->
                    @Composable { InkRow(dayHeader(d.date), d.workout, bold = d.date == today, valueBold = isGymDay(d.workout)) }
                },
            )
            InkText("Days up to today are locked. A week you haven't saved follows the default week.", size = 20.sp)
        }
    }
    if (weeks.size > 1) WeekArrows(index, weeks.size, weekLabel(index, days), onWeek)
}

/** ◀ label ▶ for stepping through [count] weeks; an arrow with nowhere to go is left blank. */
@Composable
private fun WeekArrows(index: Int, count: Int, label: String, onWeek: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Arrow("◀", index > 0) { onWeek(index - 1) }
        InkText(label, size = 22.sp, bold = true, align = TextAlign.Center, modifier = Modifier.weight(1f))
        Arrow("▶", index < count - 1) { onWeek(index + 1) }
    }
}

@Composable
private fun Arrow(text: String, enabled: Boolean, onClick: () -> Unit) {
    val m = Modifier.size(64.dp)
    Box(
        if (enabled) m.border(Ink.Rule, Ink.Black).clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onClick) else m,
        contentAlignment = Alignment.Center,
    ) {
        if (enabled) InkText(text, size = 26.sp, bold = true)
    }
}

/** "This week", "Next week", or the week's dates. */
private fun weekLabel(index: Int, days: List<WeekDay>): String = when (index) {
    0 -> "This week"
    1 -> "Next week"
    else -> days.firstOrNull()?.let { "Week of ${dayHeader(it.date)}" } ?: ""
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

/**
 * Full-page choice of one day's plan: Rest, or one or more of [workouts] (checked boxes, done when
 * every one is met). [current] is the plan so far; Done hands back the new one.
 */
@Composable
fun DayPicker(day: Int, workouts: List<String>, current: String, onPick: (String) -> Unit, onCancel: () -> Unit) {
    var chosen by remember(day, current) {
        mutableStateOf(workoutParts(current).filter { isGymDay(it) }.map { p -> workouts.firstOrNull { it.equals(p, true) } ?: p })
    }
    TabPage(title = weekdayName(day)) {
        InkText("Pick one or more workouts. All of them count that day.", size = 20.sp)
        Column(Modifier.weight(1f)) {
            InkList(
                modifier = Modifier.weight(1f, fill = false),
                rows = listOf<@Composable () -> Unit>({ PickRow(REST, chosen.isEmpty(), bold = false) { chosen = emptyList() } }) +
                    workouts.map { w ->
                        @Composable {
                            val on = chosen.any { it.equals(w, true) }
                            // Keep the order of [workouts], so the same days read the same.
                            PickRow(w, on, bold = true) { chosen = workouts.filter { o -> if (o == w) !on else chosen.any { it.equals(o, true) } } }
                        }
                    },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            InkButton("Cancel", onCancel, Modifier.weight(1f), filled = false)
            InkButton("Done", { onPick(joinWorkouts(chosen)) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun PickRow(label: String, checked: Boolean, bold: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(interactionSource = null, indication = null, role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InkCheck(checked, size = 28.dp)
        InkText(label, size = 22.sp, bold = bold)
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
