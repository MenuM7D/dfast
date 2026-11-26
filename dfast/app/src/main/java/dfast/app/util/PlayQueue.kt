package dfast.app.util

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import dfast.app.R
import dfast.app.database.DBManager
import dfast.app.services.PlaybackService
import dfast.app.util.extractors.ytdlp.YTDLPUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Background queue with rolling preload. Songs added to the queue are placeholders until their stream
 * is resolved. Whenever the current song changes (or the queue changes) the next PRELOAD songs are
 * resolved in the background and swapped in, so the player moves on without a gap.
 */
object PlayQueue {
    const val PENDING = "dfast-pending"
    const val PRELOAD = 2
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val resolving = HashSet<String>()
    private var attachedTo: Player? = null
    private lateinit var app: Context

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            prefetch(); fixCurrent()
            // a song that was played goes to the END of the queue instead of leaving it
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
                Handler(Looper.getMainLooper()).post { rotateToFront() }
        }
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) { prefetch() }
        override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) { StartupTrace.end("playing"); setStarting(false) } }
        override fun onPlaybackStateChanged(state: Int) {
            if (StartupTrace.active()) { if (state == Player.STATE_BUFFERING) StartupTrace.mark("buffering") else if (state == Player.STATE_READY) StartupTrace.mark("ready") }
        }
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) { if (isPending(PlaybackService.player?.currentMediaItem)) fixCurrent() else recover() }
    }

    private val retried = HashMap<String, Long>()

    /** True from the moment the user asks the (restored) queue to play until sound really starts, or it fails. Drives the spinner on the pill, bubble and notification. */
    @Volatile var starting = false
        private set
    /** Called (on the main thread) whenever [starting] changes. */
    val stateListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    private val mainH = Handler(Looper.getMainLooper())
    private val startTimeout = Runnable {
        if (starting) {
            setStarting(false)
            val p = PlaybackService.player
            if (p != null && !p.isPlaying && ::app.isInitialized) Toast.makeText(app, app.getString(R.string.o_load_fail), Toast.LENGTH_LONG).show()
        }
    }
    private fun setStarting(v: Boolean) {
        if (starting == v) return
        starting = v
        mainH.removeCallbacks(startTimeout)
        if (v) mainH.postDelayed(startTimeout, 30_000)
        stateListeners.forEach { runCatching { it() } }
    }

    /** Resolves the current song's stream in the background right after a restore, so the first tap on play has nothing left to wait for. */
    fun warmCurrent(ctx: Context) {
        app = ctx.applicationContext
        val cur = PlaybackService.player?.currentMediaItem ?: return
        if (!isPending(cur) || !cur.mediaId.startsWith("http")) return
        val id = cur.mediaId
        scope.launch { withContext(Dispatchers.IO) { runCatching { resolve(id) } } }
    }

    /** True right after a saved queue was restored and before the user pressed play: nothing is resolved or started. */
    @Volatile var suspended = false
    private val resumePos = HashMap<String, Long>()

    /** Called after the saved queue is put back: the current song resumes at [posMs] when the user presses play. */
    fun markRestored(mediaId: String, posMs: Long) { suspended = true; resumePos.clear(); if (posMs > 0) resumePos[mediaId] = posMs }

    /**
     * Keeps the queue a loop: every song before the current one moves to the end, in order, so nothing
     * is lost once it has been played and the current song is always first. Not used in shuffle mode.
     */
    fun rotateToFront() {
        val p = PlaybackService.player ?: return
        if (p.shuffleModeEnabled) return
        var n = p.currentMediaItemIndex
        if (n <= 0 || n >= p.mediaItemCount) return
        while (n > 0) { p.moveMediaItem(0, p.mediaItemCount - 1); n-- }
    }

    /** Previous: restart the song after 3 s, otherwise go to the song that was just before it (the last one in the loop). */
    fun previous(p: Player) {
        if (p.currentPosition > 3000 || p.mediaItemCount < 2) { p.seekTo(p.currentMediaItemIndex, 0); return }
        if (p.hasPreviousMediaItem()) p.seekToPreviousMediaItem() else p.seekTo(p.mediaItemCount - 1, 0)
    }

    /** Play / pause from any control. Handles the restored (not yet prepared) queue. */
    fun togglePlay(p: Player) {
        if (p.isPlaying) { setStarting(false); p.pause(); return }
        if (starting) return // already on its way: a second tap must not restart it
        if (suspended) { startCurrent(p, true); return }
        if (p.playbackState == Player.STATE_IDLE) { setStarting(true); p.prepare() }
        p.play()
    }

    /** Starts the current song. After a restore the stream is resolved now and playback resumes at the saved position. */
    fun startCurrent(p: Player, keepSavedPosition: Boolean = false) {
        suspended = false
        StartupTrace.begin("resume"); if (!p.isPlaying) setStarting(true)
        if (!keepSavedPosition) resumePos.clear()
        if (isPending(p.currentMediaItem)) { p.playWhenReady = true; fixCurrent(); prefetch(); return }
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        p.play()
        prefetch()
    }

    /** True when a failed stream can still be re-resolved (once per minute per song). */
    fun willRecover(item: MediaItem?): Boolean {
        val id = item?.mediaId ?: return false
        if (!id.startsWith("http")) return false
        val t = retried[id] ?: return true
        return System.currentTimeMillis() - t > 60_000
    }

    /** A stream that dies a few seconds in (expired or refused URL): resolve it again with the other extractor first and resume where it stopped. */
    private fun recover() {
        val p = PlaybackService.player as? ExoPlayer ?: return
        val cur = p.currentMediaItem ?: return
        if (!willRecover(cur)) return
        val id = cur.mediaId
        retried[id] = System.currentTimeMillis()
        val pos = p.currentPosition
        scope.launch {
            // a video opened on the player screen must stay a video (picture + sound); queue songs are audio
            val video = id == PlaybackService.lastViewer
            val urls = withContext(Dispatchers.IO) {
                StreamResolver.forget(id)
                val yt = runCatching { YTDLPUtil(app, DBManager.getInstance(app).commandTemplateDao).getStreamingUrlAndChapters(id, !video).getOrNull()?.first.orEmpty() }.getOrDefault(emptyList()).filter { it.isNotBlank() }
                if (yt.isNotEmpty()) yt else StreamResolver.resolve(app, id, !video, true)
            }
            val idx = p.currentMediaItemIndex
            if (urls.isEmpty() || p.currentMediaItem?.mediaId != id) {
                if (p.hasNextMediaItem()) { p.seekToNextMediaItem(); p.prepare(); p.play() }
                else Toast.makeText(app, app.getString(R.string.o_load_fail), Toast.LENGTH_LONG).show()
                return@launch
            }
            val factory = DefaultMediaSourceFactory(VideoPlayerUtil.cacheFactory(app))
            val first = factory.createMediaSource(MediaItem.Builder().setMediaId(id).setUri(Uri.parse(urls[0])).setMediaMetadata(cur.mediaMetadata).build())
            val src = if (urls.size >= 2) MergingMediaSource(first, factory.createMediaSource(MediaItem.fromUri(Uri.parse(urls[1])))) else first
            p.removeMediaItem(idx)
            p.addMediaSource(idx, src)
            p.seekTo(idx, pos); p.prepare(); p.play()
        }
    }

    fun isPending(item: MediaItem?) = item?.localConfiguration?.uri?.scheme == PENDING

    /** Called by the playback service once its player exists. */
    fun attach(ctx: Context, p: Player) {
        app = ctx.applicationContext
        if (attachedTo === p) return
        attachedTo?.removeListener(listener)
        attachedTo = p
        p.addListener(listener)
    }

    private fun pendingItem(ref: MediaRef): MediaItem = MediaItem.Builder()
        .setMediaId(ref.url).setUri(Uri.parse("$PENDING://item"))
        .setMediaMetadata(MediaMetadata.Builder().setTitle(ref.title).setArtist(ref.author)
            .apply { if (ref.thumb.isNotBlank()) setArtworkUri(Uri.parse(ref.thumb)) }.build()).build()

    /** Adds a song to the queue (next = right after the current one). Starts playback when nothing is playing. */
    fun add(ctx: Context, ref: MediaRef, next: Boolean) {
        val p = PlaybackService.player
        if (p == null || p.mediaItemCount == 0) { playNow(ctx, ref); return }
        attach(ctx, p)
        val idx = if (next) p.currentMediaItemIndex + 1 else p.mediaItemCount
        p.addMediaItem(idx.coerceIn(0, p.mediaItemCount), pendingItem(ref))
        Toast.makeText(ctx, ctx.getString(R.string.o_added_to_queue), Toast.LENGTH_SHORT).show()
        prefetch()
    }

    /** Plays the song as audio right away in the background player. */
    fun playNow(ctx: Context, ref: MediaRef) {
        app = ctx.applicationContext
        StartupTrace.begin("background")
        scope.launch {
            val urls = withContext(Dispatchers.IO) { resolve(ref.url) }
            if (urls.isEmpty()) { Toast.makeText(app, app.getString(R.string.o_load_fail), Toast.LENGTH_LONG).show(); return@launch }
            PlaybackService.playStream(app, urls, ref.title, ref.author, ref.thumb, ref.url)
            Handler(Looper.getMainLooper()).postDelayed({ PlaybackService.player?.let { attach(app, it) } }, 600)
        }
    }

    /** Background playback is audio, so only the audio stream is resolved (cached, with a time-boxed fallback). */
    private suspend fun resolve(url: String): List<String> = StreamResolver.resolve(app, url, true, true)

    private fun wasCurrentFail(p: Player, idx: Int) = starting // a failed load while the user is waiting ends the waiting state

    /** Resolves the next PRELOAD songs that are still placeholders. */
    fun prefetch() {
        if (suspended) return
        val p = PlaybackService.player ?: return
        val start = p.currentMediaItemIndex
        for (k in 1..PRELOAD) {
            val i = start + k
            if (i >= p.mediaItemCount) break
            val item = p.getMediaItemAt(i)
            if (isPending(item)) load(item.mediaId)
        }
    }

    /** If the current song is still a placeholder (skipped ahead quickly), resolve it now. */
    private fun fixCurrent() {
        if (suspended) return
        val p = PlaybackService.player ?: return
        val cur = p.currentMediaItem ?: return
        if (isPending(cur)) load(cur.mediaId)
    }

    private fun load(mediaId: String) {
        if (!resolving.add(mediaId)) return
        scope.launch {
            val urls = withContext(Dispatchers.IO) { resolve(mediaId) }
            resolving.remove(mediaId)
            val p = PlaybackService.player as? ExoPlayer ?: return@launch
            var idx = -1
            for (i in 0 until p.mediaItemCount) if (p.getMediaItemAt(i).mediaId == mediaId && isPending(p.getMediaItemAt(i))) { idx = i; break }
            if (idx < 0) return@launch
            if (urls.isEmpty()) {
                // could not load: drop it so the queue keeps moving
                p.removeMediaItem(idx)
                if (wasCurrentFail(p, idx)) setStarting(false)
                Toast.makeText(app, app.getString(R.string.o_load_fail), Toast.LENGTH_SHORT).show()
                prefetch(); return@launch
            }
            val old = p.getMediaItemAt(idx)
            val wasCurrent = idx == p.currentMediaItemIndex
            val factory = DefaultMediaSourceFactory(VideoPlayerUtil.cacheFactory(app))
            val first = factory.createMediaSource(MediaItem.Builder().setMediaId(mediaId).setUri(Uri.parse(urls[0])).setMediaMetadata(old.mediaMetadata).build())
            val src = if (urls.size >= 2) MergingMediaSource(first, factory.createMediaSource(MediaItem.fromUri(Uri.parse(urls[1])))) else first
            p.removeMediaItem(idx)
            p.addMediaSource(idx, src)
            if (wasCurrent) { p.seekTo(idx, resumePos.remove(mediaId) ?: 0L); p.prepare(); p.play() }
            prefetch()
        }
    }

    /** Adds a downloaded (local) file to the queue. */
    fun addLocal(ctx: Context, ref: MediaRef, path: String) {
        app = ctx.applicationContext
        val uri = Uri.fromFile(java.io.File(path))
        val p = PlaybackService.player
        if (p == null || p.mediaItemCount == 0) { PlaybackService.playStream(app, listOf(uri.toString()), ref.title, ref.author, ref.thumb); return }
        p.addMediaItem(MediaItem.Builder().setMediaId(path).setUri(uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(ref.title).setArtist(ref.author)
                .apply { if (ref.thumb.isNotBlank()) setArtworkUri(Uri.parse(ref.thumb)) }.build()).build())
        Toast.makeText(ctx, ctx.getString(R.string.o_added_to_queue), Toast.LENGTH_SHORT).show()
    }

    /** Puts a removed item back (used by Undo). */
    fun reinsert(item: MediaItem, idx: Int) {
        val p = PlaybackService.player ?: return
        val it = if (item.localConfiguration != null) item else MediaItem.Builder().setMediaId(item.mediaId)
            .setUri(Uri.parse("$PENDING://item")).setMediaMetadata(item.mediaMetadata).build()
        p.addMediaItem(idx.coerceIn(0, p.mediaItemCount), it)
        prefetch()
    }

    /** Shuffles only the songs after the current one. */
    fun shuffleUpNext() {
        val p = PlaybackService.player ?: return
        val first = p.currentMediaItemIndex + 1
        var n = p.mediaItemCount - first
        while (n > 1) { val j = (0 until n).random(); p.moveMediaItem(first + j, p.mediaItemCount - 1); n-- }
        prefetch()
    }

    /** Menu shown from the ⋮ button or a long press on a song row. */
    fun menu(ctx: Context, ref: MediaRef, onDownload: (() -> Unit)? = null) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val dlg = com.google.android.material.bottomsheet.BottomSheetDialog(ctx)
        val col = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF262630.toInt()); cornerRadii = floatArrayOf(26 * d, 26 * d, 26 * d, 26 * d, 0f, 0f, 0f, 0f) }
            setPadding(0, dp(6), 0, dp(18))
            layoutDirection = android.view.View.LAYOUT_DIRECTION_LOCALE
        }
        // header: artwork, title, channel
        val head = android.widget.LinearLayout(ctx).apply { gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(10), dp(18), dp(14)) }
        val art = android.widget.ImageView(ctx).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; clipToOutline = true
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 10 * d; setColor(0xFF3A3A46.toInt()) }
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        if (ref.thumb.isNotBlank()) runCatching { com.squareup.picasso.Picasso.get().load(ref.thumb).resize(240, 0).onlyScaleDown().into(art) }
        head.addView(art, android.widget.LinearLayout.LayoutParams(dp(84), dp(48)))
        val tx = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL }
        tx.addView(android.widget.TextView(ctx).apply { text = ref.title; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; textSize = 14f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(-1) })
        if (ref.author.isNotBlank()) tx.addView(android.widget.TextView(ctx).apply { text = ref.author; maxLines = 1; textSize = 12f; setTextColor(0xFF9A9AAA.toInt()) })
        head.addView(tx, android.widget.LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        col.addView(head)
        col.addView(android.view.View(ctx).apply { setBackgroundColor(0xFF3A3A46.toInt()) }, android.widget.LinearLayout.LayoutParams(-1, 1))
        fun act(a: () -> Unit) = { dlg.dismiss(); a() }
        // two quick actions
        val chips = android.widget.LinearLayout(ctx).apply { setPadding(dp(16), dp(14), dp(16), dp(6)) }
        fun chip(icon: Int, label: String, color: Int, a: () -> Unit, first: Boolean) {
            val c = android.widget.TextView(ctx).apply {
                text = label; textSize = 14f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(0xFF14141B.toInt())
                gravity = android.view.Gravity.CENTER; maxLines = 2; setPadding(dp(10), dp(12), dp(10), dp(12))
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 16 * d; setColor(color) }
                OIcons.start(this, icon, 0xFF14141B.toInt(), 22, 8)
                setOnClickListener { act(a)() }
            }
            Press.card(c, 16f)
            chips.addView(c, android.widget.LinearLayout.LayoutParams(0, -2, 1f).apply { if (!first) marginStart = dp(10) })
        }
        chip(R.drawable.ic_o_headphones, ctx.getString(R.string.o_play_audio_now), 0xFFFF8A65.toInt(), { playNow(ctx, ref) }, true)
        if (onDownload != null) chip(R.drawable.ic_o_download, ctx.getString(R.string.o_download), 0xFFFFD54F.toInt(), onDownload, false)
        col.addView(chips)
        fun row(icon: Int, label: String, color: Int, a: () -> Unit) {
            val r = android.widget.LinearLayout(ctx).apply { gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(10), dp(18), dp(10)) }
            val ib = android.widget.ImageView(ctx).apply {
                setImageResource(icon); setColorFilter(color); scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE; setPadding(dp(9), dp(9), dp(9), dp(9))
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 12 * d; setColor((color and 0x00FFFFFF) or 0x26000000) }
            }
            r.addView(ib, android.widget.LinearLayout.LayoutParams(dp(40), dp(40)))
            r.addView(android.widget.TextView(ctx).apply { text = label; textSize = 15f; setTextColor(-1) }, android.widget.LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
            r.setOnClickListener { act(a)() }
            Press.card(r, 12f)
            col.addView(r)
        }
        row(R.drawable.ic_o_skip_next, ctx.getString(R.string.o_play_next), 0xFF8E7CFF.toInt()) { add(ctx, ref, true) }
        row(R.drawable.ic_o_queue_add, ctx.getString(R.string.o_add_to_queue), 0xFF4DB6FF.toInt()) { add(ctx, ref, false) }
        row(R.drawable.ic_o_playlist_add, ctx.getString(R.string.play_add_to_playlist), 0xFF5BD6A0.toInt()) { PlaylistPicker.show(ctx, listOf(ref)) }
        if (ShareLink.canShare(ref.url)) row(R.drawable.baseline_share_24, ctx.getString(R.string.share), 0xFFFF8AC8.toInt()) { ShareLink.share(ctx, ref.url, ref.title) }
        dlg.setContentView(col)
        dlg.window?.findViewById<android.view.View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        dlg.show()
    }
}
