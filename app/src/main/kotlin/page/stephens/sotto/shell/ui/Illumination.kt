package page.stephens.sotto.shell.ui

// Illuminated-manuscript decorative elements (docs/41-design-system.md):
//   SottoWordmark - the title in blackletter, the one place that face appears.
//   Versal        - an illuminated drop-cap: the gold-bordered lapis initial
//                   that opens a manuscript text.
//   FleuronRule   - a hairline gold rule centered on a fleuron (❧), the
//                   scribe's section break.
// Used sparingly, as illumination was: a few gilded marks on an otherwise
// plain, readable page.

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
    Text("Sotto", modifier = modifier, fontFamily = Blackletter, fontSize = fontSize, color = color)
}

/** An illuminated drop-cap: a gold-bordered lapis box with a serif initial,
 *  the way a manuscript text opens. */
@Composable
fun Versal(letter: Char, modifier: Modifier = Modifier, boxSize: Dp = 56.dp) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier
            .size(boxSize)
            .background(cs.primary, MaterialTheme.shapes.small)
            .border(2.dp, cs.secondary, MaterialTheme.shapes.small),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            letter.uppercaseChar().toString(),
            color = cs.onPrimary,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Bold,
            fontSize = (boxSize.value * 0.6f).sp,
        )
    }
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
