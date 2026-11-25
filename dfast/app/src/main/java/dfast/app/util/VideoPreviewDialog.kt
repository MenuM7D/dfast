package dfast.app.util

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.ui.PlayerView
import dfast.app.R

/** Full-screen in-app video player: title, close, playback speed. Pauses and releases when closed. */
object VideoPreviewDialog {
    fun show(activity: Activity, uri: Uri, title: String = "") {
        val player = VideoPlayerUtil.buildPlayer(activity)
        val d = activity.resources.displayMetrics.density
        val root = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
        val view = PlayerView(activity).apply {
            layoutParams = ViewGroup.LayoutParams(-1, -1)
            this.player = player
        }
        root.addView(view, FrameLayout.LayoutParams(-1, -1))

        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val bar = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * d).toInt(), (28 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xB3000000.toInt(), 0x00000000))
        }
        bar.addView(ImageButton(activity).apply {
            setImageResource(R.drawable.ic_mini_close); setColorFilter(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT); contentDescription = "Close"
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt()))
        bar.addView(TextView(activity).apply {
            text = title; setTextColor(Color.WHITE); textSize = 14f; maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = (6 * d).toInt() })
        val speeds = floatArrayOf(1f, 1.25f, 1.5f, 2f, 0.5f, 0.75f)
        var idx = 0
        val speed = TextView(activity).apply {
            text = "1x"; setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER
            setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
            background = GradientDrawable().apply { cornerRadius = 1000f; setColor(0x44FFFFFF) }
        }
        speed.setOnClickListener {
            idx = (idx + 1) % speeds.size
            player.setPlaybackSpeed(speeds[idx])
            speed.text = (if (speeds[idx] % 1f == 0f) speeds[idx].toInt().toString() else speeds[idx].toString()) + "x"
        }
        bar.addView(speed)
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        dialog.setContentView(root)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        dialog.setOnDismissListener { player.release() }
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = true
        dialog.show()
    }
}
