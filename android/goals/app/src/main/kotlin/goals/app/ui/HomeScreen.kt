package goals.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import goals.core.DayMark
import goals.core.MAX_TOKENS
import goals.core.Status
import goals.core.WeekCell
import goals.core.weekCells

/**
 * The Status tab (mock: "Home (done for today)"). The app never gates itself, so while today is
 * blocked this shows today's goals and "Check again" too, through [check].
 */
@Composable
fun HomeScreen(status: Status, check: HomeCheck? = null) {
    val (title, body) = when {
        status.blocked -> "Gym first." to "Other apps unlock once ${status.workout} is in your log."
        !status.todayScheduled && status.week.isEmpty() -> "Nothing planned." to "Set a schedule to start your streak."
        !status.todayScheduled -> "Rest day." to "Nothing is locked today."
        else -> "Done for today." to "${status.workout} is logged. Every app is open until tomorrow."
    }
    Column(
        modifier = Modifier.fillMaxSize().background(Ink.White).padding(Ink.Gutter),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InkLabel(dayHeader(status.today))
            InkText(title, size = 44.sp, bold = true)
            InkText(body)
        }

        // The goals take the space left over and scroll inside their box when there are many.
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            if (status.blocked) GoalsList(status, Modifier.weight(1f, fill = false))

            // Left out while blocked, to leave room for the goals.
            val cells = if (status.blocked) emptyList() else weekCells(status)
            if (cells.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    InkLabel("This week")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        cells.forEach { WeekDayCell(it, Modifier.weight(1f)) }
                    }
                    InkText("Filled = done · Box = gym day · × missed · F = token used · – rest", size = 18.sp)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatBox("${status.streak}", "streak", Modifier.weight(1f))
                StatBox("${status.longest}", "longest", Modifier.weight(1f))
                StatBox("${status.tokens}", "tokens", Modifier.weight(1f), suffix = "/$MAX_TOKENS")
            }
        }

        if (status.blocked && check != null) {
            CheckButtons(status, check.checking, check.error, check.onCheckAgain, check.onDebugUnlock, caption = false)
        }
    }
}

/** "Check again" state for [HomeScreen] while today is blocked. */
class HomeCheck(
    val checking: Boolean,
    val error: String?,
    val onCheckAgain: () -> Unit,
    val onDebugUnlock: (() -> Unit)?,
)

@Composable
private fun WeekDayCell(cell: WeekCell, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InkText(weekdayLetter(cell.date), size = 20.sp, bold = true)
        val box = Modifier.fillMaxWidth().height(64.dp)
        // Today gets a heavier outline around whatever it shows.
        val outer = if (cell.isToday) Modifier.border(Ink.Rule, Ink.Black).padding(5.dp) else Modifier
        Box(outer) {
            when (cell.mark) {
                DayMark.DONE -> Box(box.background(Ink.Black), contentAlignment = Alignment.Center) { CheckMark(28.dp, Ink.White) }
                DayMark.GYM -> Box(box.border(Ink.Rule, Ink.Black))
                DayMark.MISSED -> MarkedBox(box, "×")
                DayMark.FROZEN -> MarkedBox(box, "F")
                DayMark.REST -> MarkedBox(box, "–", rule = Ink.ThinRule)
            }
        }
    }
}

@Composable
private fun MarkedBox(box: Modifier, text: String, rule: androidx.compose.ui.unit.Dp = Ink.Rule) {
    Box(box.border(rule, Ink.Black), contentAlignment = Alignment.Center) { InkText(text, size = 28.sp, bold = true) }
}
