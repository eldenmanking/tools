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
import goals.core.PlanPart
import goals.core.Window
import goals.core.joinParts
import goals.core.planLabel
import goals.core.planParts
import goals.core.windowText
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
    /** The default week in effect today (7 names), to star what changes tomorrow. */
    val inEffect: List<String>,
    val workouts: List<String>,
    /** Each workout's window from tomorrow (null: all day). */
    val windowOf: (String) -> Window?,
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
    weekEdit: WeekEditUi? = null,
    /** A workout's window on a date (yyyy-mm-dd); null: all day or not known. */
    windowOf: (String, String) -> Window? = { _, _ -> null },
) {
    TabPage(title = null) {
        InkSegments(ScheduleMode.entries.map { it.label }, mode.ordinal, { onMode(ScheduleMode.entries[it]) })
        when (mode) {
            ScheduleMode.Week -> ThisWeek(status?.today, weeks, week.coerceIn(0, maxOf(0, weeks.size - 1)), onWeek, weekEdit, windowOf)
            ScheduleMode.Default -> if (editor == null) {
                InkText("Add gate.url and gate.token to local.properties to edit the schedule.")
            } else {
                DefaultWeek(editor)
            }
        }
    }
}

/**
 * Editing the week on show: [draft] is its plan so far (null until a day is changed), saved on
 * the server with [onSave]. Days before today are over; from today on, a workout locks an hour
 * before its window (the day picker shows which).
 */
class WeekEditUi(
    val draft: List<String>?,
    val workouts: List<String>,
    val saving: Boolean,
    val errors: List<String>,
    val notice: String?,
    val onPickDay: (Int) -> Unit,
    val onSave: () -> Unit,
)

@Composable
private fun ColumnScope.ThisWeek(
    today: String?,
    weeks: List<List<WeekDay>>,
    index: Int,
    onWeek: (Int) -> Unit,
    edit: WeekEditUi?,
    windowOf: (String, String) -> Window?,
) {
    val days = weeks.getOrNull(index).orEmpty()
    val draft = edit?.draft?.takeIf { it.size == days.size }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (days.isEmpty()) InkText("Loading…")
        else InkList(
            modifier = Modifier.weight(1f, fill = false),
            rows = days.mapIndexed { i, d ->
                val w = draft?.get(i) ?: d.workout
                val tap = if (edit != null && !d.locked) ({ edit.onPickDay(i) }) else null
                // Days that are over are gray; today is bold.
                val past = today != null && d.date < today
                @Composable {
                    InkRow(dayHeader(d.date), starred(planLabel(w) { windowOf(d.date, it) }, w != d.workout), onClick = tap, bold = d.date == today,
                        valueBold = isGymDay(w), color = if (past) Ink.Gray else Ink.Black)
                }
            },
        )
        if (draft != null && draft != days.map { it.workout }) InkNote("* Not saved yet")
        else if (draft == null && edit?.notice != null) InkNote(edit.notice, bold = true)
    }
    if (edit != null && draft != null) {
        // The same checks as the server: known workouts, no minimum of gym days for a single week.
        // A day that has started keeps its workouts even if they were removed since.
        val known = edit.workouts + days.filter { it.locked || it.date == today }.flatMap { workoutParts(it.workout) }
        val problems = defaultWeekErrors(draft, known, minGymDays = 0) + edit.errors
        if (problems.isNotEmpty()) InkError(problems.joinToString(" "))
        val canSave = problems.isEmpty() && !edit.saving
        InkButton(if (edit.saving) "Saving…" else "Save this week", { if (canSave) edit.onSave() }, Modifier.fillMaxWidth(), filled = canSave)
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
        if (ui.loading) InkText("Loading…") else InkError(ui.errors.joinToString(" "))
        if (!ui.loading) InkButton("Try again", ui.onRetry, Modifier.fillMaxWidth(), filled = false)
        return
    }
    // A star marks a day that differs from the default in effect today: it starts tomorrow.
    val starred = draft.indices.filter { draft[it] != ui.inEffect.getOrNull(it) }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        InkList(
            modifier = Modifier.weight(1f, fill = false),
            rows = draft.mapIndexed { i, w ->
                @Composable { InkRow(weekdayName(i), starred(planLabel(w, ui.windowOf), i in starred), onClick = { ui.onPickDay(i) }, valueBold = isGymDay(w)) }
            },
        )
        if (starred.isNotEmpty()) InkNote("* Changes start tomorrow")
    }
    val changed = draft != ui.saved
    val problems = (if (changed) defaultWeekErrors(draft, ui.workouts, ui.minGymDays) else emptyList()) + ui.errors
    if (problems.isNotEmpty()) InkError(problems.joinToString(" "))
    else if (ui.notice != null && !changed) InkNote(ui.notice, bold = true)
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
 * every one is met), each with its window: its workout's ([windowOf]) unless the day sets its own.
 * [current] is the plan so far; Done hands back the new one. Workouts in [locked] (lower case)
 * start within the hour and can't change.
 */
