package dfast.app.util

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.Player
import dfast.app.R
import dfast.app.services.PlaybackService
import dfast.app.ui.play.OfflineActivity
import dfast.app.util.Extensions.loadThumbnail

/** S2 + Q2: edge-snapping half-hidden bubble, expands to a pill, queue opens as a slide-up sheet. Lives inside DFast. */
class MiniPlayer(private val activity: Activity) {
    private val d = activity.resources.displayMetrics.density
    private fun dp(n: Int) = (n * d).toInt()
    private val host: FrameLayout = activity.findViewById(android.R.id.content)
    private val bubble = ImageView(activity)
    private val pill = LinearLayout(activity)
    private val pillTitle = TextView(activity)
    private val pillPlay = ImageButton(activity)
    private val pillSpin = android.widget.ProgressBar(activity)
    private val bubbleSpin = android.widget.ProgressBar(activity)
    private val sheet = LinearLayout(activity)
    /** dims the app behind the queue sheet and swallows taps, so the sheet is modal; a tap on it closes the sheet */
    private val scrim = View(activity)
    private val backCb = object : androidx.activity.OnBackPressedCallback(false) { override fun handleOnBackPressed() { toggleSheet(false) } }
    private val sheetList = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val queueTitle = TextView(activity)
    private var repeatChip: TextView? = null
    private val undoBar = TextView(activity)
    private var undoItems: List<Pair<Int, androidx.media3.common.MediaItem>> = emptyList()

