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
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.Player
import dfast.app.R
import dfast.app.util.TapSkip
import dfast.app.util.fmtClock
import dfast.app.util.skipTarget
import kotlin.math.abs

/**
 * The player's own controls, design A (light, YouTube-like): a small collapse chevron, gear and download on the top row,
 * previous / play / next in the middle (the loading ring replaces the play icon, so it never covers a control), time and a thin
 * progress bar at the bottom with the fullscreen button; fullscreen also shows the title, channel and favorite / playlist / share.
 * Shuffle and repeat live in the gear panel. It only reads and seeks the player it is given; it never touches the queue,
 * the service or the notification. The top row follows the layout direction (the chevron sits at the start side);
 * the transport row and seek bar stay left-to-right like every media control; the double-tap sides are fixed: right = forward, left = back, in every language.
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
    var onBack: () -> Unit = {}
    var onDownload: () -> Unit = {}
    var onFavorite: () -> Unit = {}
    var onPlaylist: () -> Unit = {}
    var onShare: () -> Unit = {}
    var canPrev: () -> Boolean = { true }
    var rtl: () -> Boolean = { false }
    /** title and channel (shown in fullscreen) and the favorite state, read on every refresh */
    var infoProvider: () -> Triple<String, String, Boolean> = { Triple("", "", false) }

    /** shuffle / repeat state is kept here only so the activity can share one setter with the audio view */
    var shuffleOn = false
    var repeatIdx = 0
    var fullscreen = false
        set(v) { field = v; refreshStates() }
    /** the fullscreen button only makes sense for a video */
    var fullscreenAllowed = true
        set(v) { field = v; refreshStates() }
    /** buffering: the ring takes the place of the play icon */
    var loading = false
        set(v) { if (field != v) { field = v; refreshStates(); update() } }

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

    private val backBtn = icon(R.drawable.ic_pd_chevron_down, 44)
    private val gear = icon(R.drawable.ic_pd_settings_2, 44)
    private val dlBtn = icon(R.drawable.ic_pd_download, 44)
    private val titleTv = TextView(context).apply { setTextColor(Color.WHITE); textSize = 15f; setTypeface(typeface, android.graphics.Typeface.BOLD) }
    private val chanTv = TextView(context).apply { setTextColor(0xFFD8D8E2.toInt()); textSize = 12f }
    private val titleBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
    private val prevBtn = icon(R.drawable.ic_pd_skip_back, 52)
    private val playBtn = icon(R.drawable.ic_pd_play, 72)
    private val nextBtn = icon(R.drawable.ic_pd_skip_forward, 52)
    private val ring = ProgressBar(context).apply {
        isIndeterminate = true; visibility = View.GONE
        indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
    }
    private val favBtn = icon(R.drawable.ic_pd_heart, 40)
    private val plBtn = icon(R.drawable.ic_pd_list_plus, 40)
    private val shBtn = icon(R.drawable.ic_pd_share, 40)
    private val acts = LinearLayout(context).apply { visibility = View.GONE }
    private val fsBtn = icon(R.drawable.ic_pd_maximize, 40)
    private val timeTv = TextView(context).apply { setTextColor(Color.WHITE); textSize = 12f; text = "0:00 / 0:00" }
    private val seek = DockSeek(context, accent)
    private val top = LinearLayout(context)
    private val spacer = View(context)
    private val center = FrameLayout(context)
    private val bottom = LinearLayout(context)
    private val skipFx = TextView(context)
    private var lastInfo = Triple("", "", false)

    private fun scrim(up: Boolean) = GradientDrawable(
        if (up) GradientDrawable.Orientation.TOP_BOTTOM else GradientDrawable.Orientation.BOTTOM_TOP,
        intArrayOf(0xAA000000.toInt(), 0x00000000))

    init {
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        isClickable = true

        // top row: chevron at the start side, gear + download at the end side (mirrors with the language)
        top.layoutDirection = if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        top.gravity = Gravity.CENTER_VERTICAL
        top.background = scrim(true)
        top.setPadding(dp(8), dp(6), dp(8), dp(18))
        top.addView(backBtn, LinearLayout.LayoutParams(dp(44), dp(44)))
        titleBox.addView(titleTv, LinearLayout.LayoutParams(-1, -2)); titleBox.addView(chanTv, LinearLayout.LayoutParams(-1, -2))
        top.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6); marginEnd = dp(6) })
        top.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(dlBtn, LinearLayout.LayoutParams(dp(44), dp(44)))
        top.addView(gear, LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // centre: previous, play (or the ring), next
        val row = LinearLayout(context).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL }
        prevBtn.alpha = 0.75f; nextBtn.alpha = 0.75f
        row.addView(prevBtn, LinearLayout.LayoutParams(dp(52), dp(52)))
        val playHolder = FrameLayout(context)
        playBtn.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x66000000) }
        playHolder.addView(playBtn, FrameLayout.LayoutParams(dp(68), dp(68), Gravity.CENTER))
        playHolder.addView(ring, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER))
        row.addView(playHolder, LinearLayout.LayoutParams(dp(76), dp(76)).apply { setMargins(dp(22), 0, dp(22), 0) })
        row.addView(nextBtn, LinearLayout.LayoutParams(dp(52), dp(52)))
        center.addView(row, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        addView(center, FrameLayout.LayoutParams(-1, -1))

        // bottom: time (left) and actions + fullscreen (right), then the thin progress bar
        bottom.orientation = LinearLayout.VERTICAL
        bottom.background = scrim(false)
        bottom.setPadding(dp(10), dp(26), dp(10), dp(2))
        val info = LinearLayout(context).apply { gravity = Gravity.BOTTOM }
        info.addView(timeTv, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6); bottomMargin = dp(10) })
        acts.addView(favBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
        acts.addView(plBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
        acts.addView(shBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
        info.addView(acts, LinearLayout.LayoutParams(-2, dp(40)).apply { marginEnd = dp(8) })
        info.addView(fsBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
        bottom.addView(info, LinearLayout.LayoutParams(-1, -2))
        bottom.addView(seek, LinearLayout.LayoutParams(-1, dp(24)))
        addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        skipFx.apply {
            setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER; visibility = View.GONE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(0x80000000.toInt()) }
            setPadding(dp(18), dp(10), dp(18), dp(10)); compoundDrawablePadding = dp(2)
        }
        addView(skipFx, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.LEFT))

        backBtn.setOnClickListener { poke(); onBack() }
        gear.setOnClickListener { poke(); onSettings(it) }
        dlBtn.setOnClickListener { poke(); onDownload() }
        favBtn.setOnClickListener { poke(); onFavorite() }
        plBtn.setOnClickListener { poke(); onPlaylist() }
        shBtn.setOnClickListener { poke(); onShare() }
        prevBtn.setOnClickListener { poke(); if (canPrev()) onPrev() }
        nextBtn.setOnClickListener { poke(); onNext() }
        playBtn.setOnClickListener { poke(); onPlayPause() }
        fsBtn.setOnClickListener { poke(); onFullscreen() }
        seek.onSeek = { frac -> val p = playerProvider(); if (p != null && p.duration > 0) p.seekTo((p.duration * frac).toLong()); poke() }
        seek.onDrag = { frac -> poke(); val p = playerProvider(); if (p != null && p.duration > 0) timeTv.text = fmtClock((p.duration * frac).toLong()) + " / " + fmtClock(p.duration) }
        refreshStates()
    }

    private fun poke() { lastInteract = SystemClock.uptimeMillis() }

    fun refreshStates() {
        val info = infoProvider()
        lastInfo = info
        titleTv.text = info.first; chanTv.text = info.second
        favBtn.setImageResource(if (info.third) R.drawable.ic_pd_heart_fill else R.drawable.ic_pd_heart)
        favBtn.setColorFilter(if (info.third) accent else Color.WHITE)
        titleBox.visibility = if (fullscreen) View.VISIBLE else View.GONE
        spacer.visibility = if (fullscreen) View.GONE else View.VISIBLE
        acts.visibility = if (fullscreen) View.VISIBLE else View.GONE
        fsBtn.setImageResource(if (fullscreen) R.drawable.ic_pd_minimize else R.drawable.ic_pd_maximize)
        fsBtn.visibility = if (fullscreenAllowed) View.VISIBLE else View.GONE
        val prevOk = canPrev()
        prevBtn.alpha = if (prevOk) 0.75f else 0.3f
        prevBtn.isEnabled = prevOk
        ring.visibility = if (loading) View.VISIBLE else View.GONE
        playBtn.visibility = if (loading) View.INVISIBLE else View.VISIBLE
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
        playBtn.setColorFilter(Color.WHITE)
        val info = infoProvider()
        if (info != lastInfo) refreshStates()
        val prevOk = canPrev()
        if (prevBtn.isEnabled != prevOk) { prevBtn.isEnabled = prevOk; prevBtn.alpha = if (prevOk) 0.75f else 0.3f }
        if (p != null && !seek.isDragging()) {
            val dur = p.duration.takeIf { it > 0 } ?: 0L
            seek.set(if (dur > 0) p.currentPosition.toFloat() / dur else 0f, if (dur > 0) p.bufferedPosition.toFloat() / dur else 0f)
            timeTv.text = fmtClock(p.currentPosition) + " / " + (if (dur > 0) fmtClock(dur) else "--:--")
        }
        // auto-hide only while really playing; paused, ended or loading keeps the controls on screen
        val really = p != null && p.isPlaying
        if (!shown) { if (!really) setControls(true) }
        else if (really && SystemClock.uptimeMillis() - lastInteract > hideDelay && !seek.isDragging()) setControls(false)
    }

    fun setControls(on: Boolean) {
        shown = on
        val v = if (on) View.VISIBLE else View.GONE
        top.visibility = v; center.visibility = v; bottom.visibility = v
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