@Composable
fun DayPicker(
    day: Int,
    workouts: List<String>,
    current: String,
    onPick: (String) -> Unit,
    onCancel: () -> Unit,
    windowOf: (String) -> Window? = { null },
    locked: Set<String> = emptySet(),
) {
    var chosen by remember(day, current) {
        mutableStateOf(planParts(current).map { p -> p.copy(name = workouts.firstOrNull { it.equals(p.name, true) } ?: p.name) })
    }
    var timing by remember(day, current) { mutableStateOf<String?>(null) }
    fun isLocked(name: String) = name.lowercase() in locked
    timing?.let { name ->
        val part = chosen.firstOrNull { it.name == name }
        if (part != null) {
            val usual = windowOf(name) ?: Window.ALL_DAY
            fun set(w: Window?) { chosen = chosen.map { if (it.name == name) it.copy(window = w?.takeIf { it != usual }) else it } }
            WindowEditor("$name on ${weekdayName(day)}", part.window ?: usual, { set(it) }, { timing = null }, onDefault = { set(null) })
            return
        }
    }
    TabPage(title = weekdayName(day)) {
        Column(Modifier.weight(1f)) {
            InkList(
                modifier = Modifier.weight(1f, fill = false),
                rows = listOf<@Composable () -> Unit>({
                    CheckRow(REST, chosen.isEmpty(), bold = false) { if (locked.isEmpty()) chosen = emptyList() }
                }) + workouts.map { w ->
                    @Composable {
                        val part = chosen.firstOrNull { it.name.equals(w, true) }
                        val fixed = isLocked(w)
                        Column {
                            // Keep the order of [workouts], so the same days read the same.
                            CheckRow(if (fixed) "$w (locked)" else w, part != null, bold = true, color = if (fixed) Ink.Gray else Ink.Black) {
                                if (!fixed) {
                                    chosen = workouts.mapNotNull { o ->
                                        val had = chosen.firstOrNull { it.name.equals(o, true) }
                                        when {
                                            o != w -> had
                                            had != null -> null
                                            else -> PlanPart(o)
                                        }
                                    } + chosen.filter { c -> workouts.none { it.equals(c.name, true) } }
                                }
                            }
                            if (part != null) {
                                val win = part.window ?: windowOf(w) ?: Window.ALL_DAY
                                // Under the check box's label: when it locks apps; tapping changes it for this day.
                                InkText(
                                    "Locks apps ${windowText(win)}" + (if (part.window != null) " this day" else "") + (if (fixed) "" else "  ›"),
                                    size = 20.sp,
                                    color = if (fixed) Ink.Gray else Ink.Black,
                                    modifier = Modifier.fillMaxWidth()
                                        .then(if (fixed) Modifier else Modifier.clickable(interactionSource = null, indication = null, role = Role.Button) { timing = w })
                                        .padding(start = 64.dp, end = 20.dp, bottom = 12.dp),
                                )
                            }
                        }
                    }
                },
            )
        }
        if (locked.isNotEmpty()) InkNote("A workout can change until an hour before it locks apps.")
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            InkButton("Cancel", onCancel, Modifier.weight(1f), filled = false)
            InkButton("Done", { onPick(joinParts(chosen)) }, Modifier.weight(1f))
        }
    }
}

/** A list row with a check box in front of [label]; tapping it toggles. */
@Composable
fun CheckRow(label: String, checked: Boolean, bold: Boolean, color: androidx.compose.ui.graphics.Color = Ink.Black, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(interactionSource = null, indication = null, role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InkCheck(checked, size = 28.dp)
        InkText(label, size = 22.sp, bold = bold, color = color)
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
