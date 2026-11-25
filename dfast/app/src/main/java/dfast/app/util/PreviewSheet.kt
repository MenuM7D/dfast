package dfast.app.util

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import dfast.app.R
import dfast.app.database.models.ResultItem
import dfast.app.database.viewmodel.ResultViewModel
import dfast.app.services.PlaybackService
import dfast.app.util.Extensions.loadThumbnail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Audio (half sheet) and video (compact player) previews for a search result. */
object PreviewSheet {
    private fun dp(v: View, n: Int) = (n * v.resources.displayMetrics.density).toInt()

    private fun sheetBg(activity: android.content.Context): GradientDrawable = GradientDrawable().apply {
        val tv = android.util.TypedValue()
        val ok = activity.theme.resolveAttribute(R.attr.orbitDeep, tv, true)
        setColor(if (ok) tv.data else Color.parseColor("#1B1470"))
        cornerRadii = floatArrayOf(56f, 56f, 56f, 56f, 0f, 0f, 0f, 0f)
    }

    private fun pill(activity: AppCompatActivity, text: String, white: Boolean, onClick: () -> Unit): Button =
        Button(activity).apply {
            this.text = text
            isAllCaps = false
            setTextColor(if (white) Color.parseColor("#1B1470") else Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = 100f
                setColor(if (white) Color.WHITE else Color.parseColor("#33FFFFFF"))
            }
            setOnClickListener { onClick() }
        }

    private suspend fun streamUrls(activity: AppCompatActivity, item: ResultItem): List<String> {
        if (item.urls.isNotBlank()) return item.urls.split("\n").filter { it.isNotBlank() }
        val vm = ViewModelProvider(activity)[ResultViewModel::class.java]
        return withContext(Dispatchers.IO) { vm.getStreamingUrlAndChapters(item.url).first }.filter { it.isNotBlank() }
    }

