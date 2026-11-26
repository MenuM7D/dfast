package dfast.app.ui.play

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import androidx.media3.common.Player
import com.squareup.picasso.Picasso
import com.squareup.picasso.Target
import dfast.app.util.AudioTap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Audio-only screen: a spinning disc with the thumbnail and a bar strip that follows the music. */
class DiscView(c: Context) : View(c) {
    var playerProvider: () -> Player? = { null }
    var accent: Int = 0xFF7C5CFF.toInt()
    private val bars = 24
    private val segs = 14
    private val level = FloatArray(bars)
    private val peak = FloatArray(bars)
    var onPrev: () -> Unit = {}
    var onNext: () -> Unit = {}
    var onShuffle: () -> Unit = {}
    var onRepeat: () -> Unit = {}
    var shuffleOn = false
    var repeatMode = 0
    private val gd = android.view.GestureDetector(c, object : android.view.GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onDoubleTap(e: MotionEvent): Boolean { if (e.y < height * 0.52f) { skip(if (e.x < width / 2) -10000 else 10000); return true }; return false }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { tapAt(e.x, e.y); return true }
    })
    private fun skip(ms: Long) { val p = playerProvider() ?: return; p.seekTo((p.currentPosition + ms).coerceIn(0L, if (p.duration > 0) p.duration else Long.MAX_VALUE)) }
    private fun fmt(ms: Long): String { val s = (ms.coerceAtLeast(0) / 1000).toInt(); return "%d:%02d".format(s / 60, s % 60) }
    private var ctrlCx = FloatArray(7)
    private var ctrlY = 0f
    private fun tapAt(x: Float, y: Float) {
        val p = playerProvider() ?: return
        if (y > height * 0.66f && y < height * 0.76f) { val dur = p.duration; if (dur > 0) p.seekTo((dur * (x / width).coerceIn(0f, 1f)).toLong()); return }
        if (y > height * 0.78f) {
            var best = 0; var bd = Float.MAX_VALUE
            for (i in 0 until 5) { val dd = abs(x - ctrlCx[i]); if (dd < bd) { bd = dd; best = i } }
            when (best) { 0 -> onShuffle(); 1 -> onPrev(); 2 -> dfast.app.util.PlayQueue.togglePlay(p); 3 -> onNext(); 4 -> onRepeat() }
            return
        }
        if (y < height * 0.52f) { dfast.app.util.PlayQueue.togglePlay(p) }
    }
    private var angle = 0f
    private var last = 0L
    private var thumb: Bitmap? = null
    private var pulse = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shader = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val target = object : Target {
        override fun onBitmapLoaded(bitmap: Bitmap, from: Picasso.LoadedFrom?) { thumb = bitmap; invalidate() }
        override fun onBitmapFailed(e: Exception?, errorDrawable: android.graphics.drawable.Drawable?) {}
        override fun onPrepareLoad(placeHolderDrawable: android.graphics.drawable.Drawable?) {}
    }

    private var thumbUrl = ""

    /** Shows the photo of the song that is playing. A different or empty url clears the old photo at once, so a song never shows another song's picture. */
    fun setThumb(url: String) {
        if (url == thumbUrl && thumb != null) return
        thumbUrl = url
        thumb = null; invalidate()
        if (url.isBlank()) { kotlin.runCatching { Picasso.get().cancelRequest(target) }; return }
        kotlin.runCatching { Picasso.get().load(url).resize(600, 600).centerCrop().into(target) }
    }

    private val seekPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun d(n: Float) = n * resources.displayMetrics.density

    // drag-to-seek on the seek bar: press, drag (time follows the finger), seek on release
    private var dragging = false
    private var dragFrac = 0f
    private fun fracAt(x: Float): Float { val sx = d(18f); return ((x - sx) / (width - sx * 2)).coerceIn(0f, 1f) }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val inBand = e.y > height * 0.62f && e.y < height * 0.80f
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (inBand && (playerProvider()?.duration ?: 0L) > 0) {
                dragging = true; dragFrac = fracAt(e.x); parent?.requestDisallowInterceptTouchEvent(true); invalidate(); return true
            }
            MotionEvent.ACTION_MOVE -> if (dragging) { dragFrac = fracAt(e.x); invalidate(); return true }
            MotionEvent.ACTION_UP -> if (dragging) {
                dragging = false
                val p = playerProvider()
                if (p != null && p.duration > 0) p.seekTo((p.duration * dragFrac).toLong())
                invalidate(); return true
            }
            MotionEvent.ACTION_CANCEL -> if (dragging) { dragging = false; invalidate(); return true }
        }
        if (dragging) return true
        gd.onTouchEvent(e); return true
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val now = System.currentTimeMillis()
        val dt = if (last == 0L) 16 else min(now - last, 64)
        last = now
        val p = playerProvider()
        val playing = p?.isPlaying == true
        val live = now - AudioTap.lastUpdate < 400 && playing
        val t = now / 1000f
        for (i in 0 until bars) {
            val f = i / (bars - 1f)
            val tgt = if (live) {
                val base = if (f < 0.5f) AudioTap.bass + (AudioTap.mid - AudioTap.bass) * (f / 0.5f) else AudioTap.mid + (AudioTap.high - AudioTap.mid) * ((f - 0.5f) / 0.5f)
                (base * (0.6f + 0.4f * abs(sin(t * (2.1f + i * 0.37f) + i)))).coerceIn(0f, 1f)
            } else if (playing) 0.15f + 0.2f * abs(sin(t * (1.3f + i * 0.21f) + i * 0.9f)) else 0f
            level[i] += (tgt - level[i]) * (if (tgt > level[i]) 0.45f else 0.12f)
            peak[i] = max(level[i], peak[i] - 0.0006f * dt)
        }
        val beat = if (live) AudioTap.bass else if (playing) 0.15f + 0.1f * sin(t * 4f) else 0f
        pulse += (beat - pulse) * 0.25f
        if (playing) angle = (angle + dt * 0.03f) % 360f

        canvas.drawColor(0xFF0B0B10.toInt())
        paint.shader = RadialGradient(w / 2, h * 0.25f, h * (0.5f + pulse * 0.2f), intArrayOf((accent and 0x00FFFFFF) or 0x44000000, 0x00000000), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        // disc (top half)
        val cx = w / 2; val cy = h * 0.26f
        val r = min(w * 0.3f, h * 0.22f) * (1f + pulse * 0.04f)
        canvas.save(); canvas.rotate(angle, cx, cy)
        paint.color = 0xFF14141B.toInt(); canvas.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f; paint.color = 0x22FFFFFF
        var g = r * 0.97f
        while (g > r * 0.62f) { canvas.drawCircle(cx, cy, g, paint); g -= r * 0.045f }
        paint.style = Paint.Style.FILL
        val tr = r * 0.58f
        val bmp = thumb
        if (bmp != null) {
            val path = Path().apply { addCircle(cx, cy, tr, Path.Direction.CW) }
            canvas.save(); canvas.clipPath(path)
            canvas.drawBitmap(bmp, Rect(0, 0, bmp.width, bmp.height), RectF(cx - tr, cy - tr, cx + tr, cy + tr), shader)
            canvas.restore()
        } else { paint.color = accent; canvas.drawCircle(cx, cy, tr, paint) }
        paint.color = 0xFF0B0B10.toInt(); canvas.drawCircle(cx, cy, r * 0.07f, paint)
        canvas.restore()

        // LED spectrum (E3)
        val specTop = h * 0.5f; val specBot = h * 0.64f
        val sx = d(18f); val gap = d(3f)
        val cw = (w - sx * 2 - gap * (bars - 1)) / bars
        val sh = (specBot - specTop - gap * (segs - 1)) / segs
        for (i in 0 until bars) {
            val x = sx + i * (cw + gap)
            val lit = (level[i] * segs).toInt().coerceIn(0, segs)
            val pk = (peak[i] * segs).toInt().coerceIn(0, segs - 1)
            for (s in 0 until segs) {
                val y = specBot - (s + 1) * sh - s * gap
                val fr = s / (segs - 1f)
                val col = if (fr < 0.6f) 0xFF3DDC84.toInt() else if (fr < 0.85f) 0xFFFFC857.toInt() else 0xFFFF5A5F.toInt()
                paint.color = if (s < lit) col else if (s == pk && playing) 0xFFFFFFFF.toInt() else 0x14FFFFFF
                canvas.drawRoundRect(x, y, x + cw, y + sh, d(1.5f), d(1.5f), paint)
            }
        }

        // seek bar + times
        val dur = p?.duration ?: 0L; val pos = p?.currentPosition ?: 0L
        val by = h * 0.705f
        paint.color = 0x33FFFFFF; canvas.drawRoundRect(sx, by - d(2f), w - sx, by + d(2f), d(2f), d(2f), paint)
        val frac = if (dragging) dragFrac else if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
        paint.color = accent; canvas.drawRoundRect(sx, by - d(2f), sx + (w - sx * 2) * frac, by + d(2f), d(2f), d(2f), paint)
        canvas.drawCircle(sx + (w - sx * 2) * frac, by, d(6f), paint)
        paint.color = 0xB3FFFFFF.toInt(); paint.textSize = d(11f)
        paint.textAlign = Paint.Align.LEFT; canvas.drawText(if (dragging && dur > 0) fmt((dur * dragFrac).toLong()) else fmt(pos), sx, by + d(20f), paint)
        paint.textAlign = Paint.Align.RIGHT; canvas.drawText(if (dur > 0) fmt(dur) else "--:--", w - sx, by + d(20f), paint)
        paint.textAlign = Paint.Align.LEFT

        // controls: shuffle, prev, play/pause, next, repeat  (+-10s via double tap on disc)
        val cyy = h * 0.88f; ctrlY = cyy
        val xs = floatArrayOf(w * 0.12f, w * 0.31f, w * 0.5f, w * 0.69f, w * 0.88f, 0f, 0f)
        ctrlCx = xs
        val on = accent; val off = 0xCCFFFFFF.toInt()
        paint.color = if (shuffleOn) on else off; drawShuffle(canvas, xs[0], cyy)
        paint.color = off; drawSkip(canvas, xs[1], cyy, false)
        paint.color = 0xFFFFFFFF.toInt(); canvas.drawCircle(xs[2], cyy, d(26f), paint)
        paint.color = 0xFF0B0B10.toInt()
        if (playing) { canvas.drawRect(xs[2] - d(8f), cyy - d(10f), xs[2] - d(2.5f), cyy + d(10f), paint); canvas.drawRect(xs[2] + d(2.5f), cyy - d(10f), xs[2] + d(8f), cyy + d(10f), paint) }
        else canvas.drawPath(Path().apply { moveTo(xs[2] - d(6f), cyy - d(11f)); lineTo(xs[2] + d(11f), cyy); lineTo(xs[2] - d(6f), cyy + d(11f)); close() }, paint)
        paint.color = off; drawSkip(canvas, xs[3], cyy, true)
        paint.color = if (repeatMode != 0) on else off; drawRepeat(canvas, xs[4], cyy)
        paint.alpha = 255
        if (visibility == VISIBLE) postInvalidateOnAnimation()
    }

    private fun drawSkip(c: Canvas, x: Float, y: Float, next: Boolean) {
        val s = d(10f)
        val dir = if (next) 1f else -1f
        val path = Path().apply { moveTo(x - dir * s, y - s); lineTo(x + dir * s * 0.6f, y); lineTo(x - dir * s, y + s); close() }
        c.drawPath(path, paint)
        c.drawRect(minOf(x + dir * s * 0.8f, x + dir * s * 1.2f), y - s, maxOf(x + dir * s * 0.8f, x + dir * s * 1.2f), y + s, paint)
    }
    private fun drawShuffle(c: Canvas, x: Float, y: Float) {
        paint.style = Paint.Style.STROKE; paint.strokeWidth = d(2f)
        val s = d(9f)
        c.drawLine(x - s, y + s * 0.8f, x + s, y - s * 0.8f, paint); c.drawLine(x - s, y - s * 0.8f, x - s * 0.2f, y - s * 0.3f, paint); c.drawLine(x + s * 0.2f, y + s * 0.3f, x + s, y + s * 0.8f, paint)
        paint.style = Paint.Style.FILL
    }
    private fun drawRepeat(c: Canvas, x: Float, y: Float) {
        paint.style = Paint.Style.STROKE; paint.strokeWidth = d(2f)
        val s = d(9f)
        c.drawRoundRect(x - s, y - s * 0.7f, x + s, y + s * 0.7f, d(4f), d(4f), paint)
        paint.style = Paint.Style.FILL
        if (repeatMode == 2) { paint.textSize = d(9f); paint.textAlign = Paint.Align.CENTER; c.drawText("1", x, y + d(3f), paint); paint.textAlign = Paint.Align.LEFT }
    }
}
