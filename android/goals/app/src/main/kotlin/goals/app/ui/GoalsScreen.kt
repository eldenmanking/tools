package goals.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import goals.core.GoalRule
import goals.core.Goals

/**
 * The Goals tab: what each workout needs, read-only until the goals editor comes. [goals] is null
 * until the schedule has loaded (or when no endpoint is set); [message] says why.
 */
@Composable
fun GoalsScreen(goals: Goals?, message: String?, onRetry: (() -> Unit)?) {
    TabPage("Goals.") {
        if (goals == null) {
            InkText(message ?: "Loading…")
            if (onRetry != null) InkButton("Try again", onRetry, Modifier.fillMaxWidth(), filled = false)
            return@TabPage
        }
        InkText("A workout is done when every goal under it is met in your log.", size = 20.sp)
        InkList(
            modifier = Modifier.weight(1f, fill = false),
            rows = goals.current.map { w ->
                @Composable {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        InkText(w.name, size = 24.sp, bold = true)
                        w.rules.forEach { InkText(ruleText(it), size = 20.sp) }
                    }
                }
            },
        )
        goals.pending?.let { InkText("Changed goals start ${dayHeader(it.from)}.", size = 20.sp, bold = true) }
        InkText("Editing goals comes in a later step.", size = 20.sp)
    }
}

/** "Push: 2 or more exercises", "Figure skating logged". */
fun ruleText(r: GoalRule): String =
    if (r.type == "exercise") "${r.name} logged"
    else "${r.name}: ${if (r.min % 1.0 == 0.0) r.min.toInt() else r.min} or more exercises"
