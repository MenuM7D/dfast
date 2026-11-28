package dfast.app.ui.downloadcard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.view.ViewCompat
import com.google.android.material.color.MaterialColors

/** Dots on a line, one per real quality, best at the start edge; the chosen one is large and filled. Display only: taps go to the card. */
class QualityScaleView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {
    private var labels: List<String> = emptyList()
    private var selected = 0
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity

    fun set(l: List<String>, sel: Int) { labels = l; selected = sel; requestLayout(); invalidate() }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (52 * d).toInt())
    }

    override fun onDraw(cv: Canvas) {
        val n = labels.size; if (n < 2) return
        val rtl = ViewCompat.getLayoutDirection(this) == LAYOUT_DIRECTION_RTL
        val pad = 24 * d; val slot = (width - 2 * pad) / (n - 1)
        val line = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant)
        val on = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
        val ink = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
        val soft = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val y = 12 * d
        fun x(i: Int) = if (rtl) width - pad - i * slot else pad + i * slot
        p.color = line; p.strokeWidth = 3 * d; cv.drawLine(x(0), y, x(n - 1), y, p)
        p.color = on; cv.drawLine(x(0), y, x(selected), y, p)
        for (i in 0 until n) {
            p.style = Paint.Style.FILL; p.color = if (i == selected) on else line
            cv.drawCircle(x(i), y, if (i == selected) 8 * d else 5 * d, p)
            // text always fits its slot: the size shrinks instead of being cut
            p.color = if (i == selected) ink else soft; p.textAlign = Paint.Align.CENTER
            p.isFakeBoldText = i == selected
            var size = 13 * sp; p.textSize = size
            val maxW = if (n > 1) slot * 0.95f else width.toFloat()
            while (p.measureText(labels[i]) > maxW && size > 8 * sp) { size -= 0.5f * sp; p.textSize = size }
            cv.drawText(labels[i], x(i), y + 8 * d + 16 * d + 2 * d, p)
        }
        p.isFakeBoldText = false
    }
}
