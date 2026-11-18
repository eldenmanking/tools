package goals.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import goals.core.MAX_TOKENS
import goals.core.Status

/**
 * The gate (mock: "Gate (locked)"). [today] (yyyy-mm-dd) heads the screen. [status] is the
 * last status fetched; [unknownReason] is set when it can't vouch for today, in which case
 * the goals list is left out.
 */
@Composable
fun GateScreen(
    today: String,
    status: Status?,
    unknownReason: String?,
    checking: Boolean,
    error: String?,
    onCheckAgain: () -> Unit,
    onDebugUnlock: (() -> Unit)?,
) {
    Column(
        modifier = Modifier.fillMaxSize().background(Ink.White).systemBarsPadding()
            .verticalScroll(rememberScrollState()).padding(Ink.Gutter),
        verticalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InkLabel(dayHeader(today))
            InkText("Gym first.", size = 60.sp, bold = true)
            InkText(
                if (unknownReason == null && status != null) {
                    "Apps are locked until today's ${status.workout} workout is in your log."
                } else {
                    "Apps are locked until today's status is checked. $unknownReason"
                },
            )
        }

        if (unknownReason == null && status != null) GoalsList(status)
        if (status != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatBox("${status.streak}", "day streak", Modifier.weight(1f))
                StatBox("${status.tokens}", "freeze tokens", Modifier.weight(1f), suffix = " of $MAX_TOKENS")
            }
        }

        Spacer(Modifier.weight(1f))

        CheckButtons(status, checking, error, onCheckAgain, onDebugUnlock)
    }
}

/** Today's goals and progress, or a note when today's workout has no goals. */
@Composable
fun GoalsList(status: Status) {
    if (status.goalsProgress.isNotEmpty()) {
        InkList(
            status.goalsProgress.map { g ->
                @Composable {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        InkCheck(g.met)
                        InkText(g.name, size = 28.sp, bold = true, modifier = Modifier.weight(1f))
                        InkText(progressText(g), size = 28.sp)
                    }
                }
            },
        )
    }
    if (status.unknownWorkout) {
        InkText("\"${status.workout}\" isn't one of your workouts any more. Fix the goals, then check again.")
    }
}

/** "Check again" with when the status was last checked, and the debug unlock if given. */
@Composable
fun CheckButtons(status: Status?, checking: Boolean, error: String?, onCheckAgain: () -> Unit, onDebugUnlock: (() -> Unit)?) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (onDebugUnlock != null) {
            InkButton("I finished (debug)", onDebugUnlock, Modifier.fillMaxWidth(), filled = false)
        }
        InkButton(if (checking) "Checking…" else "Check again", onCheckAgain, Modifier.fillMaxWidth())
        val last = checkedAt(status?.serverTime, status?.timeZone)
        InkText(
            error?.let { "Couldn't check: $it" }
                ?: ((if (last != null) "Last checked $last. " else "") + "Log it in your sheet, then check again."),
            size = 20.sp,
            align = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
