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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
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
        // A day with no source has nothing planned: no saved week, default or earlier week to copy.
        !status.todayScheduled && status.week.all { it.source == null } ->
            "Nothing planned." to "Set a schedule to start your streak."
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
                    // Tapping a day explains its mark in a tooltip under it; tapping elsewhere closes it.
                    var open by remember { mutableStateOf<String?>(null) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        cells.forEach { c ->
                            WeekDayCell(c, Modifier.weight(1f), open == c.date, { open = if (open == c.date) null else c.date }, { open = null })
                        }
                    }
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
private fun WeekDayCell(cell: WeekCell, modifier: Modifier, tip: Boolean, onTap: () -> Unit, onDismiss: () -> Unit) {
    Column(
        modifier.clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onTap),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
            if (tip) {
                Popup(popupPositionProvider = BelowAnchor, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
                    Column(
                        Modifier.widthIn(max = 320.dp).background(Ink.White).border(Ink.Rule, Ink.Black).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        InkText("${dayHeader(cell.date)} · ${cell.workout}", size = 20.sp, bold = true)
                        InkText(markText(cell), size = 20.sp)
                    }
                }
            }
        }
    }
}

/** What a day's mark means, for its tooltip. */
fun markText(cell: WeekCell): String = when (cell.mark) {
    DayMark.DONE -> "Done: every goal was met."
    DayMark.GYM -> if (cell.isToday) "Gym day, not done yet." else "Gym day, still to come."
    DayMark.MISSED -> "Missed with no freeze token left, so the streak reset."
    DayMark.FROZEN -> "Missed, but a freeze token kept the streak."
    DayMark.REST -> "Rest day: nothing is locked."
}

/** Puts a popup just under its anchor, centred on it but kept inside the window. */
private object BelowAnchor : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width))
        return IntOffset(x, anchorBounds.bottom + 8)
    }
}

@Composable
private fun MarkedBox(box: Modifier, text: String, rule: androidx.compose.ui.unit.Dp = Ink.Rule) {
    Box(box.border(rule, Ink.Black), contentAlignment = Alignment.Center) { InkText(text, size = 28.sp, bold = true) }
}
