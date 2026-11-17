package goals.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
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

/** The home screen while nothing is gated (mock: "Home (done for today)"). */
@Composable
fun HomeScreen(status: Status, notice: String?, onSchedule: () -> Unit, onAllowlist: () -> Unit) {
    val (title, body) = when {
        !status.todayScheduled && status.week.isEmpty() -> "Nothing planned." to "Set a schedule to start your streak."
        !status.todayScheduled -> "Rest day." to "Nothing is locked today."
        else -> "Done for today." to "${status.workout} is logged. Every app is open until tomorrow."
    }
    Column(
        modifier = Modifier.fillMaxSize().background(Ink.White).systemBarsPadding().padding(Ink.Gutter),
        verticalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InkLabel(dayHeader(status.today))
            InkText(title, size = 60.sp, bold = true)
            InkText(body)
        }

        val cells = weekCells(status)
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

        Spacer(Modifier.weight(1f))

        if (notice != null) InkText(notice, size = 20.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            InkButton("Schedule", onSchedule, Modifier.weight(1f), filled = false)
            InkButton("Allowed apps", onAllowlist, Modifier.weight(1f), filled = false)
        }
    }
}

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
