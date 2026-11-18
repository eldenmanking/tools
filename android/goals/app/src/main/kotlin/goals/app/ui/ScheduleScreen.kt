package goals.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import goals.core.Status

/** The Schedule tab: this week's plan, read-only until the editor comes (step 5). */
@Composable
fun ScheduleScreen(status: Status?) {
    TabPage("Schedule.") {
        if (status == null || status.week.isEmpty()) {
            InkText("Nothing is planned yet.")
        } else {
            InkLabel("This week")
            InkList(
                modifier = Modifier.weight(1f, fill = false),
                rows = status.week.map { d ->
                    @Composable {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            InkText(
                                dayHeader(d.date),
                                size = 22.sp,
                                bold = d.date == status.today,
                                modifier = Modifier.weight(1f),
                            )
                            InkText(d.workout, size = 22.sp, bold = d.workout != "Rest")
                        }
                    }
                },
            )
            InkText("Days up to today are locked. Editing comes in a later step.", size = 20.sp)
        }
    }
}

/** A tab's page: a title, then [content], with the usual gutter and spacing. */
@Composable
fun TabPage(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(Ink.White).padding(Ink.Gutter),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        InkText(title, size = 44.sp, bold = true)
        content()
    }
}
