package goals.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
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
    onSkip: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize().background(Ink.White).systemBarsPadding().padding(Ink.Gutter),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InkLabel(dayHeader(today))
            InkText("Gym first.", size = 44.sp, bold = true)
            InkText(
                if (unknownReason == null && status != null) {
                    "Apps are locked until today's ${status.workout} workout is in your log."
                } else {
                    "Apps are locked until today's status is checked. $unknownReason"
                },
            )
        }

        // The goals take the space left over and scroll inside their box when there are many.
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            if (unknownReason == null && status != null) GoalsList(status, Modifier.weight(1f, fill = false))
            if (status != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatBox("${status.streak}", "day streak", Modifier.weight(1f))
                    StatBox("${status.tokens}", tokenLabel(status), Modifier.weight(1f), suffix = " of $MAX_TOKENS")
                }
            }
        }

        CheckButtons(status, checking, error, onCheckAgain, onDebugUnlock, onSkip = onSkip)
    }
}

/** Today's goals and progress, or a note when today's workout has no goals. */
@Composable
fun GoalsList(status: Status, modifier: Modifier = Modifier) {
    if (status.goalsProgress.isNotEmpty()) {
        InkList(
            modifier = modifier,
            rows =
            status.goalsProgress.map { g ->
                @Composable {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        InkCheck(g.met, size = 28.dp)
                        InkText(g.name, size = 24.sp, bold = true, modifier = Modifier.weight(1f))
                        InkText(progressText(g), size = 24.sp)
                    }
                }
            },
        )
    }
    if (status.unknownWorkout) {
        InkText("\"${status.workout}\" isn't one of your workouts any more. Fix the goals, then check again.")
    }
}

/**
 * Skips today with a freeze token. The first tap only asks; the second skips, so a stray tap on
 * e-ink can't spend a token.
 */
@Composable
fun SkipButton(status: Status, onSkip: () -> Unit) {
    var asking by remember(status.today) { mutableStateOf(false) }
    val token = if (status.todayTokens > 0) "today's extra token" else "a freeze token"
    InkButton(
        if (asking) "Tap again to spend $token" else "Skip today with $token",
        { if (asking) { asking = false; onSkip() } else asking = true },
        Modifier.fillMaxWidth(),
        filled = false,
        height = 56.dp,
        size = 20.sp,
    )
}

/**
 * "Check again" and the debug unlock if given. Below them: an error, or (with [caption]) when the
 * status was last checked and what to do.
 */
@Composable
fun CheckButtons(
    status: Status?,
    checking: Boolean,
    error: String?,
    onCheckAgain: () -> Unit,
    onDebugUnlock: (() -> Unit)?,
    caption: Boolean = true,
    onSkip: (() -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (onSkip != null && status?.canSkip == true) SkipButton(status, onSkip)
        if (onDebugUnlock != null) {
            InkButton("I finished (debug)", onDebugUnlock, Modifier.fillMaxWidth(), filled = false)
        }
        InkButton(if (checking) "Checking…" else "Check again", onCheckAgain, Modifier.fillMaxWidth())
        val last = checkedAt(status?.serverTime, status?.timeZone)
        val text = error?.let { "Couldn't check: $it" }
            ?: if (caption) (if (last != null) "Last checked $last. " else "") + "Log it in your sheet, then check again." else null
        if (text != null) InkText(
            text,
            size = 20.sp,
            align = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The earned tokens' caption: "freeze tokens" ("tokens" when [short]), or with tokens granted for
 * today "tokens +1 today" ("+1 today" when [short]).
 */
fun tokenLabel(status: Status, short: Boolean = false): String = when {
    status.todayTokens > 0 -> (if (short) "" else "tokens ") + "+${status.todayTokens} today"
    short -> "tokens"
    else -> "freeze tokens"
}
