package dfast.app.util

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.ImageView
import androidx.preference.PreferenceManager
import dfast.app.R
import dfast.app.services.PlaybackService
import dfast.app.util.Extensions.loadThumbnail

/** Optional: the music bubble over other apps. Only when the user turned on "show over other apps" and granted the permission. */
object OverlayBubble {
    private var view: ImageView? = null
    private var artListener: androidx.media3.common.Player.Listener? = null
    private var artPlayer: androidx.media3.common.Player? = null

    fun enabled(c: Context) = PreferenceManager.getDefaultSharedPreferences(c).getBoolean("bubble_overlay", false) && Settings.canDrawOverlays(c)

    @SuppressLint("ClickableViewAccessibility")
    fun show(ctx: Context) {
        val c = ctx.applicationContext
        if (view != null || !enabled(c)) return
        val p = PlaybackService.player ?: return
        if (p.mediaItemCount == 0) return
        val d = c.resources.displayMetrics.density
        val size = (56 * d).toInt()
        val wm = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val iv = ImageView(c).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; setImageResource(R.drawable.ic_music)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF1B1B22.toInt()) }
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND; alpha = 0.95f
        }
        p.mediaMetadata.artworkUri?.toString()?.takeIf { it.isNotBlank() }?.let { iv.loadThumbnail(false, it) }
        // follow the song that is playing now (the picture used to stay the one from when the bubble was shown)
        artListener = object : androidx.media3.common.Player.Listener {
            private var last = p.mediaMetadata.artworkUri?.toString().orEmpty()
            override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                val a = mediaMetadata.artworkUri?.toString().orEmpty()
                if (a == last) return
                last = a
                if (a.isNotBlank()) iv.loadThumbnail(false, a) else iv.setImageResource(R.drawable.ic_music)
            }
        }
        artPlayer = p
        p.addListener(artListener!!)
        val lp = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = c.resources.displayMetrics.widthPixels - size / 2; y = (c.resources.displayMetrics.heightPixels * 0.6f).toInt()
        }
        val gd = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                c.packageManager.getLaunchIntentForPackage(c.packageName)?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)?.let { c.startActivity(it) }
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean { PlaybackService.player?.let { PlayQueue.togglePlay(it) }; return true }
        })
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
        iv.setOnTouchListener { _, e ->
            gd.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY }
                MotionEvent.ACTION_MOVE -> { lp.x = sx + (e.rawX - tx).toInt(); lp.y = sy + (e.rawY - ty).toInt(); runCatching { wm.updateViewLayout(iv, lp) } }
                MotionEvent.ACTION_UP -> {
                    val w = c.resources.displayMetrics.widthPixels
                    lp.x = if (lp.x + size / 2 < w / 2) -size / 2 else w - size / 2
                    runCatching { wm.updateViewLayout(iv, lp) }
                }
            }
            true
        }
        runCatching { wm.addView(iv, lp); view = iv }
    }

    fun hide(ctx: Context) {
        artListener?.let { artPlayer?.removeListener(it) }; artListener = null; artPlayer = null
        val v = view ?: return
        runCatching { (ctx.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) }
        view = null
    }
}
