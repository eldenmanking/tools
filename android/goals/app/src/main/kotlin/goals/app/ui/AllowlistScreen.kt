package goals.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import goals.core.Status

/** The Allowlist tab: the apps that stay open while today is blocked, read-only until the picker comes. */
@Composable
fun AllowlistScreen(status: Status?) {
    TabPage("Allowlist.") {
        InkText("While today is blocked, these apps stay open:")
        val apps = status?.allowlist.orEmpty()
        if (apps.isEmpty()) {
            InkText("None yet.", bold = true)
        } else {
            InkList(
                modifier = Modifier.weight(1f, fill = false),
                rows = apps.map { pkg -> @Composable { InkText(pkg, size = 22.sp, modifier = Modifier.fillMaxWidth().padding(16.dp)) } },
            )
        }
        InkText("This app, the launcher and Settings are always open. Picking apps comes in a later step.", size = 20.sp)
    }
}
