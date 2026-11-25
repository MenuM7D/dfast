package dfast.app.ui.downloadcard

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import com.squareup.picasso.Picasso
import com.squareup.picasso.Target
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Preview used by the download card.
 * Video mode: framed thumbnail with corner brackets, film perforations, a play glyph and a scrub bar.
 * Audio mode: a slowly spinning disc and LED bars in the same style as the player (drawn here, the player view is untouched).
 */
class DlHeroView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {
    var audio = false
        set(v) { field = v; invalidate() }
    var duration: String = ""
        set(v) { field = v; invalidate() }
    private val accent = 0xFF8E7CFF.toInt()
    private var thumb: Bitmap? = null
    private var thumbUrl = ""
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var angle = 0f
    private var last = 0L
    private val bars = 7
    private val segs = 8
    private val target = object : Target {
        override fun onBitmapLoaded(bitmap: Bitmap, from: Picasso.LoadedFrom?) { thumb = bitmap; invalidate() }
        override fun onBitmapFailed(e: Exception?, errorDrawable: Drawable?) {}
        override fun onPrepareLoad(placeHolderDrawable: Drawable?) {}
    }

    fun setThumb(url: String) {
        if (url.isBlank() || url == thumbUrl) return
        thumbUrl = url
        kotlin.runCatching { Picasso.get().load(url).resize(640, 640).centerCrop().into(target) }
    }

    private fun d(n: Float) = n * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        if (audio) drawAudio(canvas) else drawVideo(canvas)
    }

    private fun drawVideo(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val fw = min(w, h * 16f / 9f)
        val l = (w - fw) / 2f; val r = l + fw
        val rr = d(14f)
        val rect = RectF(l + d(2f), d(2f), r - d(2f), h - d(2f))
        val clip = Path().apply { addRoundRect(rect, rr, rr, Path.Direction.CW) }
        canvas.save(); canvas.clipPath(clip)
        paint.style = Paint.Style.FILL; paint.color = 0xFF3A2F80.toInt(); canvas.drawRect(rect, paint)
        val bmp = thumb
        if (bmp != null) {
            val s = max(rect.width() / bmp.width, rect.height() / bmp.height)
            val bw = bmp.width * s; val bh = bmp.height * s
            val dst = RectF(rect.centerX() - bw / 2, rect.centerY() - bh / 2, rect.centerX() + bw / 2, rect.centerY() + bh / 2)
            canvas.drawBitmap(bmp, null, dst, bmpPaint)
            paint.color = 0x55000000; canvas.drawRect(rect, paint)
        }
        // film perforations
        val pw = d(10f)
        paint.color = 0xCC0F0D1F.toInt()
        canvas.drawRect(rect.left, rect.top, rect.left + pw, rect.bottom, paint)
        canvas.drawRect(rect.right - pw, rect.top, rect.right, rect.bottom, paint)
        paint.color = 0x88_8E7CFF.toInt()
        val n = max(4, (h / d(20f)).toInt())
        for (i in 0 until n) {
            val y = rect.top + (i + 0.5f) * rect.height() / n
            val pr = RectF(0f, y - d(3.5f), d(4f), y + d(3.5f))
            pr.offset(rect.left + d(3f), 0f); canvas.drawRoundRect(pr, 1f, 1f, paint)
            pr.offset(rect.width() - d(10f), 0f); canvas.drawRoundRect(pr, 1f, 1f, paint)
        }
        canvas.restore()
        // frame
        paint.style = Paint.Style.STROKE; paint.strokeWidth = d(2f); paint.color = accent
        canvas.drawRoundRect(rect, rr, rr, paint)
        // corner brackets
        paint.strokeWidth = d(3f); paint.strokeCap = Paint.Cap.ROUND
        val m = d(18f); val bl = d(16f)
        val x1 = rect.left + m; val x2 = rect.right - m; val y1 = rect.top + d(10f); val y2 = rect.bottom - d(10f)
        canvas.drawLines(floatArrayOf(x1, y1, x1 + bl, y1, x1, y1, x1, y1 + bl, x2, y1, x2 - bl, y1, x2, y1, x2, y1 + bl,
            x1, y2, x1 + bl, y2, x1, y2, x1, y2 - bl, x2, y2, x2 - bl, y2, x2, y2, x2, y2 - bl), paint)
        paint.strokeCap = Paint.Cap.BUTT
        // play glyph
        paint.style = Paint.Style.FILL; paint.color = accent
        val pr = min(h * 0.17f, d(26f))
        canvas.drawCircle(rect.centerX(), rect.centerY() - d(4f), pr, paint)
        paint.color = 0xFF14102E.toInt()
        canvas.drawPath(Path().apply {
            val cx = rect.centerX() + pr * 0.1f; val cy = rect.centerY() - d(4f)
            moveTo(cx - pr * 0.4f, cy - pr * 0.55f); lineTo(cx + pr * 0.6f, cy); lineTo(cx - pr * 0.4f, cy + pr * 0.55f); close()
        }, paint)
        // scrub bar
        val sx = rect.left + d(26f); val ex = rect.right - d(26f); val by = rect.bottom - d(20f)
        paint.color = 0x55FFFFFF; canvas.drawRoundRect(sx, by - d(2f), ex, by + d(2f), d(2f), d(2f), paint)
        paint.color = accent; val px = sx + (ex - sx) * 0.32f
        canvas.drawRoundRect(sx, by - d(2f), px, by + d(2f), d(2f), d(2f), paint)
        paint.color = 0xFFFFFFFF.toInt(); canvas.drawCircle(px, by, d(5f), paint)
        if (duration.isNotBlank() && h > d(110f)) {
            paint.color = 0xE6FFFFFF.toInt(); paint.textSize = d(10f); paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(duration, ex, by - d(7f), paint); paint.textAlign = Paint.Align.LEFT
        }
    }

    private fun drawAudio(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val now = System.currentTimeMillis()
        val dt = if (last == 0L) 16 else min(now - last, 64)
        last = now
        angle = (angle + dt * 0.02f) % 360f
        val t = now / 1000f
        val cx = w / 2; val cy = h / 2
        val r = min(h * 0.46f, w * 0.22f)
        canvas.save(); canvas.rotate(angle, cx, cy)
        paint.style = Paint.Style.FILL; paint.color = 0xFF14141B.toInt(); canvas.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f; paint.color = 0x33FFFFFF
        var g = r * 0.97f
        while (g > r * 0.62f) { canvas.drawCircle(cx, cy, g, paint); g -= r * 0.045f }
        paint.style = Paint.Style.FILL
        val tr = r * 0.58f
        val bmp = thumb
        if (bmp != null) {
            val path = Path().apply { addCircle(cx, cy, tr, Path.Direction.CW) }
            canvas.save(); canvas.clipPath(path)
            canvas.drawBitmap(bmp, Rect(0, 0, bmp.width, bmp.height), RectF(cx - tr, cy - tr, cx + tr, cy + tr), bmpPaint)
            canvas.restore()
        } else { paint.color = accent; canvas.drawCircle(cx, cy, tr, paint) }
        paint.color = 0xFF0B0B10.toInt(); canvas.drawCircle(cx, cy, r * 0.07f, paint)
        canvas.restore()
        paint.style = Paint.Style.STROKE; paint.strokeWidth = d(2f); paint.color = accent
        canvas.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.FILL
        // LED bars on both sides (same colours as the player spectrum)
        val gap = d(3f); val bw = d(7f)
        val top = cy - r * 0.9f; val bottom = cy + r * 0.9f
        val sh = (bottom - top - gap * (segs - 1)) / segs
        for (side in 0..1) for (i in 0 until bars) {
            val x = if (side == 0) cx - r - d(18f) - (i + 1) * (bw + gap) else cx + r + d(18f) + i * (bw + gap)
            val lvl = (0.25f + 0.65f * abs(sin(t * (1.3f + i * 0.21f + side) + i * 0.9f)))
            val lit = (lvl * segs).toInt().coerceIn(0, segs)
            for (s in 0 until segs) {
                val y = bottom - (s + 1) * sh - s * gap
                val fr = s / (segs - 1f)
                val col = if (fr < 0.6f) 0xFF3DDC84.toInt() else if (fr < 0.85f) 0xFFFFC857.toInt() else 0xFFFF5A5F.toInt()
                paint.color = if (s < lit) col else 0x22FFFFFF
                canvas.drawRoundRect(x, y, x + bw, y + sh, d(1.5f), d(1.5f), paint)
            }
        }
        if (isAttachedToWindow) postInvalidateOnAnimation()
    }
}

