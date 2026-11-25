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
import dfast.app.util.extractors.newpipe.NewPipeUtil
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
    private const val PENDING = "dfast-pending"
    const val PRELOAD = 2
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val resolving = HashSet<String>()
    private var attachedTo: Player? = null
    private lateinit var app: Context

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { prefetch(); fixCurrent() }
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) { prefetch() }
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) { if (isPending(PlaybackService.player?.currentMediaItem)) fixCurrent() else recover() }
    }

    private val retried = HashMap<String, Long>()

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
            val urls = withContext(Dispatchers.IO) {
                val yt = runCatching { YTDLPUtil(app, DBManager.getInstance(app).commandTemplateDao).getStreamingUrlAndChapters(id).getOrNull()?.first.orEmpty() }.getOrDefault(emptyList()).filter { it.isNotBlank() }
                if (yt.isNotEmpty()) yt else resolve(id)
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
        scope.launch {
            val urls = withContext(Dispatchers.IO) { resolve(ref.url) }
            if (urls.isEmpty()) { Toast.makeText(app, app.getString(R.string.o_load_fail), Toast.LENGTH_LONG).show(); return@launch }
            PlaybackService.playStream(app, urls, ref.title, ref.author, ref.thumb, ref.url)
            Handler(Looper.getMainLooper()).postDelayed({ PlaybackService.player?.let { attach(app, it) } }, 600)
        }
    }

    private suspend fun resolve(url: String): List<String> {
        val np = runCatching { NewPipeUtil(app).getStreamingUrlAndChapters(url) }.getOrNull()?.getOrNull()?.first
        if (!np.isNullOrEmpty()) return np.filter { it.isNotBlank() }
        return runCatching { YTDLPUtil(app, DBManager.getInstance(app).commandTemplateDao).getStreamingUrlAndChapters(url).getOrNull()?.first.orEmpty() }
            .getOrDefault(emptyList()).filter { it.isNotBlank() }
    }

    /** Resolves the next PRELOAD songs that are still placeholders. */
    fun prefetch() {
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
            if (wasCurrent) { p.seekTo(idx, 0); p.prepare(); p.play() }
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
        val labels = ArrayList<String>(); val acts = ArrayList<() -> Unit>()
        labels.add(ctx.getString(R.string.o_play_next)); acts.add { add(ctx, ref, true) }
        labels.add(ctx.getString(R.string.o_add_to_queue)); acts.add { add(ctx, ref, false) }
        labels.add(ctx.getString(R.string.o_play_audio_now)); acts.add { playNow(ctx, ref) }
        labels.add(ctx.getString(R.string.play_add_to_playlist)); acts.add { PlaylistPicker.show(ctx, listOf(ref)) }
        if (onDownload != null) { labels.add(ctx.getString(R.string.o_download)); acts.add { onDownload() } }
        androidx.appcompat.app.AlertDialog.Builder(ctx).setTitle(ref.title).setItems(labels.toTypedArray()) { _, w -> acts[w]() }.show()
    }
}
