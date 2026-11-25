package dfast.app.util

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.net.Uri
import android.view.ViewGroup
import androidx.media3.common.MediaItem
import androidx.media3.ui.PlayerView

/** Full-screen in-app video preview. Pauses and releases when closed. */
object VideoPreviewDialog {
    fun show(activity: Activity, uri: Uri) {
        val player = VideoPlayerUtil.buildPlayer(activity)
        val view = PlayerView(activity).apply {
            layoutParams = ViewGroup.LayoutParams(-1, -1)
            setBackgroundColor(Color.BLACK)
            this.player = player
        }
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(view)
        dialog.setOnDismissListener { player.release() }
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = true
        dialog.show()
    }
}
