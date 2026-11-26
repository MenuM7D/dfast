package dfast.app.ui.downloadcard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max

/** DFast quality chart: one column of LED cells per quality. Lit cells encode the size. Solid colours only. */
class LedChartView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {
    class Col(val label: String, val sizeText: String, val frac: Float, val tag: String = "")

    private var cols: List<Col> = emptyList()
    var index = 0
        private set
    var onChange: (Int) -> Unit = {}
    private val accent = 0xFF8E7CFF.toInt()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun d(n: Float) = n * resources.displayMetrics.density
    private val cells = 9

    fun setColumns(l: List<Col>, selected: Int) {
        cols = l; index = selected.coerceIn(0, max(0, l.size - 1)); invalidate()
    }

    private fun slotW() = (width - d(8f)) / max(1, cols.size).coerceAtLeast(4)
    private fun colX(i: Int): Float {
        val n = max(cols.size, 1)
        val w = (width - d(8f)) / max(n, 4)
        val total = w * n
        return (width - total) / 2f + w * (i + 0.5f)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (cols.isEmpty()) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> parent.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent.requestDisallowInterceptTouchEvent(false)
        }
        var best = 0; var bd = Float.MAX_VALUE
        for (i in cols.indices) { val dd = abs(e.x - colX(i)); if (dd < bd) { bd = dd; best = i } }
        if (best != index) { index = best; invalidate(); onChange(best); performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        if (cols.isEmpty()) return
        val topText = d(22f)
        val hasTags = cols.any { it.tag.isNotEmpty() }
        val bottomText = if (hasTags) d(52f) else d(26f)
        val area = height - topText - bottomText
        val cellH = area / cells
        val gap = d(3f)
        val cw = (slotW() * 0.56f).coerceAtMost(d(40f))
        for (i in cols.indices) {
            val col = cols[i]
            val x = colX(i)
            val sel = i == index
            val lit = (col.frac * cells).toInt().coerceIn(1, cells)
            for (k in 0 until cells) {
                val top = topText + area - (k + 1) * cellH + gap / 2
                val bot = topText + area - k * cellH - gap / 2
                if (k < lit) {
                    if (sel) {
                        paint.color = 0x338E7CFF
                        canvas.drawRoundRect(x - cw / 2 - d(3f), top - d(2f), x + cw / 2 + d(3f), bot + d(2f), d(6f), d(6f), paint)
                    }
                    paint.color = if (sel) accent else 0xFF5A5878.toInt()
                } else paint.color = 0xFF23222F.toInt()
                canvas.drawRoundRect(x - cw / 2, top, x + cw / 2, bot, d(4f), d(4f), paint)
            }
            paint.textAlign = Paint.Align.CENTER
            paint.isFakeBoldText = sel
            paint.textSize = d(if (sel) 13f else 11f)
            paint.color = if (sel) 0xFFFFFFFF.toInt() else 0x99FFFFFF.toInt()
            canvas.drawText(col.sizeText, x, topText + area - lit * cellH - d(6f).coerceAtLeast(0f) + 0f, paint)
            paint.textSize = d(if (sel) 14f else 12f)
            paint.color = if (sel) accent else 0xB3FFFFFF.toInt()
            canvas.drawText(col.label, x, if (hasTags) topText + area + d(20f) else height - d(6f), paint)
            paint.isFakeBoldText = false
            if (col.tag.isNotEmpty()) {
                paint.textSize = d(10.5f)
                val tw = paint.measureText(col.tag)
                val ty = topText + area + d(30f)
                paint.color = if (sel) 0x558E7CFF else 0x22FFFFFF
                canvas.drawRoundRect(x - tw / 2 - d(7f), ty, x + tw / 2 + d(7f), ty + d(18f), d(9f), d(9f), paint)
                paint.color = if (sel) 0xEEFFFFFF.toInt() else 0x99FFFFFF.toInt()
                canvas.drawText(col.tag, x, ty + d(13f), paint)
            }
        }
    }
}

/** Tiny 5-cell meter used in the "more formats" rows. */
class LedMeterView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {
    var frac = 0.5f
    var on = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun d(n: Float) = n * resources.displayMetrics.density
    override fun onDraw(canvas: Canvas) {
        val n = 5
        val lit = (frac * n).toInt().coerceIn(1, n)
        val w = d(5f); val gap = d(3f)
        for (k in 0 until n) {
            val h = d(6f + k * 3f)
            val x = k * (w + gap)
            paint.color = if (k < lit) (if (on) 0xFF8E7CFF.toInt() else 0xFF5A5878.toInt()) else 0xFF23222F.toInt()
            canvas.drawRoundRect(x, height - h, x + w, height.toFloat(), d(2f), d(2f), paint)
        }
    }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(d(37f).toInt(), d(24f).toInt())
}
