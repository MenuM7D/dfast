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
    private val sheet = LinearLayout(activity)
    private val sheetList = LinearLayout(activity)
    private val queueTitle = TextView(activity)
    private var attached: Player? = null
    private var expanded = false
    private var onLeft = false
    private var lastArt: String? = null
    private var bubbleY = -1f

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
    }
    private val serviceListener: () -> Unit = { activity.runOnUiThread { attach() } }

    private fun ink() = OrbitColors.ink(activity)

    private fun iconBtn(res: Int, desc: String, onClick: () -> Unit) = ImageButton(activity).apply {
        setImageResource(res); setBackgroundColor(Color.TRANSPARENT); contentDescription = desc
        setColorFilter(ink()); setOnClickListener { onClick() }
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

        pill.apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; elevation = 12 * d
            setPadding(dp(8), dp(4), dp(8), dp(4)); visibility = View.GONE
            background = GradientDrawable().apply { cornerRadius = 1000f; setColor(OrbitColors.get(activity, R.attr.orbitDeepGlass, 0xF2151520.toInt()).let { it or 0xFF000000.toInt() }); setStroke(dp(1), OrbitColors.get(activity, R.attr.orbitStroke, 0x33FFFFFF)) }
        }
        pillTitle.apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; textSize = 13f; setTextColor(ink()); setPadding(dp(10), 0, dp(6), 0) }
        pill.addView(pillTitle, LinearLayout.LayoutParams(dp(110), -2))
        pill.addView(iconBtn(R.drawable.ic_mini_prev, "Previous") { PlaybackService.player?.seekToPreviousMediaItem() }, LinearLayout.LayoutParams(dp(36), dp(40)))
        pillPlay.apply { setBackgroundColor(Color.TRANSPARENT); setColorFilter(ink()); setOnClickListener { PlaybackService.player?.let { if (it.isPlaying) it.pause() else it.play() } } }
        pill.addView(pillPlay, LinearLayout.LayoutParams(dp(40), dp(40)))
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
            setOnClickListener { toggleSheet(false); activity.startActivity(Intent(activity, OfflineActivity::class.java)) }
        })
        head.addView(iconBtn(R.drawable.ic_mini_close, activity.getString(R.string.mini_close)) { toggleSheet(false) }.apply { setColorFilter(Color.WHITE) }, LinearLayout.LayoutParams(dp(40), dp(40)))
        sheet.addView(head)
        sheet.addView(ScrollView(activity).apply { addView(sheetList) }, LinearLayout.LayoutParams(-1, 0, 1f))
        host.addView(sheet, FrameLayout.LayoutParams(-1, (activity.resources.displayMetrics.heightPixels * 0.55f).toInt(), Gravity.BOTTOM))

        // drag + edge snap
        var dX = 0f; var dY = 0f; var downX = 0f; var downY = 0f; var moved = false
        bubble.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { v.animate().cancel(); dX = v.x - e.rawX; dY = v.y - e.rawY; downX = e.rawX; downY = e.rawY; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(e.rawX - downX) > 12 || Math.abs(e.rawY - downY) > 12) moved = true
                    if (moved) { v.x = e.rawX + dX; v.y = (e.rawY + dY).coerceIn(dp(60).toFloat(), (host.height - dp(140)).toFloat()) }
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
        if (animate) bubble.animate().x(tx).y(bubbleY).setDuration(180).start() else { bubble.x = tx; bubble.y = bubbleY }
        bubble.alpha = if (expanded) 1f else 0.92f
        pill.post {
            pill.x = if (onLeft) (bubble.x + dp(48)) else (bubble.x - pill.width - dp(0))
            pill.y = bubbleY + dp(2)
        }
    }

    private fun toggleSheet(show: Boolean) {
        if (show) {
            fillQueue(); sheet.visibility = View.VISIBLE; sheet.translationY = sheet.height.toFloat().coerceAtLeast(dp(400).toFloat())
            sheet.animate().translationY(0f).setDuration(220).start()
        } else if (sheet.visibility == View.VISIBLE) {
            sheet.animate().translationY(sheet.height.toFloat()).setDuration(180).withEndAction { sheet.visibility = View.GONE }.start()
        }
    }

    private fun fillQueue() {
        sheetList.removeAllViews()
        val p = attached ?: return
        queueTitle.text = activity.getString(R.string.o_queue) + " · " + activity.getString(R.string.o_queue_count, p.mediaItemCount)
        for (i in 0 until p.mediaItemCount) {
            val item = p.getMediaItemAt(i)
            val current = i == p.currentMediaItemIndex
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
            row.addView(ImageButton(activity).apply { setImageResource(R.drawable.ic_mini_prev); rotation = 90f; setColorFilter(0xB3FFFFFF.toInt()); setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { if (i > 0) { p.moveMediaItem(i, i - 1); fillQueue() } } }, LinearLayout.LayoutParams(dp(34), dp(40)))
            row.addView(ImageButton(activity).apply { setImageResource(R.drawable.ic_mini_next); rotation = 90f; setColorFilter(0xB3FFFFFF.toInt()); setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { if (i < p.mediaItemCount - 1) { p.moveMediaItem(i, i + 1); fillQueue() } } }, LinearLayout.LayoutParams(dp(34), dp(40)))
            row.addView(TextView(activity).apply { text = "✕"; textSize = 16f; setTextColor(0xB3FFFFFF.toInt()); gravity = Gravity.CENTER
                setOnClickListener { p.removeMediaItem(i); fillQueue() } }, LinearLayout.LayoutParams(dp(34), dp(40)))
            row.setOnClickListener { p.seekTo(i, 0); p.play(); fillQueue() }
            sheetList.addView(row)
        }
    }

    init {
        build()
        PlaybackService.listeners.add(serviceListener)
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
            bubble.visibility = View.GONE; pill.visibility = View.GONE; expanded = false; toggleSheet(false); return
        }
        bubble.visibility = View.VISIBLE
        pill.visibility = if (expanded) View.VISIBLE else View.GONE
        pillTitle.text = p.mediaMetadata.title
        val art = p.mediaMetadata.artworkUri?.toString()
        if (art != lastArt) {
            lastArt = art
            if (!art.isNullOrBlank()) bubble.loadThumbnail(false, art) else bubble.setImageResource(R.drawable.ic_music)
        }
        pillPlay.setImageResource(if (p.isPlaying) R.drawable.ic_mini_pause else R.drawable.ic_mini_play)
        snap(false)
        if (sheet.visibility == View.VISIBLE) fillQueue()
    }

    fun release() {
        PlaybackService.listeners.remove(serviceListener)
        attached?.removeListener(playerListener)
        (bubble.parent as? ViewGroup)?.removeView(bubble)
        (pill.parent as? ViewGroup)?.removeView(pill)
        (sheet.parent as? ViewGroup)?.removeView(sheet)
    }
}
