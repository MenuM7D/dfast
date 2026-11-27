package dfast.app.ui.play

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.Player
import dfast.app.R
import dfast.app.util.TapSkip
import dfast.app.util.fmtClock
import dfast.app.util.skipTarget
import kotlin.math.abs

/**
 * The player's own controls (design A, the dock): top gear, a rounded dock at the bottom with shuffle, previous,
 * play/pause, next, repeat, a seek bar with times and the fullscreen button, plus double-tap to skip 10 s.
 * It only reads and seeks the player it is given; it never touches the queue, the service or the notification.
 * The dock itself is always left-to-right (like every media control); only the double-tap sides follow the layout direction.
 */
class PlayerDock(c: Context) : FrameLayout(c) {
    var playerProvider: () -> Player? = { null }
    var onPrev: () -> Unit = {}
    var onNext: () -> Unit = {}
    var onShuffle: () -> Unit = {}
    var onRepeat: () -> Unit = {}
    var onPlayPause: () -> Unit = {}
    var onSettings: (View) -> Unit = {}
    var onFullscreen: () -> Unit = {}
    var canPrev: () -> Boolean = { true }
    var rtl: () -> Boolean = { false }

    var shuffleOn = false
        set(v) { field = v; refreshStates() }
    var repeatIdx = 0
        set(v) { field = v; refreshStates() }
    var fullscreen = false
        set(v) { field = v; refreshStates() }
    /** the fullscreen button only makes sense for a video */
    var fullscreenAllowed = true
        set(v) { field = v; refreshStates() }

    private val accent = 0xFF9D86FF.toInt()
    private val handler = Handler(Looper.getMainLooper())
    private val tapSkip = TapSkip()
    private var shown = true
    private var lastInteract = SystemClock.uptimeMillis()
    private val hideDelay = 3500L

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun icon(res: Int, sizeDp: Int, tint: Int = Color.WHITE): ImageView = ImageView(context).apply {
        setImageResource(res); setColorFilter(tint); scaleType = ImageView.ScaleType.FIT_CENTER
        val pad = dp(sizeDp / 5); setPadding(pad, pad, pad, pad)
    }

