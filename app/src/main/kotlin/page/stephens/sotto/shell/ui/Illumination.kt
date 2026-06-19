package page.stephens.sotto.shell.ui

// Illuminated-manuscript decorative elements (docs/41-design-system.md):
//   SottoWordmark - the title in the medieval display face, set as an Insular
//                   *diminuendo* (the opening letter largest, each following one
//                   a step smaller). The one place that face appears.
//   Versal        - a gilded illuminated initial: gold letter on lapis with a
//                   gold rule and filigree corner scrolls.
//   RubricHeader  - a section heading in rubric red, spaced small-caps.
//   FleuronRule   - a hairline gold rule centered on a fleuron (❧).
//   FoliateRule   - a gold rule sprouting small leaves, a richer section break.
// Used sparingly, as illumination was: a few gilded marks on a readable page.

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SottoWordmark(
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 26.sp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val text = buildAnnotatedString {
        "Sotto".forEachIndexed { i, c ->
            val scale = (1f - 0.085f * i).coerceAtLeast(0.62f)   // the diminuendo cascade
            withStyle(SpanStyle(fontSize = fontSize * scale)) { append(c.toString()) }
        }
    }
    Text(text, modifier = modifier, fontFamily = Wordmark, color = color)
}

/** A gilded illuminated initial: gold letter on lapis, with a gold rule and
 *  filigree corner scrolls - the way a manuscript text opens. */
@Composable
fun Versal(letter: Char, modifier: Modifier = Modifier, boxSize: Dp = 64.dp) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier.size(boxSize).background(cs.primary, MaterialTheme.shapes.small),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val gold = cs.secondary
            val sw = 1.6.dp.toPx()
            val pad = 4.dp.toPx()
            val w = size.width - pad * 2
            val h = size.height - pad * 2
            drawRoundRect(
                color = gold, topLeft = Offset(pad, pad), size = Size(w, h),
                cornerRadius = CornerRadius(4.dp.toPx()), style = Stroke(sw),
            )
            val r = size.width * 0.16f
            val o = size.width * 0.12f
            val far = size.width - o - r
            // corner filigree scrolls (quarter arcs)
            drawArc(gold, 90f, 90f, false, Offset(o, o), Size(r, r), style = Stroke(sw))
            drawArc(gold, 0f, 90f, false, Offset(far, o), Size(r, r), style = Stroke(sw))
            drawArc(gold, 180f, 90f, false, Offset(o, far), Size(r, r), style = Stroke(sw))
            drawArc(gold, 270f, 90f, false, Offset(far, far), Size(r, r), style = Stroke(sw))
        }
        Text(
            letter.uppercaseChar().toString(),
            color = cs.secondary,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Bold,
            fontSize = (boxSize.value * 0.5f).sp,
        )
    }
}

/** A rubricated heading: red (Latin *ruber*), spaced small-caps. Sparingly. */
@Composable
fun RubricHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        color = MaterialTheme.colorScheme.tertiary,
        style = MaterialTheme.typography.labelLarge,
        letterSpacing = 1.5.sp,
    )
}

/** A scribe's section break: hairline gold rule centered on a fleuron. */
@Composable
fun FleuronRule(modifier: Modifier = Modifier) {
    val gold = MaterialTheme.colorScheme.secondary
    Row(
        modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(gold))
        Text("  ❧  ", color = gold, fontFamily = FontFamily.Serif)
        Box(Modifier.weight(1f).height(1.dp).background(gold))
    }
}

/** A foliate rule: a gold stem with small leaves sprouting alternately - the
 *  manuscript's living border, reduced to a horizontal section break. */
@Composable
fun FoliateRule(modifier: Modifier = Modifier) {
    val gold = MaterialTheme.colorScheme.secondary
    Canvas(modifier.fillMaxWidth().height(22.dp).padding(horizontal = 4.dp)) {
        val midY = size.height / 2f
        val w = size.width
        drawLine(gold, Offset(0f, midY), Offset(w, midY), strokeWidth = 1.4.dp.toPx())
        val n = 6
        for (i in 1 until n) {
            leaf(Offset(w * i / n, midY), up = i % 2 == 0, gold = gold)
        }
    }
}

private fun DrawScope.leaf(at: Offset, up: Boolean, gold: Color) {
    val len = 13.dp.toPx()
    val wide = 5.dp.toPx()
    val dir = if (up) -1f else 1f
    val tip = Offset(at.x, at.y + len * dir)
    val path = Path().apply {
        moveTo(at.x, at.y)
        quadraticBezierTo(at.x - wide, at.y + len * 0.5f * dir, tip.x, tip.y)
        quadraticBezierTo(at.x + wide, at.y + len * 0.5f * dir, at.x, at.y)
        close()
    }
    drawPath(path, gold)
}
