package goals.app.ui

import androidx.compose.foundation.layout.Arrangement
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
    /** The list in effect today, to star what changes tomorrow. */
    val inEffect: List<String>,
    val pending: PendingAllowlist?,
    val saving: Boolean,
    val errors: List<String>,
    val notice: String?,
    val onToggle: (String) -> Unit,
    val onSave: () -> Unit,
)

/** The Allowlist tab: the apps that stay open while today is blocked, and the picker for them. */
@Composable
fun AllowlistScreen(status: Status?, ui: AllowlistUi?, loading: Boolean = false, loadError: String? = null) {
    TabPage("Allowlist.") {
        InkText("Ticked apps stay open while today is blocked.", size = 20.sp)
        if (ui == null) {
            when {
                loadError != null -> InkError(loadError)
                loading -> InkText("Loading…")
                else -> {
                    // No endpoint: today's list, read-only.
                    val apps = status?.allowlist.orEmpty()
                    if (apps.isEmpty()) InkText("No apps are allowed.")
                    else InkList(modifier = Modifier.weight(1f, fill = false), rows = apps.map { pkg -> @Composable { InkRow(pkg, "") } })
                }
            }
            return@TabPage
        }
        val apps = ui.apps
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (apps == null) {
                InkText("Loading apps…")
            } else {
                // Packages on the list that aren't installed stay visible, so they can be unticked.
                val missing = ui.draft.filter { p -> apps.none { it.packageName == p } }.map { AppEntry(it, it) }
                InkList(
                    modifier = Modifier.weight(1f, fill = false),
                    rows = (apps + missing).map { a ->
                        // A star marks an app ticked or unticked since today's list: it changes tomorrow.
                        val on = a.packageName in ui.draft
                        @Composable { CheckRow(starred(a.label, on != (a.packageName in ui.inEffect)), on, bold = false) { ui.onToggle(a.packageName) } }
                    },
                )
                if (ui.draft.toSet() != ui.inEffect.toSet()) InkNote("* Changes start tomorrow")
            }
        }
        val changed = ui.draft.toSet() != ui.saved.toSet()
        when {
            ui.errors.isNotEmpty() -> InkError(ui.errors.joinToString(" "))
            ui.notice != null && !changed -> InkNote(ui.notice, bold = true)
            else -> InkNote("This app, the launcher and Settings are always open.")
        }
        val canSave = changed && !ui.saving
        InkButton(if (ui.saving) "Saving…" else "Save allowlist", { if (canSave) ui.onSave() }, Modifier.fillMaxWidth(), filled = canSave)
    }
}
