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
    private var thumbPrev: Bitmap? = null
    private var prevT = 0L
    private var thumbT = 0L
    private var pulse = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shader = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val target = object : Target {
        override fun onBitmapLoaded(bitmap: Bitmap, from: Picasso.LoadedFrom?) { thumb = bitmap; thumbT = System.currentTimeMillis(); makeGlass(bitmap); invalidate() }
        override fun onBitmapFailed(e: Exception?, errorDrawable: android.graphics.drawable.Drawable?) {}
        override fun onPrepareLoad(placeHolderDrawable: android.graphics.drawable.Drawable?) {}
    }

    private var thumbUrl = ""

    /** Shows the photo of the song that is playing. A different or empty url clears the old photo at once, so a song never shows another song's picture. */
    fun setThumb(url: String) {
        if (url == thumbUrl && thumb != null) return
        thumbUrl = url
        if (thumb != null) { thumbPrev = thumb; prevT = System.currentTimeMillis() }
        thumb = null; makeGlass(null); invalidate()
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
        val t = (now % 1_000_000L) / 1000f // small number: a float of the full epoch time has steps of 128 s
        val sx = d(18f)
        if (ring) drawRingScene(canvas, w, h, now, dt, playing, live, if (saving) 0f else t) else drawClassic(canvas, w, h, now, dt, playing, live, t)

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
        paint.color = if (shuffleOn) on else off; drawIc(canvas, dfast.app.R.drawable.ic_pd_shuffle, xs[0], cyy, shuffleOn)
        paint.color = off; drawSkip(canvas, xs[1], cyy, false)
        paint.color = 0xFFFFFFFF.toInt(); canvas.drawCircle(xs[2], cyy, d(26f), paint)
        paint.color = 0xFF0B0B10.toInt()
        if (playing) { canvas.drawRect(xs[2] - d(8f), cyy - d(10f), xs[2] - d(2.5f), cyy + d(10f), paint); canvas.drawRect(xs[2] + d(2.5f), cyy - d(10f), xs[2] + d(8f), cyy + d(10f), paint) }
        else canvas.drawPath(Path().apply { moveTo(xs[2] - d(6f), cyy - d(11f)); lineTo(xs[2] + d(11f), cyy); lineTo(xs[2] - d(6f), cyy + d(11f)); close() }, paint)
        paint.color = off; drawSkip(canvas, xs[3], cyy, true)
        paint.color = if (repeatMode != 0) on else off; drawIc(canvas, if (repeatMode == 2) dfast.app.R.drawable.ic_pd_repeat_1 else dfast.app.R.drawable.ic_pd_repeat, xs[4], cyy, repeatMode != 0)
        paint.alpha = 255
        if (visibility == VISIBLE) { if (ring) postInvalidateDelayed(if (saving) 1000L else if (!playing) 50L else 33L) else postInvalidateOnAnimation() } // the ring look draws at 30 fps to save battery
    }

    /** The classic look: spinning disc and the LED bar strip. */
    private fun drawClassic(canvas: Canvas, w: Float, h: Float, now: Long, dt: Long, playing: Boolean, live: Boolean, t: Float) {
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

    }

    // ---- ring look: neon ring around the cover + glass background from the cover colours ----
    private val dyn = dfast.app.util.RingDynamics()
    private var ring = true
    private class Mesh(val a: Bitmap, val b: Bitmap)
    private var glass: Mesh? = null
    private var glassPrev: Mesh? = null
    private var pal = IntArray(4) { accent }
    private var palPrev = IntArray(4) { accent }
    private var fadeStart = 0L
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val ringPath = Path()
    private val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var shade: LinearGradient? = null

    private var saverOn = true
    private var saving = false
    private var lastBatt = 0L
    private fun prefs() = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
    private fun readRingSettings() {
        kotlin.runCatching {
            val sp = prefs()
            dyn.intensity = dfast.app.util.RingDynamics.intensityFor(sp.getString("audio_ring_intensity", "auto"))
            saverOn = sp.getBoolean("audio_ring_saver", true)
        }
    }
    /** Battery state is read at most every 30 s (a sticky broadcast, no receiver kept). */
    private fun updateSaving(now: Long) {
        if (now - lastBatt < 30_000L && lastBatt != 0L) return
        lastBatt = now
        saving = kotlin.runCatching {
            val i = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val lvl = i?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = i?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
            val st = i?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = st == android.os.BatteryManager.BATTERY_STATUS_CHARGING || st == android.os.BatteryManager.BATTERY_STATUS_FULL
            val pct = if (lvl >= 0 && scale > 0) lvl * 100 / scale else -1
            dfast.app.util.RingPower.saverActive(saverOn, pct, charging)
        }.getOrDefault(false)
    }

    init { ring = readRingPref(); readRingSettings() }
    private fun readRingPref() = kotlin.runCatching { androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).getBoolean("audio_ring_style", true) }.getOrDefault(true)
    override fun onAttachedToWindow() { super.onAttachedToWindow(); ring = readRingPref(); readRingSettings(); lastBatt = 0L }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        shade = LinearGradient(0f, h * 0.42f, 0f, h * 0.8f, 0x000B0B10, 0xFF0B0B10.toInt(), Shader.TileMode.CLAMP)
    }

    /** New cover: its colours, turned into two small smooth meshes (64 x 64, made once per song). They are stretched over the top of the screen and cross-faded slowly, which gives the frosted, drifting glass. */
    private fun makeGlass(bmp: Bitmap?) {
        glassPrev = glass; palPrev = pal; fadeStart = System.currentTimeMillis()
        if (bmp == null) { pal = IntArray(4) { accent } }
        else {
            val big = Bitmap.createScaledBitmap(bmp, 64, 64, true)
            val bp = IntArray(64 * 64); big.getPixels(bp, 0, 64, 0, 0, 64, 64)
            pal = dfast.app.util.CoverGlass.palette(bp, accent)
        }
        val n = 64
        fun bm(shift: Int): Bitmap = Bitmap.createBitmap(dfast.app.util.CoverGlass.mesh(pal, n, shift, 0.62f), n, n, Bitmap.Config.ARGB_8888)
        glass = kotlin.runCatching { Mesh(bm(0), bm(1)) }.getOrNull()
    }

    private fun lerpColor(a: Int, b: Int, f: Float): Int {
        fun ch(sh: Int) = (((a shr sh and 255) * (1f - f)) + ((b shr sh and 255) * f)).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun drawGlass(c: Canvas, m: Mesh?, p: IntArray, a: Float, w: Float, h: Float, now: Long, energy: Float, punch: Float) {
        if (a <= 0.01f || m == null) return
        val tt = (now % 1_000_000L) / 1000f
        // the mesh is a little larger than the screen top and drifts slowly, so the colours move without any redrawing of pixels
        val dx = w * 0.07f * sin(tt * 0.06f); val dy = h * 0.03f * sin(tt * 0.045f + 1f)
        val dst = RectF(-w * 0.2f + dx, -h * 0.08f + dy, w * 1.2f + dx, h * 0.78f + dy)
        val level = (0.62f + 0.22f * energy + 0.16f * punch).coerceIn(0f, 1f) * a
        bgPaint.alpha = (level * 255).toInt(); c.drawBitmap(m.a, null, dst, bgPaint)
        // the second mesh has the colours in another order; fading between them makes the colours slowly swap places (about 40 s a turn)
        bgPaint.alpha = (level * (0.5f + 0.5f * sin(tt * 0.16f)) * 255).toInt(); c.drawBitmap(m.b, null, dst, bgPaint)
        // on a beat the classic colour glow pulses: three soft blobs in the cover colours swell and brighten with the kick
        if (punch > 0.02f) {
            val tp = dfast.app.util.CoverGlass.tame(p)
            for (i in 0 until 3) {
                val bx = w * (0.5f + 0.42f * sin(tt * 0.09f + i * 2.1f))
                val by = h * (0.1f + 0.14f * i) + h * 0.03f * sin(tt * 0.11f + i)
                val br = w * (0.5f + 0.12f * i) * (1f + 0.18f * punch)
                val al = ((0.05f * energy + 0.32f * punch) * a).coerceIn(0f, 0.6f)
                val col = tp[i % tp.size] and 0x00FFFFFF
                paint.shader = RadialGradient(bx, by, br, intArrayOf(col or ((al * 255).toInt() shl 24), col), null, Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w, h * 0.75f, paint)
            }
            paint.shader = null
        }
    }

    // ---- beat release: an onset is shown when its SOUND is heard, not when the player processed it ----
    private val sync = dfast.app.util.BeatSync()
    private var btExtra = 0.0
    private var routeAt = 0L
    private var logAt = 0L
    private fun releaseOnsets(now: Long, playing: Boolean, live: Boolean) {
        val pl = playerProvider()
        sync.observe(AudioTap.flushGen, AudioTap.flushWallMs, now, AudioTap.fedMs, pl?.currentPosition ?: -1L, playing, pl?.playbackParameters?.speed ?: 1f)
        if (now - routeAt > 2000L) {
            routeAt = now
            btExtra = kotlin.runCatching { @Suppress("DEPRECATION") if ((context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager).isBluetoothA2dpOn) dfast.app.util.BeatSync.BLUETOOTH_EXTRA_MS else 0.0 }.getOrDefault(0.0)
        }
        val lead = sync.leadMs()
        while (true) {
            val h = AudioTap.onsets.peek() ?: break
            val fire = dfast.app.util.BeatSync.fireAtMs(h.baseWallMs, lead, btExtra, 0.0)
            if (fire > now) break
            AudioTap.onsets.poll()
            if (live && !saving && now - fire < 300) {
                dyn.onset(h.strength)
                android.util.Log.d("DFastBeat", "onset strength=${"%.2f".format(h.strength)} lead=${lead.toInt()}ms measured=${sync.trusted} bt=${btExtra.toInt()}ms late=${(now - fire).toInt()}ms")
            }
        }
        if (now - logAt > 5000L) { logAt = now; android.util.Log.d("DFastBeat", "sync lead=${lead.toInt()}ms measured=${sync.trusted} bt=${btExtra.toInt()}ms fed=${AudioTap.fedMs.toInt()}ms pos=${pl?.currentPosition} gen=${AudioTap.flushGen}") }
    }

    private fun drawRingScene(canvas: Canvas, w: Float, h: Float, now: Long, dt: Long, playing: Boolean, live: Boolean, t: Float) {
        updateSaving(now)
        // battery saver: the ring settles to its calm resting state and is redrawn once a second
        dyn.step(dt, AudioTap.bass, AudioTap.mid, AudioTap.high, live && !saving, if (saving) 0f else t)
        releaseOnsets(now, playing, live)
        if (playing) angle = (angle + dt * 0.03f) % 360f
        canvas.drawColor(0xFF0B0B10.toInt())
        val f = ((now - fadeStart) / 600f).coerceIn(0f, 1f)
        // song change: the new cover grows in with a small ease-out while the old one fades (about 0.6 s)
        val cf = ((now - thumbT) / 600f).coerceIn(0f, 1f); val ce = 1f - (1f - cf) * (1f - cf)
        val grow = if (thumb != null) 0.94f + 0.06f * ce else 1f
        val cx = w / 2; val cy = h * 0.30f
        val coverR = min(w * 0.3f, h * 0.22f) * 0.78f
        val zoom = 1f + 0.045f * dyn.punch
        canvas.save(); canvas.scale(zoom, zoom, cx, cy)
        drawGlass(canvas, glassPrev, palPrev, 1f - f, w, h, now, dyn.energy, dyn.punch)
        drawGlass(canvas, glass, pal, f, w, h, now, dyn.energy, dyn.punch)
        val sh = shade; if (sh != null) { paint.shader = sh; canvas.drawRect(0f, h * 0.42f, w, h, paint); paint.shader = null }
        // outline
        val breathe = if (playing) 0.012f * sin(t * 1.1f) else 0f
        val lobe = (dyn.lobe + breathe).coerceIn(0f, 1f); val rip = dyn.ripple
        val baseR = coverR * grow + d(9f) * (1f + (if (playing) 0.12f * sin(t * 1.25f) else 0f)); val amp = d(36f)
        val ph = 0.15f + 0.08f * sin(t * 0.3f)
        fun build(inward: Boolean) {
            ringPath.rewind()
            val n = 120
            for (i in 0..n) {
                val th = (i % n) * (2f * PI.toFloat() / n)
                val wing = max(0f, kotlin.math.cos(2f * th - ph)).let { it * it * it * it * it * it }
                val top = max(0f, kotlin.math.cos(th - 1.9f)).let { x -> val x2 = x * x; val x4 = x2 * x2; x4 * x4 * x2 }
                val r = if (inward) baseR - lobe * amp * 0.35f * wing * wing
                        else baseR + lobe * amp * wing + rip * amp * 0.35f * top + (0.35f + rip) * d(2.4f) * (sin(7f * th + t * 1.6f) + 0.6f * sin(4f * th - t * 0.9f))
                val x = cx + r * kotlin.math.cos(th); val y = cy + r * kotlin.math.sin(th)
                if (i == 0) ringPath.moveTo(x, y) else ringPath.lineTo(x, y)
            }
            ringPath.close()
        }
        val c0 = lerpColor(palPrev[0], pal[0], f)
        val glowK = if (playing) 1f else 0.75f + 0.25f * sin(t * 1.25f) // paused: a calm glow that slowly breathes
        if (lobe > 0.35f) { build(true); ringPaint.strokeWidth = d(3f); ringPaint.color = (pal[1] and 0x00FFFFFF) or (((lobe - 0.3f).coerceIn(0f, 0.7f) * 255).toInt() shl 24); canvas.drawPath(ringPath, ringPaint) }
        build(false)
        ringPaint.strokeWidth = d(11f); ringPaint.color = (c0 and 0x00FFFFFF) or ((0x22 * glowK).toInt() shl 24); canvas.drawPath(ringPath, ringPaint)
        ringPaint.strokeWidth = d(6f); ringPaint.color = (c0 and 0x00FFFFFF) or ((0x66 * glowK).toInt() shl 24); canvas.drawPath(ringPath, ringPaint)
        ringPaint.strokeWidth = d(3f); ringPaint.color = c0; canvas.drawPath(ringPath, ringPaint)
        ringPaint.strokeWidth = d(1.4f); ringPaint.color = 0xFFFFF4EC.toInt(); canvas.drawPath(ringPath, ringPaint)
        // shock waves: every beat sends a thin ring out of the cover that travels outward and fades (up to 3 at a time)
        for (wi in 0 until 3) {
            val pr = dyn.waveProgress(wi)
            if (pr < 0f || pr >= 1f) continue
            val e = 1f - (1f - pr) * (1f - pr)
            val al = ((1f - pr) * (1f - pr) * (0.25f + 0.55f * dyn.waveStrength(wi)) * 255).toInt().coerceIn(0, 255)
            ringPaint.strokeWidth = d(2.2f) * (1f - 0.5f * pr); ringPaint.color = (c0 and 0x00FFFFFF) or (al shl 24)
            canvas.drawCircle(cx, cy, baseR + d(6f) + e * d(26f + 22f * dyn.waveStrength(wi)), ringPaint)
        }
        // cover
        val cr = coverR * grow * (1f + dyn.punch * 0.03f)
        paint.color = 0xFF0C0C10.toInt(); canvas.drawCircle(cx, cy, cr + d(4f), paint)
        val bmp = thumb
        val pv = thumbPrev
        val pvA = if (pv != null) 1f - ((now - prevT) / 450f).coerceIn(0f, 1f) else 0f
        if (pv != null && pvA <= 0f) thumbPrev = null
        if (bmp == null || cf < 1f) { paint.color = accent; paint.alpha = if (bmp == null) 255 else ((1f - ce) * 255).toInt(); canvas.drawCircle(cx, cy, cr, paint); paint.alpha = 255 }
        val path = Path().apply { addCircle(cx, cy, cr, Path.Direction.CW) }
        canvas.save(); canvas.clipPath(path)
        if (pv != null && pvA > 0f) { shader.alpha = (pvA * 255).toInt(); canvas.drawBitmap(pv, Rect(0, 0, pv.width, pv.height), RectF(cx - cr, cy - cr, cx + cr, cy + cr), shader) }
        if (bmp != null) { shader.alpha = (ce * 255).toInt(); canvas.drawBitmap(bmp, Rect(0, 0, bmp.width, bmp.height), RectF(cx - cr, cy - cr, cx + cr, cy + cr), shader); }
        shader.alpha = 255
        canvas.restore()
        canvas.restore()
        // controls: a soft pool of the main cover colour behind the play button, so the strip is part of the glass, not a flat block
        canvas.save(); canvas.clipRect(0f, h * 0.70f, w, h)
        canvas.scale(1f, 0.55f, w / 2, h * 0.88f)
        val pc = c0 and 0x00FFFFFF
        paint.shader = RadialGradient(w / 2, h * 0.88f, w * 0.85f, intArrayOf(pc or ((((0.34f + 0.10f * dyn.punch) * 255).toInt()) shl 24), pc), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h * 2f, paint); paint.shader = null
        canvas.restore()
    }

    private fun drawSkip(c: Canvas, x: Float, y: Float, next: Boolean) {
        val s = d(10f)
        val dir = if (next) 1f else -1f
        val path = Path().apply { moveTo(x - dir * s, y - s); lineTo(x + dir * s * 0.6f, y); lineTo(x - dir * s, y + s); close() }
        c.drawPath(path, paint)
        c.drawRect(minOf(x + dir * s * 0.8f, x + dir * s * 1.2f), y - s, maxOf(x + dir * s * 0.8f, x + dir * s * 1.2f), y + s, paint)
    }
    /** outline icon in the current paint colour (grey off, purple on); a small dot under it when on */
    private fun drawIc(c: Canvas, res: Int, x: Float, y: Float, on: Boolean) {
        val dr = androidx.core.content.ContextCompat.getDrawable(context, res)?.mutate() ?: return
        val s = d(15f).toInt()
        dr.setBounds((x - s).toInt(), (y - s).toInt(), (x + s).toInt(), (y + s).toInt())
        dr.setTint(paint.color); dr.draw(c)
        if (on) { paint.style = Paint.Style.FILL; c.drawCircle(x, y + s + d(7f), d(2.2f), paint) }
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
