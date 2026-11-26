package dfast.app.util

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Keeps the background play queue across app restarts.
 *
 * What is saved: for every queued song its id (the page URL for streamed songs, the file for local ones),
 * title, artist and artwork, plus the current song, the position in it, shuffle and repeat.
 * Stream URLs are NOT saved (they expire): a streamed song comes back as a placeholder and is
 * resolved again when played, exactly like a song added with "Add to queue".
 * Only the close (x) button wipes the saved queue.
 */
object QueueStore {
    private const val FILE = "dfast_queue"
    private const val KEY = "queue_v1"
    private val main = Handler(Looper.getMainLooper())
    private var tracked: Player? = null
    private var appCtx: Context? = null

    data class Saved(val items: List<MediaItem>, val index: Int, val positionMs: Long, val shuffle: Boolean, val repeat: Int)

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun hasSaved(c: Context): Boolean = runCatching { JSONObject(prefs(c).getString(KEY, null) ?: return false).getJSONArray("items").length() > 0 }.getOrDefault(false)

    fun clear(c: Context) { main.removeCallbacks(saveRun); prefs(c).edit().remove(KEY).apply() }

    private val saveRun = Runnable { val c = appCtx; val p = tracked; if (c != null && p != null) saveNow(c, p) }

    private fun debounced() { main.removeCallbacks(saveRun); main.postDelayed(saveRun, 700) }

    private val ticker = object : Runnable {
        override fun run() {
            val p = tracked ?: return
            if (p.isPlaying) { saveRun.run(); main.postDelayed(this, 5000) }
        }
    }

    private val listener = object : Player.Listener {
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = debounced()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = debounced()
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = debounced()
        override fun onRepeatModeChanged(repeatMode: Int) = debounced()
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) = debounced()
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            debounced()
            main.removeCallbacks(ticker)
            if (isPlaying) main.postDelayed(ticker, 5000)
        }
    }

    /** Start saving whenever the queue, the current song, the position, shuffle or repeat change. */
    fun track(c: Context, p: Player) {
        appCtx = c.applicationContext
        tracked?.removeListener(listener)
        tracked = p
        p.addListener(listener)
    }

    fun untrack() {
        main.removeCallbacks(saveRun); main.removeCallbacks(ticker)
        tracked?.removeListener(listener); tracked = null
    }

    fun saveNow(c: Context, p: Player) {
        runCatching {
            val arr = JSONArray()
            var index = p.currentMediaItemIndex
            var kept = 0
            for (i in 0 until p.mediaItemCount) {
                val it = p.getMediaItemAt(i)
                val o = JSONObject()
                val id = it.mediaId
                val uri = it.localConfiguration?.uri
                if (id.startsWith("http")) {
                    o.put("id", id) // streamed song: identified by its page URL
                } else if (uri != null && (uri.scheme == "file" || uri.scheme == "content") && (uri.scheme != "file" || File(uri.path.orEmpty()).exists())) {
                    o.put("id", id).put("uri", uri.toString())
                } else {
                    if (i < p.currentMediaItemIndex) index-- else if (i == p.currentMediaItemIndex) index = -1
                    continue
                }
                o.put("title", it.mediaMetadata.title?.toString().orEmpty())
                o.put("artist", it.mediaMetadata.artist?.toString().orEmpty())
                o.put("art", it.mediaMetadata.artworkUri?.toString().orEmpty())
                arr.put(o); kept++
            }
            if (kept == 0) { clear(c); return }
            val root = JSONObject()
                .put("items", arr)
                .put("index", index.coerceIn(0, kept - 1))
                .put("pos", if (index >= 0) p.currentPosition.coerceAtLeast(0L) else 0L)
                .put("shuffle", p.shuffleModeEnabled)
                .put("repeat", p.repeatMode)
            prefs(c).edit().putString(KEY, root.toString()).apply()
        }
    }

    fun load(c: Context): Saved? = runCatching {
        val root = JSONObject(prefs(c).getString(KEY, null) ?: return null)
        val arr = root.getJSONArray("items")
        val items = ArrayList<MediaItem>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val md = MediaMetadata.Builder().setTitle(o.optString("title")).setArtist(o.optString("artist"))
                .apply { o.optString("art").takeIf { it.isNotBlank() }?.let { setArtworkUri(Uri.parse(it)) } }.build()
            val b = MediaItem.Builder().setMediaId(o.getString("id")).setMediaMetadata(md)
            val uri = o.optString("uri")
            if (uri.isNotBlank()) {
                if (uri.startsWith("file") && !File(Uri.parse(uri).path.orEmpty()).exists()) continue
                b.setUri(Uri.parse(uri))
            } else b.setUri(Uri.parse("${PlayQueue.PENDING}://item"))
            items.add(b.build())
        }
        if (items.isEmpty()) return null
        Saved(items, root.optInt("index", 0).coerceIn(0, items.size - 1), root.optLong("pos", 0L), root.optBoolean("shuffle", false), root.optInt("repeat", 0))
    }.getOrNull()
}
