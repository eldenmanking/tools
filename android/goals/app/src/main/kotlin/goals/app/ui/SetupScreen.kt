package goals.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import goals.app.Need

/**
 * First-run checklist: each permission the gate needs, with a button to its settings
 * screen. Shown until the required ones are granted; the optional ones show "Skip".
 */
@Composable
fun SetupScreen(missing: List<Need>, onOpen: (Need) -> Unit, onSkip: (() -> Unit)?) {
    Column(
        modifier = Modifier.fillMaxSize().background(Ink.White).systemBarsPadding().padding(Ink.Gutter),
        verticalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InkLabel("Setup")
            InkText("Let the gate work.", size = 52.sp, bold = true)
            InkText("Gym Gate needs these, once. Each button opens the system setting; come back here after.")
        }
        InkList(
            Need.entries.map { need ->
                @Composable {
                    val done = need !in missing
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        InkCheck(done)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            InkText(need.title + if (need.required) "" else " (optional)", size = 26.sp, bold = true)
                            InkText(need.why, size = 20.sp)
                        }
                        if (!done) InkButton("Open", { onOpen(need) }, Modifier.width(140.dp), filled = false)
                    }
                }
            },
        )
        Spacer(Modifier.weight(1f))
        if (onSkip != null) {
            InkButton("Continue without the optional ones", onSkip, Modifier.fillMaxWidth(), filled = false)
        }
    }
}
