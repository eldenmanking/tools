package goals.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import goals.core.GoalRule
import goals.core.Goals
import goals.core.Workout
import goals.core.goalsErrors

/** Sections of the Exercise tab offered when adding a goal; any other can be typed. */
val COMMON_SECTIONS = listOf("Push", "Pull", "Legs", "Core", "Cardio", "Free", "Stretch")

/** The goals editor's state: [draft] is the workouts so far, [saved] what it started from. */
class GoalsUi(
    val draft: List<Workout>,
    val saved: List<Workout>,
    val pendingFrom: String?,
    val saving: Boolean,
    val errors: List<String>,
    val notice: String?,
    val onOpen: (Int) -> Unit,
    val onAdd: () -> Unit,
    val onSave: () -> Unit,
)

/**
 * The Goals tab: each workout and what it needs. With [ui] the workouts can be opened, added and
 * saved; without it (no endpoint, or still loading) [goals] shows read-only, or [message] says why.
 */
@Composable
fun GoalsScreen(goals: Goals?, message: String?, onRetry: (() -> Unit)?, ui: GoalsUi? = null) {
    TabPage("Goals.") {
        val workouts = ui?.draft ?: goals?.current
        if (workouts == null) {
            InkText(message ?: "Loading…")
            if (onRetry != null) InkButton("Try again", onRetry, Modifier.fillMaxWidth(), filled = false)
            return@TabPage
        }
        InkText("A workout is done when every goal under it is met in your log. Changes start tomorrow.", size = 20.sp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            InkList(
                modifier = Modifier.weight(1f, fill = false),
                rows = workouts.mapIndexed { i, w ->
                    @Composable {
                        Column(
                            Modifier.fillMaxWidth()
                                .then(if (ui != null) Modifier.clickable(interactionSource = null, indication = null, role = Role.Button) { ui.onOpen(i) } else Modifier)
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            InkText(w.name.ifBlank { "(no name)" }, size = 24.sp, bold = true)
                            w.rules.forEach { InkText(ruleText(it), size = 20.sp) }
                        }
                    }
                },
            )
            if (ui != null) InkButton("Add workout", ui.onAdd, Modifier.fillMaxWidth(), filled = false)
        }
        if (ui == null) {
            goals?.pending?.let { InkText("Changed goals start ${dayHeader(it.from)}.", size = 20.sp, bold = true) }
            return@TabPage
        }
        val changed = ui.draft != ui.saved
        val problems = (if (changed) goalsErrors(ui.draft) else emptyList()) + ui.errors
        val line = problems.joinToString(" ").ifEmpty {
            when {
                changed -> "Tap a workout to change it."
                ui.notice != null -> ui.notice
                ui.pendingFrom != null -> "Changed goals start ${dayHeader(ui.pendingFrom)}."
                else -> "Tap a workout to change it."
            }
        }
        InkText(line, size = 20.sp, bold = problems.isNotEmpty() || ui.notice != null)
        val canSave = changed && problems.isEmpty() && !ui.saving
        InkButton(if (ui.saving) "Saving…" else "Save goals", { if (canSave) ui.onSave() }, Modifier.fillMaxWidth(), filled = canSave)
    }
}

/**
 * One workout: its name and goals. Section goals take − and + for how many exercises; × removes
 * a goal. [onAddGoal] opens the page for adding one; [onDone] goes back to the list.
 */
@Composable
fun WorkoutEditor(workout: Workout, onChange: (Workout) -> Unit, onAddGoal: () -> Unit, onDelete: () -> Unit, onDone: () -> Unit) {
    TabPage(title = null) {
        InkLabel("Workout name")
        InkField(workout.name, { onChange(workout.copy(name = it)) })
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (workout.rules.isEmpty()) InkText("No goals yet.", size = 20.sp)
            InkList(
                modifier = Modifier.weight(1f, fill = false),
                rows = workout.rules.mapIndexed { i, r ->
                    @Composable {
                        fun set(rule: GoalRule?) = onChange(workout.copy(rules = workout.rules.toMutableList().also { if (rule == null) it.removeAt(i) else it[i] = rule }))
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            InkText(if (r.type == "exercise") "${r.name} logged" else r.name, size = 22.sp, bold = true, modifier = Modifier.weight(1f))
                            if (r.type == "section") {
                                SmallButton("−") { if (r.min > 1) set(r.copy(min = r.min - 1)) }
                                InkText("${r.min.toInt()}", size = 22.sp, bold = true)
                                SmallButton("+") { set(r.copy(min = r.min + 1)) }
                            }
                            SmallButton("×") { set(null) }
                        }
                    }
                },
            )
            InkButton("Add goal", onAddGoal, Modifier.fillMaxWidth(), filled = false)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            InkButton("Delete", onDelete, Modifier.weight(1f), filled = false)
            InkButton("Done", onDone, Modifier.weight(1f))
        }
    }
}

/** Adding a goal: a common section, or a typed name as a section or a single exercise. */
@Composable
fun AddGoal(onAdd: (GoalRule) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf("") }
    TabPage("Add goal") {
        InkText("A section goal counts exercises logged in that section of your sheet.", size = 20.sp)
        Column(Modifier.weight(1f)) {
            InkList(
                modifier = Modifier.weight(1f, fill = false),
                rows = COMMON_SECTIONS.map { s -> @Composable { InkRow(s, "section", onClick = { onAdd(GoalRule("section", s, 1.0)) }, bold = true, valueBold = false) } },
            )
        }
        InkLabel("Or type a name")
        InkField(name, { name = it })
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val ok = name.isNotBlank()
            InkButton("Section", { if (ok) onAdd(GoalRule("section", name.trim(), 1.0)) }, Modifier.weight(1f), filled = false)
            InkButton("Exercise", { if (ok) onAdd(GoalRule("exercise", name.trim(), 1.0)) }, Modifier.weight(1f), filled = false)
        }
        InkButton("Cancel", onCancel, Modifier.fillMaxWidth(), filled = false)
    }
}

/** A one-line text field in a black frame. */
@Composable
fun InkField(value: String, onChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(fontSize = 24.sp, fontFamily = FontFamily.SansSerif, color = Ink.Black),
        modifier = Modifier.fillMaxWidth().border(Ink.Rule, Ink.Black).padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun SmallButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).border(Ink.ThinRule, Ink.Black)
            .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        InkText(text, size = 24.sp, bold = true)
    }
}

/** "Push: 2 or more exercises", "Figure skating logged". */
fun ruleText(r: GoalRule): String =
    if (r.type == "exercise") "${r.name} logged"
    else "${r.name}: ${if (r.min % 1.0 == 0.0) r.min.toInt() else r.min} or more exercises"
