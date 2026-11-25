package dfast.app.util

import android.annotation.SuppressLint
import android.app.Activity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.Player
import dfast.app.R
import dfast.app.services.PlaybackService
import dfast.app.util.Extensions.loadThumbnail

/** M4: floating draggable bubble with the artwork. Tap to expand the controls card. */
class MiniPlayer(private val activity: Activity) {
    private val card: View? = activity.findViewById(R.id.mini_player)
    private val bubble: ImageView? = activity.findViewById(R.id.mini_bubble)
    private val title: TextView? = activity.findViewById(R.id.mini_title)
    private val play: ImageButton? = activity.findViewById(R.id.mini_play)
    private var attached: Player? = null
    private var expanded = false
    private var lastArt: String? = null

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
    }
    private val serviceListener: () -> Unit = { activity.runOnUiThread { attach() } }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag() {
        val b = bubble ?: return
        var dX = 0f; var dY = 0f; var downX = 0f; var downY = 0f; var moved = false
        b.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dX = v.x - e.rawX; dY = v.y - e.rawY; downX = e.rawX; downY = e.rawY; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(e.rawX - downX) > 12 || Math.abs(e.rawY - downY) > 12) moved = true
                    if (moved) {
                        val parent = v.parent as View
                        v.x = (e.rawX + dX).coerceIn(0f, (parent.width - v.width).toFloat())
                        v.y = (e.rawY + dY).coerceIn(0f, (parent.height - v.height).toFloat())
                        placeCard()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) { expanded = !expanded; refresh() }
                    true
                }
                else -> false
            }
        }
    }

    private fun placeCard() {
        val b = bubble ?: return
        val c = card ?: return
        val parent = b.parent as View
        c.post {
            val y = if (b.y > parent.height / 2) b.y - c.height - 12 else b.y + b.height + 12
            c.y = y.coerceIn(0f, (parent.height - c.height).toFloat())
        }
    }

    init {
        play?.setOnClickListener {
            PlaybackService.player?.let { if (it.isPlaying) it.pause() else it.play() }
        }
        activity.findViewById<View>(R.id.mini_prev)?.setOnClickListener { PlaybackService.player?.seekToPreviousMediaItem() }
        activity.findViewById<View>(R.id.mini_next)?.setOnClickListener { PlaybackService.player?.seekToNextMediaItem() }
        activity.findViewById<View>(R.id.mini_shuffle)?.setOnClickListener {
            PlaybackService.player?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
        }
        activity.findViewById<View>(R.id.mini_close)?.setOnClickListener {
            expanded = false
            PlaybackService.stop(activity)
        }
        setupDrag()
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
            card?.visibility = View.GONE; bubble?.visibility = View.GONE; expanded = false; return
        }
        bubble?.visibility = View.VISIBLE
        card?.visibility = if (expanded) View.VISIBLE else View.GONE
        if (expanded) placeCard()
        title?.text = p.mediaMetadata.title
        val art = p.mediaMetadata.artworkUri?.toString()
        if (art != lastArt) {
            lastArt = art
            if (!art.isNullOrBlank()) bubble?.loadThumbnail(false, art) else bubble?.setImageResource(R.drawable.ic_music)
        }
        activity.findViewById<ImageView>(R.id.mini_shuffle)?.imageAlpha = if (p.shuffleModeEnabled) 255 else 110
        val multi = p.mediaItemCount > 1
        listOf(R.id.mini_prev, R.id.mini_next, R.id.mini_shuffle).forEach { activity.findViewById<View>(it)?.visibility = if (multi) View.VISIBLE else View.GONE }
        play?.setImageResource(if (p.isPlaying) R.drawable.ic_mini_pause else R.drawable.ic_mini_play)
    }

    fun release() {
        PlaybackService.listeners.remove(serviceListener)
        attached?.removeListener(playerListener)
    }
}