/** Slanted rounded button background with three speed lines, used by the download buttons. */
class SpeedBg(private val color: Int, private val lineColor: Int, private val rtl: Boolean, private val density: Float) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(c: Canvas) {
        val b = bounds; val w = b.width().toFloat(); val h = b.height().toFloat()
        val sk = h * 0.14f
        val path = Path()
        // leading edge leans forward (towards the reading direction)
        if (rtl) { path.moveTo(sk, 0f); path.lineTo(w, 0f); path.lineTo(w - sk, h); path.lineTo(0f, h) }
        else { path.moveTo(sk, 0f); path.lineTo(w, 0f); path.lineTo(w - sk, h); path.lineTo(0f, h) }
        path.close()
        p.style = Paint.Style.FILL; p.color = color; p.pathEffect = CornerPathEffect(h * 0.28f)
        c.drawPath(path, p)
        p.pathEffect = null; p.color = lineColor; p.alpha = 90; p.strokeWidth = 2f * density; p.strokeCap = Paint.Cap.ROUND
        val x0 = if (rtl) w - sk - 4f * density else sk + 4f * density
        val dir = if (rtl) -1f else 1f
        c.drawLine(x0, h * 0.30f, x0 + dir * 26f * density, h * 0.30f, p)
        c.drawLine(x0, h * 0.50f, x0 + dir * 17f * density, h * 0.50f, p)
        c.drawLine(x0, h * 0.70f, x0 + dir * 10f * density, h * 0.70f, p)
    }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(f: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
