package goals.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import goals.app.data.AppEntry
import goals.core.PendingAllowlist
import goals.core.Status

/**
 * The allowlist picker's state. [apps] is null while the installed apps load; [draft] is the
 * packages ticked so far, [saved] the list it started from.
 */
class AllowlistUi(
    val apps: List<AppEntry>?,
    val draft: List<String>,
    val saved: List<String>,
    val pending: PendingAllowlist?,
    val saving: Boolean,
    val errors: List<String>,
    val notice: String?,
    val onToggle: (String) -> Unit,
    val onSave: () -> Unit,
)

/** The Allowlist tab: the apps that stay open while today is blocked, and the picker for them. */
@Composable
fun AllowlistScreen(status: Status?, ui: AllowlistUi?) {
    TabPage("Allowlist.") {
        InkText("Ticked apps stay open while today is blocked. Changes start tomorrow.", size = 20.sp)
        if (ui == null) {
            // No endpoint, or the schedule hasn't loaded: today's list, read-only.
            val apps = status?.allowlist.orEmpty()
            if (apps.isEmpty()) InkText("None yet.", bold = true)
            else InkList(modifier = Modifier.weight(1f, fill = false), rows = apps.map { pkg -> @Composable { InkRow(pkg, "") } })
            return@TabPage
        }
        val apps = ui.apps
        Column(Modifier.weight(1f)) {
            if (apps == null) {
                InkText("Loading apps…")
            } else {
                // Packages on the list that aren't installed stay visible, so they can be unticked.
                val missing = ui.draft.filter { p -> apps.none { it.packageName == p } }.map { AppEntry(it, it) }
                InkList(
                    modifier = Modifier.weight(1f, fill = false),
                    rows = (apps + missing).map { a ->
                        @Composable { CheckRow(a.label, a.packageName in ui.draft, bold = false) { ui.onToggle(a.packageName) } }
                    },
                )
            }
        }
        val changed = ui.draft.toSet() != ui.saved.toSet()
        val line = ui.errors.joinToString(" ").ifEmpty {
            when {
                changed -> "${ui.draft.size} ticked. This app, the launcher and Settings are always open."
                ui.notice != null -> ui.notice
                ui.pending != null -> "A changed list starts ${dayHeader(ui.pending.from)}."
                else -> "This app, the launcher and Settings are always open."
            }
        }
        InkText(line, size = 20.sp, bold = ui.errors.isNotEmpty() || ui.notice != null)
        val canSave = changed && !ui.saving
        InkButton(if (ui.saving) "Saving…" else "Save allowlist", { if (canSave) ui.onSave() }, Modifier.fillMaxWidth(), filled = canSave)
    }
}
