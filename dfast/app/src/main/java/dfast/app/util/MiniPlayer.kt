package dfast.app.util

import android.app.Activity
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.media3.common.Player
import dfast.app.R
import dfast.app.services.PlaybackService

/** Binds the mini player card in activity_main to the playback service. */
class MiniPlayer(private val activity: Activity) {
    private val card: View? = activity.findViewById(R.id.mini_player)
    private val title: TextView? = activity.findViewById(R.id.mini_title)
    private val play: ImageButton? = activity.findViewById(R.id.mini_play)
    private var attached: Player? = null

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
    }
    private val serviceListener: () -> Unit = { activity.runOnUiThread { attach() } }

    init {
        play?.setOnClickListener {
            PlaybackService.player?.let { if (it.isPlaying) it.pause() else it.play() }
        }
        activity.findViewById<View>(R.id.mini_close)?.setOnClickListener { PlaybackService.stop(activity) }
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
        if (p == null || p.mediaItemCount == 0) { card?.visibility = View.GONE; return }
        card?.visibility = View.VISIBLE
        title?.text = p.mediaMetadata.title
        play?.setImageResource(if (p.isPlaying) R.drawable.ic_mini_pause else R.drawable.ic_mini_play)
    }

    fun release() {
        PlaybackService.listeners.remove(serviceListener)
        attached?.removeListener(playerListener)
    }
}
