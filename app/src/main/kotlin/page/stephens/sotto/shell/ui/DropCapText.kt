package page.stephens.sotto.shell.ui

// A true sunk drop-cap for the Player read-along: the source's first letter as
// a gilded rubric initial that the following two or three lines wrap around,
// then text continues full width below. Compose can't wrap text around an
// inline box across lines, so this hosts a TextView and uses the platform's
// LeadingMarginSpan2 (the standard Android drop-cap mechanism) - the first N
// lines are indented by the cap's width and the cap is drawn into that margin.

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView

private class DropCapSpan(
    private val cap: String,
    private val color: Int,
    private val capSizePx: Float,
    private val lines: Int,
    private val gapPx: Float,
) : LeadingMarginSpan.LeadingMarginSpan2 {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = this@DropCapSpan.color
        textSize = capSizePx
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }
    private val capWidth = paint.measureText(cap)
    override fun getLeadingMargin(first: Boolean) = if (first) (capWidth + gapPx).toInt() else 0
    override fun getLeadingMarginLineCount() = lines
    override fun drawLeadingMargin(
        c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout,
    ) {
        // Draw once, at the paragraph's first line; sit the cap's top at the line top.
        if ((text as? Spanned)?.getSpanStart(this) == start) {
            c.drawText(cap, x.toFloat(), top - paint.fontMetrics.ascent, paint)
        }
    }
}

@Composable
fun DropCapText(
    text: String,
    capColor: Color,
    bodyColor: Color,
    modifier: Modifier = Modifier,
    bodySizeSp: Float = 15f,
    lines: Int = 3,
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                textSize = bodySizeSp
                setTextColor(bodyColor.toArgb())
                setLineSpacing(0f, 1.3f)
            }
        },
        update = { tv ->
            val i = text.indexOfFirst { !it.isWhitespace() }
            if (i < 0) {
                tv.text = text
            } else {
                val cap = text[i].toString()
                val rest = text.substring(0, i) + text.substring(i + 1)
                val fm = tv.paint.fontMetrics
                val lineH = (fm.descent - fm.ascent) * 1.3f
                val capPx = lineH * lines * 0.86f
                val sp = SpannableString(rest)
                sp.setSpan(
                    DropCapSpan(cap, capColor.toArgb(), capPx, lines, tv.paint.textSize * 0.35f),
                    0, rest.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE,
                )
                tv.text = sp
            }
        },
    )
}