    private fun root(activity: AppCompatActivity): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = sheetBg(activity)
        setPadding(dp(this, 20), dp(this, 16), dp(this, 20), dp(this, 24))
    }

    private fun titleBlock(activity: AppCompatActivity, item: ResultItem, parent: LinearLayout) {
        parent.addView(TextView(activity).apply {
            text = item.title; setTextColor(Color.WHITE); textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD); maxLines = 2
        })
        parent.addView(TextView(activity).apply {
            text = item.author; setTextColor(Color.parseColor("#B3FFFFFF")); textSize = 12f
        })
    }

    fun showAudio(activity: AppCompatActivity, item: ResultItem, onDownload: () -> Unit) {
        val dialog = BottomSheetDialog(activity)
        val root = root(activity)
        val head = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        val thumb = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, 36f) }
            }
        }
        head.addView(thumb, LinearLayout.LayoutParams(dp(root, 64), dp(root, 64)))
        val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        titleBlock(activity, item, texts)
        head.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(root, 12) })
        val play = pill(activity, "⏸", true) {}
        head.addView(play, LinearLayout.LayoutParams(dp(root, 56), dp(root, 56)))
        root.addView(head)
        val seek = SeekBar(activity)
        root.addView(seek, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(root, 12) })
        val status = TextView(activity).apply { setTextColor(Color.parseColor("#B3FFFFFF")); textSize = 12f; text = "…" }
        root.addView(status)
        root.addView(pill(activity, "⬇ Save audio", true) { onDownload(); dialog.dismiss() },
            LinearLayout.LayoutParams(-1, dp(root, 52)).apply { topMargin = dp(root, 14) })
        thumb.loadThumbnail(false, item.thumb)
        dialog.setContentView(root)

        val handler = Handler(Looper.getMainLooper())
        var userSeeking = false
        val tick = object : Runnable {
            override fun run() {
                val p = PlaybackService.player
                if (p != null && p.duration > 0) {
                    if (!userSeeking) { seek.max = p.duration.toInt(); seek.progress = p.currentPosition.toInt() }
                    status.text = fmt(p.currentPosition) + " / " + fmt(p.duration)
                    play.text = if (p.isPlaying) "⏸" else "▶"
                }
                handler.postDelayed(this, 500)
            }
        }
        play.setOnClickListener { PlaybackService.player?.let { if (it.isPlaying) it.pause() else it.play() } }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) { if (fromUser) PlaybackService.player?.seekTo(v.toLong()) }
            override fun onStartTrackingTouch(s: SeekBar?) { userSeeking = true }
            override fun onStopTrackingTouch(s: SeekBar?) { userSeeking = false }
        })
        dialog.setOnDismissListener { handler.removeCallbacks(tick) }
        dialog.show()
        handler.post(tick)
        activity.lifecycleScope.launch {
            try {
                val urls = streamUrls(activity, item)
                if (urls.isEmpty()) throw Exception("No stream found")
                PlaybackService.play(activity, urls.last(), item.title, item.thumb)
            } catch (e: Exception) {
                status.text = e.message ?: "Preview failed"
            }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun showVideo(activity: AppCompatActivity, item: ResultItem, onDownload: (Boolean) -> Unit, onQuality: (dfast.app.database.models.Format) -> Unit = {}) {
        val dialog = BottomSheetDialog(activity)
        val root = root(activity)
        val player = VideoPlayerUtil.buildPlayer(activity)
        val view = PlayerView(activity).apply { this.player = player; setBackgroundColor(Color.BLACK) }
        root.addView(view, LinearLayout.LayoutParams(-1, dp(root, 210)))
        titleBlock(activity, item, root)
        val status = TextView(activity).apply { setTextColor(Color.parseColor("#B3FFFFFF")); textSize = 12f; text = "…" }
        root.addView(status)
        val byHeight = item.formats
            .filter { (it.height ?: 0) > 0 && it.vcodec.isNotBlank() && it.vcodec != "none" }
            .groupBy { it.height!! }
            .mapValues { e -> e.value.maxByOrNull { it.filesize } ?: e.value.first() }
        val heights = byHeight.keys.sortedDescending().take(5)
        var selected: dfast.app.database.models.Format? = null
        if (heights.isNotEmpty()) {
            val chips = LinearLayout(activity)
            val views = mutableMapOf<Int, TextView>()
            fun paint() = views.forEach { (h, tv) ->
                val on = selected === byHeight[h]
                tv.setTextColor(if (on) Color.parseColor("#1B1470") else Color.WHITE)
                tv.background = GradientDrawable().apply { cornerRadius = 100f; setColor(if (on) Color.WHITE else Color.parseColor("#33FFFFFF")) }
            }
            heights.forEach { h ->
                val tv = TextView(activity).apply {
                    text = "${h}p"; textSize = 12f; gravity = Gravity.CENTER
                    setPadding(dp(root, 12), dp(root, 6), dp(root, 12), dp(root, 6))
                    setOnClickListener { selected = if (selected === byHeight[h]) null else byHeight[h]; paint() }
                }
                views[h] = tv
                chips.addView(tv, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(root, 6) })
            }
            paint()
            root.addView(chips, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(root, 8) })
        }
        val row = LinearLayout(activity)
        row.addView(pill(activity, "▶ MP4", true) { val f = selected; if (f != null) onQuality(f) else onDownload(false); dialog.dismiss() }, LinearLayout.LayoutParams(0, dp(root, 52), 1f))
        row.addView(pill(activity, "♫ MP3", false) { onDownload(true); dialog.dismiss() },
            LinearLayout.LayoutParams(0, dp(root, 52), 1f).apply { marginStart = dp(root, 10) })
        root.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(root, 14) })
        root.addView(pill(activity, "🎧 Audio only", false) { dialog.dismiss(); showAudio(activity, item) { onDownload(true) } },
            LinearLayout.LayoutParams(-1, dp(root, 48)).apply { topMargin = dp(root, 8) })
        dialog.setContentView(root)
        dialog.setOnDismissListener { player.release() }
        dialog.show()
        activity.lifecycleScope.launch {
            try {
                val urls = streamUrls(activity, item)
                if (urls.isEmpty()) throw Exception("No stream found")
                val factory = DefaultMediaSourceFactory(activity)
                if (urls.size >= 2) {
                    player.setMediaSource(MergingMediaSource(
                        factory.createMediaSource(MediaItem.fromUri(urls[0])),
                        factory.createMediaSource(MediaItem.fromUri(urls[1]))))
                } else {
                    player.setMediaItem(MediaItem.fromUri(urls[0]))
                }
                player.prepare()
                player.playWhenReady = true
                status.text = ""
            } catch (e: Exception) {
                status.text = e.message ?: "Preview failed"
            }
        }
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}
