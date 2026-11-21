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
import goals.core.GoalProgress
import goals.core.MAX_TOKENS
import goals.core.Session
import goals.core.Status
import goals.core.clockText
import goals.core.planNames

/**
 * The gate's ways out besides doing the workout: the allowance ([allowanceAt] is when it can be
 * used again, null when it can now) and typing an unlock code.
 */
class GateExtras(val allowanceAt: String?, val onAllowance: () -> Unit, val onCode: () -> Unit)

/**
 * The gate (mock: "Gate (locked)"). [today] (yyyy-mm-dd) heads the screen. [status] is the
 * last status fetched; [unknownReason] is set when it can't vouch for today, in which case
 * the goals list is left out. [session] is the workout whose window gates now.
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
    session: Session? = null,
    extras: GateExtras? = null,
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
                    lockedText(status, session)
                } else {
                    "Apps are locked until today's status is checked. $unknownReason"
                },
            )
        }

        // The goals take the space left over and scroll inside their box when there are many.
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            if (unknownReason == null && status != null) GoalsList(status, Modifier.weight(1f, fill = false), session?.goalsProgress)
            if (status != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatBox("${status.streak}", "day streak", Modifier.weight(1f))
                    StatBox("${status.tokens}", tokenLabel(status), Modifier.weight(1f), suffix = " of $MAX_TOKENS")
                }
            }
        }

        CheckButtons(status, checking, error, onCheckAgain, onDebugUnlock, onSkip = onSkip, extras = extras)
    }
}

/** "Apps are locked until Upper is in your log, or until 10:00." */
fun lockedText(status: Status, session: Session?): String = when {
    session == null -> "Apps are locked until today's ${planNames(status.workout)} workout is in your log."
    session.window.allDay -> "Apps are locked until today's ${session.workout} workout is in your log."
    else -> "Apps are locked until ${session.workout} is in your log, or until ${clockText(session.window.end)}."
}

/** [goals] (else all of today's) and their progress, or a note when today's workout has no goals. */
@Composable
fun GoalsList(status: Status, modifier: Modifier = Modifier, goals: List<GoalProgress>? = null) {
    val shown = goals ?: status.goalsProgress
    if (shown.isNotEmpty()) {
        InkList(
            modifier = modifier,
            rows =
            shown.map { g ->
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
        InkText("\"${planNames(status.workout)}\" isn't one of your workouts any more. Fix the goals, then check again.")
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
    extras: GateExtras? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (onSkip != null && status?.canSkip == true) SkipButton(status, onSkip)
        if (extras != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val m = Modifier.weight(1f)
                if (extras.allowanceAt == null) InkButton("2 minutes", extras.onAllowance, m, filled = false, height = 56.dp, size = 20.sp)
                else InkButton("2 min at ${extras.allowanceAt}", {}, m, filled = false, height = 56.dp, size = 20.sp)
                InkButton("Enter code", extras.onCode, m, filled = false, height = 56.dp, size = 20.sp)
            }
        }
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