    private val gear = icon(R.drawable.ic_pd_settings_2, 40).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x66000000) }
        setOnClickListener { poke(); onSettings(it) }
    }
    private val shuffleBtn = icon(R.drawable.ic_pd_shuffle, 40)
    private val prevBtn = icon(R.drawable.ic_pd_skip_back, 44)
    private val playBtn = icon(R.drawable.ic_pd_play, 56, 0xFF14141C.toInt())
    private val nextBtn = icon(R.drawable.ic_pd_skip_forward, 44)
    private val repeatBtn = icon(R.drawable.ic_pd_repeat, 40)
    private val fsBtn = icon(R.drawable.ic_pd_maximize, 36)
    private val curTime = TextView(context).apply { setTextColor(Color.WHITE); textSize = 12f; maxLines = 1; text = "0:00" }
    private val durTime = TextView(context).apply { setTextColor(0xFFB0B0BE.toInt()); textSize = 12f; maxLines = 1; text = "0:00" }
    private val seek = DockSeek(context, accent)
    private val panel = LinearLayout(context)
    private val skipFx = TextView(context)

    init {
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        isClickable = true
        addView(gear, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.RIGHT).apply { setMargins(0, dp(10), dp(10), 0) })

        panel.orientation = LinearLayout.VERTICAL
        panel.isClickable = true
        panel.setPadding(dp(14), dp(8), dp(14), dp(6))
        panel.background = GradientDrawable().apply { cornerRadius = dp(26).toFloat(); setColor(0xD8181820.toInt()) }
        val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        fun slot(v: View, w: Int, h: Int) = row.addView(FrameLayout(context).apply { addView(v, FrameLayout.LayoutParams(w, h, Gravity.CENTER)) }, LinearLayout.LayoutParams(0, dp(58), 1f))
        slot(shuffleBtn, dp(40), dp(40)); slot(prevBtn, dp(44), dp(44)); slot(playBtn, dp(56), dp(56)); slot(nextBtn, dp(44), dp(44)); slot(repeatBtn, dp(40), dp(40))
        playBtn.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
        panel.addView(row, LinearLayout.LayoutParams(-1, -2))
        val srow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        srow.addView(curTime, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        srow.addView(seek, LinearLayout.LayoutParams(0, dp(28), 1f))
        srow.addView(durTime, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        srow.addView(fsBtn, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(4) })
        panel.addView(srow, LinearLayout.LayoutParams(-1, -2))
        addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { setMargins(dp(12), 0, dp(12), dp(10)) })

        skipFx.apply {
            setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER; visibility = View.GONE; maxLines = 1
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(0x80000000.toInt()) }
            setPadding(dp(18), dp(10), dp(18), dp(10)); compoundDrawablePadding = dp(2)
        }
        addView(skipFx, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.LEFT))

        shuffleBtn.setOnClickListener { poke(); onShuffle() }
        prevBtn.setOnClickListener { poke(); if (canPrev()) onPrev() }
        nextBtn.setOnClickListener { poke(); onNext() }
        repeatBtn.setOnClickListener { poke(); onRepeat() }
        playBtn.setOnClickListener { poke(); onPlayPause() }
        fsBtn.setOnClickListener { poke(); onFullscreen() }
        seek.onSeek = { frac -> val p = playerProvider(); if (p != null && p.duration > 0) p.seekTo((p.duration * frac).toLong()); poke() }
        seek.onDrag = { frac -> poke(); val p = playerProvider(); if (p != null && p.duration > 0) curTime.text = fmtClock((p.duration * frac).toLong()) }
        refreshStates()
    }

    /** the dock never gets wider than a comfortable control strip, even on a big landscape screen */
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val lp = panel.layoutParams as FrameLayout.LayoutParams
        val want = minOf(w - dp(24), dp(560)).coerceAtLeast(dp(200))
        if (lp.width != want) { lp.width = want; panel.layoutParams = lp }
    }

    private fun poke() { lastInteract = SystemClock.uptimeMillis() }

    fun refreshStates() {
        fun tintBtn(b: ImageView, on: Boolean) { b.setColorFilter(if (on) accent else Color.WHITE) }
        tintBtn(shuffleBtn, shuffleOn)
        shuffleBtn.contentDescription = "shuffle"
        repeatBtn.setImageResource(if (repeatIdx == 2) R.drawable.ic_pd_repeat_1 else R.drawable.ic_pd_repeat)
        tintBtn(repeatBtn, repeatIdx != 0)
        fsBtn.setImageResource(if (fullscreen) R.drawable.ic_pd_minimize else R.drawable.ic_pd_maximize)
        fsBtn.visibility = if (fullscreenAllowed) View.VISIBLE else View.GONE
        val prevOk = canPrev()
        prevBtn.alpha = if (prevOk) 1f else 0.35f
        prevBtn.isEnabled = prevOk
    }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, 400)
        }
    }

    private fun update() {
        val p = playerProvider()
        val playing = p != null && p.playWhenReady && p.playbackState != Player.STATE_ENDED && p.playbackState != Player.STATE_IDLE
        playBtn.setImageResource(if (playing) R.drawable.ic_pd_pause else R.drawable.ic_pd_play)
        playBtn.setColorFilter(0xFF14141C.toInt())
        val prevOk = canPrev()
        if (prevBtn.isEnabled != prevOk) { prevBtn.isEnabled = prevOk; prevBtn.alpha = if (prevOk) 1f else 0.35f }
        if (p != null && !seek.isDragging()) {
            val dur = p.duration.takeIf { it > 0 } ?: 0L
            seek.set(if (dur > 0) p.currentPosition.toFloat() / dur else 0f, if (dur > 0) p.bufferedPosition.toFloat() / dur else 0f)
            curTime.text = fmtClock(p.currentPosition); durTime.text = if (dur > 0) fmtClock(dur) else "--:--"
        }
        // auto-hide only while really playing; paused, ended or loading keeps the dock on screen
        val really = p != null && p.isPlaying
        if (!shown) { if (!really) setControls(true) }
        else if (really && SystemClock.uptimeMillis() - lastInteract > hideDelay && !seek.isDragging()) setControls(false)
    }

    fun setControls(on: Boolean) {
        shown = on
        val v = if (on) View.VISIBLE else View.GONE
        panel.visibility = v; gear.visibility = v
        if (on) lastInteract = SystemClock.uptimeMillis()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); handler.removeCallbacks(tick); handler.post(tick) }
    override fun onDetachedFromWindow() { handler.removeCallbacksAndMessages(null); super.onDetachedFromWindow() }

    // --- taps on the video itself: single tap shows/hides, double tap on a side skips 10 s, and keeps stacking ---
    private var downX = 0f; private var downY = 0f; private var downT = 0L; private var moved = false
    private val toggleRun = Runnable { setControls(!shown) }
    private val fxHide = Runnable { skipFx.visibility = View.GONE }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; downT = e.eventTime; moved = false; return true }
            MotionEvent.ACTION_MOVE -> { if (abs(e.x - downX) > dp(16) || abs(e.y - downY) > dp(16)) moved = true; return true }
            MotionEvent.ACTION_CANCEL -> return true
            MotionEvent.ACTION_UP -> {
                if (moved || e.eventTime - downT > ViewConfiguration.getLongPressTimeout() || width == 0) return true
                handleTap(e.x / width)
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun handleTap(xFrac: Float) {
        val now = SystemClock.uptimeMillis()
        when (val r = tapSkip.tap(xFrac, rtl(), now)) {
            is TapSkip.R.Pending -> { handler.removeCallbacks(toggleRun); handler.postDelayed(toggleRun, 300) }
            is TapSkip.R.Skip -> {
                handler.removeCallbacks(toggleRun)
                val p = playerProvider() ?: return
                p.seekTo(skipTarget(p.currentPosition, r.deltaMs, r.forward, p.duration))
                poke()
                showFx(r)
            }
        }
    }

    private fun showFx(r: TapSkip.R.Skip) {
        val onRight = r.side == TapSkip.Side.RIGHT
        val lp = skipFx.layoutParams as FrameLayout.LayoutParams
        lp.gravity = Gravity.CENTER_VERTICAL or (if (onRight) Gravity.RIGHT else Gravity.LEFT)
        lp.setMargins(if (onRight) 0 else dp(36), 0, if (onRight) dp(36) else 0, 0)
        skipFx.layoutParams = lp
        skipFx.text = (if (r.forward) "+" else "-") + (r.totalMs / 1000)
        val d = androidx.core.content.ContextCompat.getDrawable(context, if (r.forward) R.drawable.ic_pd_skip_forward else R.drawable.ic_pd_skip_back)?.mutate()
        d?.setBounds(0, 0, dp(26), dp(26)); d?.setTint(Color.WHITE)
        skipFx.setCompoundDrawables(null, d, null, null)
        skipFx.visibility = View.VISIBLE
        handler.removeCallbacks(fxHide); handler.postDelayed(fxHide, 800)
    }

    /** thin seek bar: track, buffered, played, knob; drag shows the time, the seek happens on release */
    private class DockSeek(c: Context, private val accent: Int) : View(c) {
        var onSeek: (Float) -> Unit = {}
        var onDrag: (Float) -> Unit = {}
        private var frac = 0f; private var buf = 0f
        private var dragging = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        fun isDragging() = dragging
        fun set(f: Float, b: Float) { if (dragging) return; frac = f.coerceIn(0f, 1f); buf = b.coerceIn(0f, 1f); invalidate() }
        private fun d(n: Float) = n * resources.displayMetrics.density
        override fun onDraw(cv: Canvas) {
            val l = d(6f); val r = width - d(6f); val cy = height / 2f; val th = d(3f)
            paint.color = 0x40FFFFFF; rect.set(l, cy - th / 2, r, cy + th / 2); cv.drawRoundRect(rect, th, th, paint)
            paint.color = 0x66FFFFFF; rect.set(l, cy - th / 2, l + (r - l) * buf, cy + th / 2); cv.drawRoundRect(rect, th, th, paint)
            paint.color = accent; val x = l + (r - l) * frac; rect.set(l, cy - th / 2, x, cy + th / 2); cv.drawRoundRect(rect, th, th, paint)
            cv.drawCircle(x, cy, if (dragging) d(9f) else d(6f), paint)
        }
        private fun fracAt(x: Float): Float { val l = d(6f); val r = width - d(6f); return ((x - l) / (r - l)).coerceIn(0f, 1f) }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); dragging = true; frac = fracAt(e.x); onDrag(frac); invalidate(); return true }
                MotionEvent.ACTION_MOVE -> { frac = fracAt(e.x); onDrag(frac); invalidate(); return true }
                MotionEvent.ACTION_UP -> { frac = fracAt(e.x); dragging = false; onSeek(frac); invalidate(); return true }
                MotionEvent.ACTION_CANCEL -> { dragging = false; invalidate(); return true }
            }
            return super.onTouchEvent(e)
        }
    }
}
