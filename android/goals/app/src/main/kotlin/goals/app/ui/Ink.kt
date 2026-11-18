package goals.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The e-ink kit: black on white only (grays dither and ghost), 3 dp rules, large type,
 * selected or done shown as a solid black block. Built on Compose foundation, not Material,
 * so nothing ripples or animates; clickables pass `indication = null`.
 */
object Ink {
    val Black = Color.Black
    val White = Color.White
    val Rule = 3.dp
    val ThinRule = 2.dp
    val Gutter = 24.dp
}

@Composable
fun InkText(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 24.sp,
    bold: Boolean = false,
    color: Color = Ink.Black,
    align: TextAlign = TextAlign.Start,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = size,
            lineHeight = size * 1.3f,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            fontFamily = FontFamily.SansSerif,
            textAlign = align,
        ),
    )
}

/** Small caps-style label, e.g. "TUESDAY, NOV 17". */
@Composable
fun InkLabel(text: String, modifier: Modifier = Modifier) {
    BasicText(
        text = text.uppercase(),
        modifier = modifier,
        style = TextStyle(
            color = Ink.Black,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.6.sp,
            fontFamily = FontFamily.SansSerif,
        ),
    )
}

/** A full-width button: filled (primary) or outlined. */
@Composable
fun InkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = true) {
    val shape = if (filled) Modifier.background(Ink.Black) else Modifier.border(Ink.Rule, Ink.Black)
    Box(
        modifier = modifier
            .height(80.dp)
            .then(shape)
            .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        InkText(text, size = 28.sp, bold = true, color = if (filled) Ink.White else Ink.Black)
    }
}

/** A square check box: empty outline, or a black block with a white check. */
@Composable
fun InkCheck(checked: Boolean, size: Dp = 32.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .then(if (checked) Modifier.background(Ink.Black) else Modifier.border(Ink.Rule, Ink.Black)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) CheckMark(size * 0.7f, Ink.White)
    }
}

@Composable
fun CheckMark(size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = size.toPx()
        val path = Path().apply {
            moveTo(w * 0.17f, w * 0.5f)
            lineTo(w * 0.42f, w * 0.75f)
            lineTo(w * 0.83f, w * 0.25f)
        }
        drawPath(path, color, style = Stroke(width = w * 0.17f, cap = StrokeCap.Square))
    }
}

/** A bordered number with a caption, e.g. "12 / day streak". */
@Composable
fun StatBox(value: String, label: String, modifier: Modifier = Modifier, suffix: String? = null) {
    Column(
        modifier = modifier.border(Ink.Rule, Ink.Black).padding(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            InkText(value, size = 44.sp, bold = true)
            if (suffix != null) InkText(suffix, size = 24.sp, modifier = Modifier.padding(bottom = 5.dp))
        }
        InkText(label, size = 20.sp)
    }
}

/**
 * A bordered list; rows are separated by thin rules. When [modifier] limits its height (e.g.
 * `weight(1f, fill = false)`), the rows scroll inside the border.
 */
@Composable
fun InkList(rows: List<@Composable () -> Unit>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().border(Ink.Rule, Ink.Black).padding(Ink.Rule).verticalScroll(rememberScrollState())) {
        rows.forEachIndexed { i, row ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(Ink.ThinRule).background(Ink.Black))
            row()
        }
    }
}
