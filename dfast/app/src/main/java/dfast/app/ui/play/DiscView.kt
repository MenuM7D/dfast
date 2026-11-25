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
    private val bars = 36
    private val level = FloatArray(bars)
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

    fun setThumb(url: String) {
        if (url.isBlank()) return
        kotlin.runCatching { Picasso.get().load(url).resize(600, 600).centerCrop().into(target) }
    }

    private fun d(n: Float) = n * resources.displayMetrics.density

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) {
            val p = playerProvider() ?: return true
            if (e.y > height * 0.9f) {
                val dur = p.duration
                if (dur > 0) p.seekTo((dur * (e.x / width)).toLong())
            } else if (p.isPlaying) p.pause() else p.play()
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val now = System.currentTimeMillis()
        val dt = if (last == 0L) 16 else min(now - last, 64)
        last = now
        val p = playerProvider()
        val playing = p?.isPlaying == true

        // band levels: real audio when the tap is live, soft motion otherwise
        val live = now - AudioTap.lastUpdate < 400 && playing
        val t = now / 1000f
        for (i in 0 until bars) {
            val f = i / (bars - 1f)
            val target = if (live) {
                val base = if (f < 0.35f) AudioTap.bass else if (f < 0.7f) AudioTap.mid else AudioTap.high
                val wob = 0.55f + 0.45f * abs(sin(t * (2.1f + i * 0.37f) + i))
                base * wob
            } else if (playing) {
                0.18f + 0.22f * abs(sin(t * (1.3f + i * 0.21f) + i * 0.9f))
            } else 0.03f
            level[i] += (target - level[i]) * (if (target > level[i]) 0.5f else 0.18f)
        }
        val beat = if (live) AudioTap.bass else if (playing) 0.15f + 0.1f * sin(t * 4f) else 0f
        pulse += (beat - pulse) * 0.3f
        if (playing) angle = (angle + dt * 0.03f) % 360f

        // background
        paint.shader = null
        canvas.drawColor(0xFF0B0B10.toInt())
        paint.shader = RadialGradient(w / 2, h * 0.42f, h * (0.7f + pulse * 0.25f), intArrayOf((accent and 0x00FFFFFF) or 0x55000000, 0x00000000), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        // disc
        val stripH = h * 0.17f
        val cx = w / 2; val cy = (h - stripH) / 2f + d(2f)
        val r = min(w * 0.4f, (h - stripH) * 0.46f) * (1f + pulse * 0.05f)
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
        paint.style = Paint.Style.STROKE; paint.strokeWidth = d(1.5f); paint.color = (accent and 0x00FFFFFF) or 0x88000000.toInt()
        canvas.drawCircle(cx, cy, r + d(4f) + pulse * d(6f), paint)
        paint.style = Paint.Style.FILL

        // pause glyph
        if (!playing && p != null) {
            paint.color = 0xB3000000.toInt(); canvas.drawCircle(cx, cy, r * 0.2f, paint)
            paint.color = Color.WHITE
            val s = r * 0.08f
            val tri = Path().apply { moveTo(cx - s * 0.7f, cy - s * 1.2f); lineTo(cx + s * 1.3f, cy); lineTo(cx - s * 0.7f, cy + s * 1.2f); close() }
            canvas.drawPath(tri, paint)
        }

        // bar strip
        val sx = d(16f); val gap = d(3f)
        val bw = (w - sx * 2 - gap * (bars - 1)) / bars
        val baseY = h - stripH * 0.38f
        for (i in 0 until bars) {
            val bh = max(d(3f), level[i] * stripH * 0.9f)
            val x = sx + i * (bw + gap)
            paint.color = accent
            paint.alpha = (110 + level[i] * 145).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(x, baseY - bh, x + bw, baseY, bw / 2, bw / 2, paint)
        }
        paint.alpha = 255

        // progress line
        val dur = p?.duration ?: 0L
        if (p != null && dur > 0) {
            paint.color = 0x33FFFFFF; canvas.drawRect(0f, h - d(4f), w, h, paint)
            paint.color = accent; canvas.drawRect(0f, h - d(4f), w * (p.currentPosition.toFloat() / dur).coerceIn(0f, 1f), h, paint)
        }
        if (visibility == VISIBLE) postInvalidateOnAnimation()
    }
}