    private fun offerUndo(items: List<Pair<Int, androidx.media3.common.MediaItem>>) {
        undoItems = items
        undoBar.text = activity.getString(R.string.o_removed) + " (" + items.size + ")   ·   " + activity.getString(R.string.o_undo)
        undoBar.visibility = View.VISIBLE
        undoBar.removeCallbacks(hideUndo); undoBar.postDelayed(hideUndo, 6000)
    }
    private val hideUndo = Runnable { undoBar.visibility = View.GONE; undoItems = emptyList() }
    private var attached: Player? = null
    private var expanded = false
    private var onLeft = false
    private var lastArt: String? = null
    private var bubbleY = -1f

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
    }
    private var released = false
    private var lastRestore = 0L
    private fun startedNow() = (activity as? androidx.lifecycle.LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) != false

    /** Puts the saved queue back when the playback service is gone (killed, stopped while paused, process restart). Throttled so a bad save cannot loop. */
    private fun tryRestore() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRestore < 5000) return
        lastRestore = now
        PlaybackService.restore(activity)
    }

    private val serviceListener: () -> Unit = {
        activity.runOnUiThread {
            attach()
            // the service ended but the queue is still saved (only the x clears it): bring the bubble back instead of letting it vanish
            if (PlaybackService.player == null) activity.window.decorView.postDelayed({
                if (!released && PlaybackService.player == null && startedNow()) { tryRestore() }
                if (!released) attach()
            }, 700)
        }
    }

    private val stateListener: () -> Unit = { activity.runOnUiThread { if (!released) refresh() } }

    private val startObserver = androidx.lifecycle.LifecycleEventObserver { _, e ->
        if (e == androidx.lifecycle.Lifecycle.Event.ON_START && !released) { tryRestore(); attach() }
    }

    private fun ink() = OrbitColors.ink(activity)

    private fun iconBtn(res: Int, desc: String, onClick: () -> Unit) = ImageButton(activity).apply {
        setImageResource(res); setBackgroundColor(Color.TRANSPARENT); contentDescription = desc
        setColorFilter(ink()); setOnClickListener { onClick() }; Press.icon(this)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun build() {
        // hide the legacy views from the layout
        listOf(R.id.mini_player, R.id.mini_bubble).forEach { activity.findViewById<View>(it)?.visibility = View.GONE }

        bubble.apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF1B1B22.toInt()) }
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            elevation = 10 * d; visibility = View.GONE; setImageResource(R.drawable.ic_music)
        }
        host.addView(bubble, FrameLayout.LayoutParams(dp(56), dp(56)))
        bubbleSpin.apply { isIndeterminate = true; visibility = View.GONE; elevation = 11 * d; isClickable = false; indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE) }
        host.addView(bubbleSpin, FrameLayout.LayoutParams(dp(56), dp(56)))

        pill.apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; elevation = 12 * d
            setPadding(dp(8), dp(4), dp(8), dp(4)); visibility = View.GONE
            background = GradientDrawable().apply { cornerRadius = 1000f; setColor(OrbitColors.get(activity, R.attr.orbitDeepGlass, 0xF2151520.toInt()).let { it or 0xFF000000.toInt() }); setStroke(dp(1), OrbitColors.get(activity, R.attr.orbitStroke, 0x33FFFFFF)) }
        }
        pillTitle.apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; textSize = 13f; setTextColor(ink()); setPadding(dp(10), 0, dp(6), 0) }
        pill.addView(pillTitle, LinearLayout.LayoutParams(dp(110), -2))
        pill.addView(iconBtn(R.drawable.ic_mini_prev, "Previous") { PlaybackService.player?.let { PlayQueue.previous(it) } }, LinearLayout.LayoutParams(dp(36), dp(40)))
        pillPlay.apply { setBackgroundColor(Color.TRANSPARENT); setColorFilter(ink()); setOnClickListener { PlaybackService.player?.let { PlayQueue.togglePlay(it) }; refresh() } }
        pillSpin.apply { isIndeterminate = true; visibility = View.GONE; indeterminateTintList = android.content.res.ColorStateList.valueOf(ink()); isClickable = false }
        val playBox = FrameLayout(activity).apply {
            addView(pillPlay, FrameLayout.LayoutParams(-1, -1))
            addView(pillSpin, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        }
        pill.addView(playBox, LinearLayout.LayoutParams(dp(40), dp(40)))
        pill.addView(iconBtn(R.drawable.ic_mini_next, "Next") { PlaybackService.player?.seekToNextMediaItem() }, LinearLayout.LayoutParams(dp(36), dp(40)))
        pill.addView(iconBtn(R.drawable.ic_pl_playlist, activity.getString(R.string.o_queue)) { toggleSheet(true) }, LinearLayout.LayoutParams(dp(36), dp(40)))
        pill.addView(iconBtn(R.drawable.ic_mini_close, activity.getString(R.string.mini_close)) { expanded = false; toggleSheet(false); PlaybackService.stop(activity) }, LinearLayout.LayoutParams(dp(36), dp(40)))
        host.addView(pill, FrameLayout.LayoutParams(-2, dp(52)))

        // Q2 queue sheet
        sheet.apply {
            orientation = LinearLayout.VERTICAL; elevation = 20 * d; visibility = View.GONE
            setPadding(dp(14), dp(10), dp(14), dp(14))
            background = GradientDrawable().apply { cornerRadii = floatArrayOf(28 * d, 28 * d, 28 * d, 28 * d, 0f, 0f, 0f, 0f); setColor(0xFF14141C.toInt()) }
            isClickable = true
        }
        val head = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(queueTitle.apply { text = activity.getString(R.string.o_queue); textSize = 17f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(TextView(activity).apply {
            text = activity.getString(R.string.o_add_songs); setTextColor(0xFF14141C.toInt()); textSize = 13f
            setPadding(dp(14), dp(7), dp(14), dp(7)); background = GradientDrawable().apply { cornerRadius = 1000f; setColor(Color.WHITE) }
            setOnClickListener { toggleSheet(false); activity.startActivity(Intent(activity, dfast.app.ui.play.AddSongsActivity::class.java)) }
        })
        head.addView(iconBtn(R.drawable.ic_mini_close, activity.getString(R.string.mini_close)) { toggleSheet(false) }.apply { setColorFilter(Color.WHITE) }, LinearLayout.LayoutParams(dp(40), dp(40)))
        sheet.addView(head)
        val ctl = LinearLayout(activity).apply { setPadding(0, dp(8), 0, dp(4)) }
        fun chip(label: String, icon: Int, click: () -> Unit) = TextView(activity).apply {
            text = label; textSize = 12f; setTextColor(Color.WHITE); setPadding(dp(14), dp(7), dp(14), dp(7)); gravity = Gravity.CENTER_VERTICAL
            OIcons.start(this, icon, Color.WHITE, 16, 6)
            background = GradientDrawable().apply { cornerRadius = 1000f; setColor(0xFF2A2A33.toInt()) }
            setOnClickListener { click() }
        }
        ctl.addView(chip(activity.getString(R.string.o_shuffle_queue), R.drawable.ic_o_shuffle) { PlayQueue.shuffleUpNext(); fillQueue() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        repeatChip = chip(activity.getString(R.string.o_repeat_mode), R.drawable.ic_o_repeat) {
            attached?.let { it.repeatMode = when (it.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF } }
            fillQueue()
        }
        ctl.addView(repeatChip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        ctl.addView(chip(activity.getString(R.string.o_clear_queue), R.drawable.ic_o_delete) {
            val p = attached ?: return@chip
            val cur = p.currentMediaItemIndex
            val gone = ArrayList<Pair<Int, androidx.media3.common.MediaItem>>()
            for (i in p.mediaItemCount - 1 downTo 0) if (i != cur) { gone.add(0, i to p.getMediaItemAt(i)); p.removeMediaItem(i) }
            if (gone.isNotEmpty()) offerUndo(gone)
            fillQueue()
        })
        sheet.addView(ctl)
        sheet.addView(ScrollView(activity).apply { addView(sheetList) }, LinearLayout.LayoutParams(-1, 0, 1f))
        undoBar.apply {
            visibility = View.GONE; textSize = 13f; setTextColor(0xFF14141C.toInt()); gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply { cornerRadius = 1000f; setColor(0xFF9D86FF.toInt()) }
            setOnClickListener {
                undoItems.forEach { (i, m) -> PlayQueue.reinsert(m, i) }
                undoBar.removeCallbacks(hideUndo); hideUndo.run(); fillQueue()
            }
        }
        sheet.addView(undoBar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        scrim.apply { setBackgroundColor(0x99000000.toInt()); visibility = View.GONE; alpha = 0f; elevation = 18 * d; isClickable = true; isFocusable = false; setOnClickListener { toggleSheet(false) } }
        host.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        host.addView(sheet, FrameLayout.LayoutParams(-1, (activity.resources.displayMetrics.heightPixels * 0.55f).toInt(), Gravity.BOTTOM))
        (activity as? androidx.activity.ComponentActivity)?.onBackPressedDispatcher?.addCallback(backCb)

        // drag + edge snap
        var dX = 0f; var dY = 0f; var downX = 0f; var downY = 0f; var moved = false
        bubble.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { v.animate().cancel(); dX = v.x - e.rawX; dY = v.y - e.rawY; downX = e.rawX; downY = e.rawY; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(e.rawX - downX) > 12 || Math.abs(e.rawY - downY) > 12) moved = true
                    if (moved) { v.x = e.rawX + dX; v.y = (e.rawY + dY).coerceIn(dp(60).toFloat(), (host.height - dp(140)).toFloat()); bubbleSpin.x = v.x; bubbleSpin.y = v.y }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) { onLeft = v.x + v.width / 2 < host.width / 2; bubbleY = v.y; snap(true) }
                    else { expanded = !expanded; refresh() }
                    true
                }
                else -> false
            }
        }
    }

    private fun idleX() = if (onLeft) -dp(28).toFloat() else (host.width - dp(28)).toFloat()
    private fun snap(animate: Boolean) {
        if (host.width == 0) { host.post { snap(animate) }; return }
        if (bubbleY < 0) bubbleY = host.height - dp(190).toFloat()
        val tx = if (expanded) (if (onLeft) dp(8).toFloat() else (host.width - dp(64)).toFloat()) else idleX()
        if (animate) { bubble.animate().x(tx).y(bubbleY).setDuration(180).start(); bubbleSpin.animate().x(tx).y(bubbleY).setDuration(180).start() } else { bubble.x = tx; bubble.y = bubbleY; bubbleSpin.x = tx; bubbleSpin.y = bubbleY }
        bubble.alpha = if (expanded) 1f else 0.92f
        pill.post {
            pill.x = if (onLeft) (bubble.x + dp(48)) else (bubble.x - pill.width - dp(0))
            pill.y = bubbleY + dp(2)
        }
    }

    private fun toggleSheet(show: Boolean) {
        if (show) {
            fillQueue(); sheet.visibility = View.VISIBLE; sheet.translationY = sheet.height.toFloat().coerceAtLeast(dp(400).toFloat())
            scrim.visibility = View.VISIBLE; scrim.animate().alpha(1f).setDuration(220).start()
            backCb.isEnabled = true
            sheet.animate().translationY(0f).setDuration(220).start()
        } else if (sheet.visibility == View.VISIBLE) {
            backCb.isEnabled = false
            scrim.animate().alpha(0f).setDuration(180).withEndAction { scrim.visibility = View.GONE }.start()
            sheet.animate().translationY(sheet.height.toFloat()).setDuration(180).withEndAction { sheet.visibility = View.GONE }.start()
        }
    }

    private val fillRun = Runnable { fillQueueNow() }

    /** Never rebuild the list inside the player callback itself (it can fire during layout or a view change): post it once, coalescing repeats. */
    private fun fillQueue() {
        sheetList.removeCallbacks(fillRun)
        sheetList.post(fillRun)
    }

    private fun fillQueueNow() {
        if (activity.isFinishing || activity.isDestroyed) return
        runCatching { buildQueueRows() }
    }

    private fun buildQueueRows() {
        val p = attached
        sheetList.removeAllViews()
        if (p == null) return
        queueTitle.text = activity.getString(R.string.o_queue) + " · " + activity.getString(R.string.o_queue_count, p.mediaItemCount)
        repeatChip?.text = activity.getString(R.string.o_repeat_mode) + when (p.repeatMode) { Player.REPEAT_MODE_ALL -> " ∞"; Player.REPEAT_MODE_ONE -> " 1"; else -> "" }
        val cur = p.currentMediaItemIndex
        fun header(t: String) = TextView(activity).apply { text = t; textSize = 12f; setTextColor(0xB3FFFFFF.toInt()); setPadding(dp(2), dp(10), 0, dp(4)) }
        sheetList.addView(header(activity.getString(R.string.o_now_playing)))
        if (cur in 0 until p.mediaItemCount) sheetList.addView(queueRow(p, cur, true))
        if (cur + 1 < p.mediaItemCount) {
            sheetList.addView(header(activity.getString(R.string.o_up_next) + " · " + (p.mediaItemCount - cur - 1)))
            for (i in cur + 1 until p.mediaItemCount) sheetList.addView(queueRow(p, i, false))
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun queueRow(p: Player, i: Int, current: Boolean): View {
        val item = p.getMediaItemAt(i)
        val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
        val art = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
            background = GradientDrawable().apply { cornerRadius = 10 * d; setColor(0xFF2A2A33.toInt()) }; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND }
        val u = item.mediaMetadata.artworkUri?.toString()
        if (!u.isNullOrBlank()) art.loadThumbnail(false, u)
        row.addView(art, LinearLayout.LayoutParams(dp(46), dp(46)))
        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0) }
        col.addView(TextView(activity).apply { text = item.mediaMetadata.title ?: ""; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; textSize = 14f; setTextColor(if (current) 0xFF9D86FF.toInt() else Color.WHITE) })
        col.addView(TextView(activity).apply { text = item.mediaMetadata.artist ?: ""; maxLines = 1; textSize = 11f; setTextColor(0xB3FFFFFF.toInt()) })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        row.setOnLongClickListener {
            val id = item.mediaId
            if (!ShareLink.canShare(id)) false
            else {
                val ref = MediaRef(id, item.mediaMetadata.title?.toString().orEmpty(), item.mediaMetadata.artist?.toString().orEmpty(), item.mediaMetadata.artworkUri?.toString().orEmpty(), "", true)
                val m = android.widget.PopupMenu(activity, row)
                m.menu.add(0, 1, 0, activity.getString(R.string.play_add_to_playlist)); m.menu.add(0, 2, 1, activity.getString(R.string.share))
                m.setOnMenuItemClickListener { mi -> if (mi.itemId == 1) PlaylistPicker.show(activity, listOf(ref)) else ShareLink.share(activity, ref.url, ref.title); true }
                m.show(); true
            }
        }
        row.addView(OIcons.view(activity, R.drawable.ic_o_close, 18, 0xB3FFFFFF.toInt(), 8) { p.removeMediaItem(i); offerUndo(listOf(i to item)); fillQueue() }, LinearLayout.LayoutParams(dp(38), dp(40)))
        if (!current) {
            // drag handle: drag up or down, the song moves when you let go
            val handle = OIcons.view(activity, R.drawable.ic_o_drag, 22, 0xB3FFFFFF.toInt(), 6)
            var startY = 0f
            handle.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { startY = e.rawY; v.parent.requestDisallowInterceptTouchEvent(true); true }
                    MotionEvent.ACTION_MOVE -> { row.translationY = e.rawY - startY; row.elevation = 8 * d; true }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val steps = Math.round((e.rawY - startY) / row.height.coerceAtLeast(1))
                        val to = (i + steps).coerceIn(p.currentMediaItemIndex + 1, p.mediaItemCount - 1)
                        if (to != i) p.moveMediaItem(i, to)
                        row.translationY = 0f; fillQueue(); true
                    }
                    else -> false
                }
            }
            row.addView(handle, LinearLayout.LayoutParams(dp(36), dp(40)))
            row.setOnClickListener { p.seekTo(i, 0); PlayQueue.startCurrent(p); fillQueue() }
        }
        return row
    }

    init {
        build()
        // a queue saved by the last session comes back as a paused bubble
        tryRestore()
        PlaybackService.listeners.add(serviceListener)
        PlayQueue.stateListeners.add(stateListener)
        (activity as? androidx.lifecycle.LifecycleOwner)?.lifecycle?.addObserver(startObserver)
        attach()
    }

    private fun attach() {
        attached?.removeListener(playerListener)
        attached = PlaybackService.player
        attached?.addListener(playerListener)
        refresh()
    }

    private fun refresh() {
        val p = attached
        if (p == null || p.mediaItemCount == 0) {
            bubble.visibility = View.GONE; bubbleSpin.visibility = View.GONE; pill.visibility = View.GONE; expanded = false; toggleSheet(false); return
        }
        bubble.visibility = View.VISIBLE
        pill.visibility = if (expanded) View.VISIBLE else View.GONE
        pillTitle.text = p.mediaMetadata.title
        val art = p.mediaMetadata.artworkUri?.toString()
        if (art != lastArt) {
            lastArt = art
            if (!art.isNullOrBlank()) bubble.loadThumbnail(false, art) else bubble.setImageResource(R.drawable.ic_music)
        }
        // loading state: from the tap on play until sound starts (restored queue, slow stream)
        val waiting = !p.isPlaying && (PlayQueue.starting || (p.playWhenReady && p.playbackState == Player.STATE_BUFFERING))
        pillSpin.visibility = if (waiting) View.VISIBLE else View.GONE
        bubbleSpin.visibility = if (waiting) View.VISIBLE else View.GONE
        pillPlay.alpha = if (waiting) 0.25f else 1f
        pillPlay.setImageResource(if (p.isPlaying || waiting) R.drawable.ic_mini_pause else R.drawable.ic_mini_play)
        snap(false)
        if (sheet.visibility == View.VISIBLE) fillQueue()
    }

    fun release() {
        released = true
        (activity as? androidx.lifecycle.LifecycleOwner)?.lifecycle?.removeObserver(startObserver)
        PlaybackService.listeners.remove(serviceListener)
        PlayQueue.stateListeners.remove(stateListener)
        attached?.removeListener(playerListener)
        (bubble.parent as? ViewGroup)?.removeView(bubble)
        (pill.parent as? ViewGroup)?.removeView(pill)
        (sheet.parent as? ViewGroup)?.removeView(sheet)
        (scrim.parent as? ViewGroup)?.removeView(scrim)
        backCb.remove()
        sheetList.removeCallbacks(fillRun)
        attached = null
    }
}
