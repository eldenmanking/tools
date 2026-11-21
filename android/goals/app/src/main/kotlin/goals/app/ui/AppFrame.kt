package goals.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The app's own tabs, in tab bar order. */
enum class Tab(val label: String) { Status("Status"), Schedule("Schedule"), Goals("Goals"), Allowlist("Allowlist") }

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
        InkSegments(Tab.entries.map { it.label }, selected.ordinal, { onSelect(Tab.entries[it]) }, height = 64.dp, framed = false)
    }
}
