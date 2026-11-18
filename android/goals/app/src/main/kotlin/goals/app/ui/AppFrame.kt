package goals.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The app's own tabs, in tab bar order. */
enum class Tab(val label: String) { Status("Status"), Schedule("Schedule"), Allowlist("Allowlist") }

/** The app's own screen: [content] for the [selected] tab above a tab bar that stays put. */
@Composable
fun AppFrame(selected: Tab, onSelect: (Tab) -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(Ink.White).systemBarsPadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        TabBar(selected, onSelect)
    }
}

/** A rule on top, then one cell per tab; the selected one is a solid black block. */
@Composable
private fun TabBar(selected: Tab, onSelect: (Tab) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(Ink.Rule).background(Ink.Black))
        Row(Modifier.fillMaxWidth().height(64.dp)) {
            Tab.entries.forEachIndexed { i, tab ->
                if (i > 0) Box(Modifier.width(Ink.ThinRule).fillMaxHeight().background(Ink.Black))
                val on = tab == selected
                Box(
                    modifier = Modifier.weight(1f).fillMaxHeight()
                        .then(if (on) Modifier.background(Ink.Black) else Modifier)
                        .clickable(interactionSource = null, indication = null, role = Role.Tab) { onSelect(tab) },
                    contentAlignment = Alignment.Center,
                ) {
                    InkText(tab.label, size = 24.sp, bold = true, color = if (on) Ink.White else Ink.Black)
                }
            }
        }
    }
}
