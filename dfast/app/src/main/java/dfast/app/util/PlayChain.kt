package dfast.app.util

import android.content.Context
import androidx.media3.common.Player
import dfast.app.services.PlaybackService
import dfast.app.ui.play.PlayerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The related chain, owned by the process and not by the player screen. The screen publishes what it is playing
 * (song, playlist, related pool, shuffle, mode); when the screen is closed the media card keeps walking the same chain
 * from this snapshot. It only applies while the service still plays exactly the published song (same media id) and holds
 * a single song: any other source (the background queue, the Music tab, another song) is left to the old behaviour.
 */
object PlayChain {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var app: Context? = null
    @Volatile private var mediaId = ""
    @Volatile private var cur: MediaRef? = null
    @Volatile private var queue: List<MediaRef> = emptyList()
    @Volatile private var pool: List<MediaRef> = emptyList()
    @Volatile private var audio = false
    @Volatile private var shuffle = false
    @Volatile private var busy = false

    fun publish(ctx: Context, playing: String, ref: MediaRef, q: List<MediaRef>, relatedPool: List<MediaRef>, audioOnly: Boolean, shuffleOn: Boolean) {
        app = ctx.applicationContext; mediaId = playing; cur = ref; queue = q; pool = relatedPool; audio = audioOnly; shuffle = shuffleOn
    }
    fun clear() { mediaId = ""; cur = null; queue = emptyList(); pool = emptyList() }

    /** true when the snapshot describes exactly what the service is playing now */
    fun owns(p: Player?): Boolean {
        val c = cur ?: return false
        val item = p?.currentMediaItem ?: return false
        return app != null && mediaId.isNotEmpty() && item.mediaId == mediaId && c.url.startsWith("http") && p.mediaItemCount <= 1
    }

    fun next(p: Player?): Boolean {
        if (!owns(p)) return false
        go(true); return true
    }
    fun prev(p: Player?): Boolean {
        if (!owns(p)) return false
        val c = cur ?: return false
        val back = PlayerActivity.history.canBack() || queue.indexOfFirst { it.url == c.url } > 0
        if (!back) return false
        go(false); return true
    }

    /**
     * End of a song with no player screen in front (screen closed, app in background, phone locked): continue the chain the way
     * a music app does. Same rules as the screen: switch on, repeat off, not idle past the limit. Returns true when it took over.
     */
    fun onSongEnded(p: Player?): Boolean {
        val ctx = app ?: return false
        if (PlayNavHook.screenFront || !owns(p) || p?.repeatMode != Player.REPEAT_MODE_OFF) return false
        val on = androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean("autoplay_next", true)
        val metered = runCatching { (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).isActiveNetworkMetered }.getOrDefault(false)
        if (!AutoplayPick.allowed(on, false, System.currentTimeMillis(), AutoplayState.lastTouchMs, AutoplayPick.limitFor(metered))) return false
        go(true); return true
    }

    private fun go(forward: Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val c = cur ?: return@launch
                val ctx = app ?: return@launch
                val target: MediaRef? = if (forward) pickNext(c) else (if (PlayerActivity.history.canBack()) PlayerActivity.history.pop() else queue.getOrNull(queue.indexOfFirst { it.url == c.url } - 1))
                if (target == null) return@launch
                val urls = try { StreamResolver.resolve(ctx, target.url, false, false).filter { it.isNotBlank() } } catch (e: Exception) { emptyList() }
                if (urls.isEmpty()) return@launch
                if (forward) PlayerActivity.history.push(c)
                val t = target.copy(audio = audio)
                cur = t; mediaId = t.url; pool = emptyList()
                PlaybackService.playStream(ctx, urls, t.title, t.author, t.thumb, t.url, viewer = true)
            } finally { busy = false }
        }
    }

    private suspend fun pickNext(c: MediaRef): MediaRef? {
        val idx = queue.indexOfFirst { it.url == c.url }
        if (idx >= 0 && queue.size > 1) return if (shuffle) queue.filter { it.url != c.url }.randomOrNull() else queue.getOrNull(idx + 1)
        val related: List<AutoCand> = withTimeoutOrNull(9000) {
            withContext(Dispatchers.IO) {
                val vid = UrlIds.of(c.url)
                if (vid.isNotEmpty()) runCatching { YtRelated.fetch(vid) }.getOrDefault(emptyList()) else emptyList()
            }
        }.orEmpty()
        val local = pool.map { AutoCand(it.url, it.title, it.author, it.thumb, AutoplayPick.parseDuration(it.duration), false) }
        val dur = (PlaybackService.player?.duration ?: 0L).let { if (it > 0) it / 1000 else 0L }
        val pick = AutoplayPick.pick(related, local, c.url, AutoplayState.recent(), AutoplayPick.Ctx(c.title, c.author, dur),
            AutoplayPick.Opts(music = audio || c.author.endsWith("- Topic"), recentAuthors = AutoplayState.recentAuthors(),
                recentTitles = AutoplayState.recentTitles(), shuffle = shuffle))
        if (pick != null) return MediaRef(pick.url, pick.title, pick.author, pick.thumb, "")
        return pool.firstOrNull { it.url != c.url } ?: queue.firstOrNull { it.url != c.url }
    }
}
