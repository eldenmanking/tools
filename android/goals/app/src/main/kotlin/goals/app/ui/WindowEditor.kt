package goals.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import goals.core.MINUTES_PER_DAY
import goals.core.Window
import goals.core.clockText

/** Steps of the window editor's − and +. */
private const val STEP = 30

/**
 * Choosing when a workout gates: its start and end in half-hour steps, or all day. [onDefault],
 * when given, drops a day's own window for the workout's. [onDone] goes back.
 */
@Composable
fun WindowEditor(title: String, window: Window, onChange: (Window) -> Unit, onDone: () -> Unit, onDefault: (() -> Unit)? = null) {
    TabPage(title) {
        InkText("Apps are locked only in this window, until the workout is in your log.", size = 20.sp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            InkList(
                rows = listOf(
                    @Composable {
                        TimeRow("Starts", window.start,
                            { onChange(window.copy(start = maxOf(0, window.start - STEP))) },
                            { onChange(window.copy(start = minOf(window.end - STEP, window.start + STEP))) })
                    },
                    @Composable {
                        TimeRow("Ends", window.end,
                            { onChange(window.copy(end = maxOf(window.start + STEP, window.end - STEP))) },
                            { onChange(window.copy(end = minOf(MINUTES_PER_DAY, window.end + STEP))) })
                    },
                ),
            )
            InkNote(if (window.allDay) "Gated all day." else "Gated ${clockText(window.start)} to ${clockText(window.end)}.")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            InkButton("All day", { onChange(Window.ALL_DAY) }, Modifier.weight(1f), filled = false)
            if (onDefault != null) InkButton("Usual time", onDefault, Modifier.weight(1f), filled = false)
        }
        InkButton("Done", onDone, Modifier.fillMaxWidth())
    }
}

@Composable
private fun TimeRow(label: String, minute: Int, onLess: () -> Unit, onMore: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InkText(label, size = 22.sp, bold = true, modifier = Modifier.weight(1f))
        SmallButton("−", onLess)
        InkText(clockText(minute), size = 26.sp, bold = true, align = TextAlign.Center, modifier = Modifier.width(96.dp))
        SmallButton("+", onMore)
    }
}
