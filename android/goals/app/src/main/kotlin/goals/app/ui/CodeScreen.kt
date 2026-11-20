package goals.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Typing the unlock code from the status page on a phone. A keypad rather than the keyboard, so
 * the e-ink screen redraws little. [onSubmit] gets the 6 digits; [message] says how the last try
 * went.
 */
@Composable
fun CodeScreen(message: String?, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var code by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().background(Ink.White).systemBarsPadding().padding(Ink.Gutter),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        InkText("Unlock code", size = 44.sp, bold = true)
        InkText("No connection? Open the gate's page on your phone. Once today is done or skipped it shows a code.", size = 20.sp)
        InkText(
            (0 until 6).joinToString(" ") { code.getOrNull(it)?.toString() ?: "_" },
            size = 44.sp, bold = true, align = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        if (message != null) InkError(message)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("123", "456", "789", " 0<").forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { k ->
                        val m = Modifier.weight(1f).height(72.dp)
                        when (k) {
                            ' ' -> Box(m)
                            '<' -> Key("⌫", m) { code = code.dropLast(1) }
                            else -> Key(k.toString(), m) {
                                if (code.length < 6) code += k
                                if (code.length == 6) { onSubmit(code); code = "" }
                            }
                        }
                    }
                }
            }
        }
        Box(Modifier.weight(1f))
        InkButton("Cancel", onCancel, Modifier.fillMaxWidth(), filled = false)
    }
}

@Composable
private fun Key(text: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.border(Ink.Rule, Ink.Black).clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { InkText(text, size = 32.sp, bold = true) }
}
