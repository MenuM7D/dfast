package dfast.app.util

import android.content.Context
import android.os.SystemClock
import dfast.app.database.DBManager
import dfast.app.util.Extensions.getIDFromYoutubeURL
import dfast.app.util.Extensions.isYoutubeURL
import dfast.app.util.extractors.newpipe.NewPipeUtil
import dfast.app.util.extractors.ytdlp.YTDLPUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves the stream URL(s) to play a page URL.
 *  - audioOnly = true returns ONE audio url (no video stream is opened for music);
 *    audioOnly = false returns [video up to 720p, audio] as before.
 *  - results are remembered for 25 minutes (stream urls live for hours), so replaying or looping is instant.
 *  - NewPipe goes first as before. If it is only slow (no answer after 9 s) yt-dlp is asked too and the first
 *    answer wins, instead of waiting up to the 30 s network timeout. A NewPipe failure goes to yt-dlp at once, as before.
 */
object StreamResolver {
    private const val TTL_MS = 25 * 60_000L
    private const val PATIENCE_MS = 9_000L
    private class Entry(val urls: List<String>, val at: Long)
    private val cache = ConcurrentHashMap<String, Entry>()
    private fun key(url: String, audioOnly: Boolean) = (if (audioOnly) "a|" else "v|") + url

    // ---- videos whose NewPipe links did not play (age-restricted / login-required): go to yt-dlp directly next time ----
    private const val BAD_TTL_MS = 24 * 3_600_000L
    private fun badKey(url: String) = url.getIDFromYoutubeURL() ?: url
    private val badMem = ConcurrentHashMap<String, Long>()
    fun markNewPipeBad(url: String) { if (url.isYoutubeURL()) badMem[badKey(url)] = System.currentTimeMillis() }
    fun newPipeBad(url: String): Boolean { val t = badMem[badKey(url)] ?: return false; return System.currentTimeMillis() - t < BAD_TTL_MS }

    // ---- one yt-dlp format listing per video (with the link of every format), shared by the quality labels and every pick ----
    private class FEntry(val formats: List<dfast.app.database.models.Format>, val at: Long)
    private val fCache = ConcurrentHashMap<String, FEntry>()
    private val fJobs = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<List<dfast.app.database.models.Format>>>()
    private val fScope = CoroutineScope(Dispatchers.IO)

    suspend fun ytdlpFormats(ctx: Context, url: String): List<dfast.app.database.models.Format> {
        fCache[url]?.let { if (SystemClock.elapsedRealtime() - it.at < TTL_MS) return it.formats }
        val app = ctx.applicationContext
        val job = fJobs.getOrPut(url) {
            fScope.async {
                try { YTDLPUtil(app, DBManager.getInstance(app).commandTemplateDao).getFormats(url, false) } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; emptyList() }
                    .also { if (it.isNotEmpty()) fCache[url] = FEntry(it, SystemClock.elapsedRealtime()); fJobs.remove(url) }
            }
        }
        return withTimeoutOrNull(45_000) { job.await() }.orEmpty()
    }

    /** [video link, audio link] (or one muxed link) for exactly this label, from the listing; empty when the listing has none. */
    private fun pairFor(fs: List<dfast.app.database.models.Format>, label: Int): List<String> {
        fun ok(f: dfast.app.database.models.Format) = !f.url.isNullOrBlank() && !f.url!!.contains(".m3u8") && !f.url!!.contains("/manifest/")
        fun tbr(f: dfast.app.database.models.Format) = f.tbr?.toDoubleOrNull() ?: 0.0
        val vids = fs.filter { ok(it) && it.vcodec.isNotBlank() && it.vcodec != "none" && (it.height ?: 0) > 0 && QualityLabels.standard(it.height ?: 0, it.format_note) == label }
        if (vids.isEmpty()) return emptyList()
        fun avc(f: dfast.app.database.models.Format) = f.vcodec.startsWith("avc", true) || f.vcodec.contains("h264", true)
        val only = vids.filter { it.acodec.isBlank() || it.acodec == "none" }
        val audios = fs.filter { ok(it) && it.acodec.isNotBlank() && it.acodec != "none" && (it.vcodec.isBlank() || it.vcodec == "none") }
        val audio = audios.sortedWith(compareByDescending<dfast.app.database.models.Format> { it.container == "m4a" || it.acodec.startsWith("mp4a") }.thenByDescending { tbr(it) }).firstOrNull()
        if (only.isNotEmpty() && audio != null) {
            val v = only.sortedWith(compareByDescending<dfast.app.database.models.Format> { avc(it) }.thenByDescending { tbr(it) }).first()
            return listOf(v.url!!, audio.url!!)
        }
        val muxed = vids.filter { !(it.acodec.isBlank() || it.acodec == "none") }.maxByOrNull { tbr(it) }
        return if (muxed != null) listOf(muxed.url!!) else emptyList()
    }

    fun forget(url: String) { fCache.remove(url);  NewPipeUtil.forgetInfo(url); for (a in listOf(true, false)) for (y in listOf("", "|y", "|p")) cache.remove(key(url, a) + y); cache.keys.filter { it.endsWith(url) || it.contains("$url|h") }.forEach { cache.remove(it) } }

    private suspend fun viaYtdlp(app: Context, url: String, audioOnly: Boolean, progressive: Boolean = false): List<String> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        runCatching { YTDLPUtil(app, DBManager.getInstance(app).commandTemplateDao).getStreamingUrlAndChapters(url, audioOnly, progressive).getOrNull()?.first.orEmpty() }
            .getOrDefault(emptyList()).filter { it.isNotBlank() }
    }

    /** A chosen quality (standard label > 0): NewPipe first, exactly that label; if NewPipe has none, yt-dlp is asked for it. Empty when neither has it; the caller then keeps/returns to the normal pick. */
    suspend fun resolveHeight(ctx: Context, url: String, height: Int, skipNewPipe: Boolean = false, newPipeOnly: Boolean = false): List<String> {
        val k = key(url, false) + "|h" + height + (if (skipNewPipe) "|y" else "")
        cache[k]?.let { if (SystemClock.elapsedRealtime() - it.at < TTL_MS) return it.urls }
        val app = ctx.applicationContext
        var r = if (skipNewPipe) emptyList() else withTimeoutOrNull(PATIENCE_MS + 6_000) {
            kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { NewPipeUtil(app).getStreamingUrlAndChapters(url, false, height) }.getOrNull()?.getOrNull()?.first.orEmpty().filter { it.isNotBlank() } }
        }.orEmpty()
        android.util.Log.d("DFastQuality", "pick ${height}p: newpipe urls=${r.size}")
        if (r.size < 2 && (skipNewPipe || !newPipeOnly)) {
            // the listing yt-dlp gave for the labels already holds every link: no second extraction
            val l = pairFor(ytdlpFormats(ctx, url), height)
            android.util.Log.d("DFastQuality", "pick ${height}p: yt-dlp listing urls=${l.size}")
            if (l.size > r.size) r = l
        }
        if (r.size < 2 && !newPipeOnly) {
            val again = withTimeoutOrNull(25_000) {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    runCatching { YTDLPUtil(app, DBManager.getInstance(app).commandTemplateDao).getStreamingUrlAndChapters(url, false, false, height).getOrNull()?.first.orEmpty() }
                        .getOrDefault(emptyList()).filter { it.isNotBlank() }
                }
            }.orEmpty()
            if (again.size > r.size) r = again
            android.util.Log.d("DFastQuality", "pick ${height}p: yt-dlp urls=${again.size}")
        }
        if (r.size >= 2 || (r.isNotEmpty() && !newPipeOnly)) cache[k] = Entry(r, SystemClock.elapsedRealtime())
        return r
    }

    /** Quality labels yt-dlp knows for this video; empty on any failure. */
    suspend fun ytdlpLabels(ctx: Context, url: String): List<Int> {
        val fs = ytdlpFormats(ctx, url)
        return QualityLabels.ascending(fs.filter { it.vcodec.isNotBlank() && it.vcodec != "none" && (it.height ?: 0) > 0 }.map { QualityLabels.standard(it.height ?: 0, it.format_note) })
    }

    suspend fun resolve(ctx: Context, url: String, audioOnly: Boolean, newPipeForAll: Boolean, skipNewPipe: Boolean = false, progressive: Boolean = false): List<String> {
        val k = key(url, audioOnly) + (if (progressive) "|p" else if (skipNewPipe) "|y" else "")
        cache[k]?.let { if (SystemClock.elapsedRealtime() - it.at < TTL_MS) { StartupTrace.mark("resolve-end", "cache"); return it.urls } }
        StartupTrace.mark("resolve-start")
        val app = ctx.applicationContext
        val scope = CoroutineScope(Dispatchers.IO) // detached: a slow NewPipe call can be abandoned without blocking us
        var urls: List<String> = emptyList()
        var via = ""
        var triedYtdlp = false
        if (!progressive && !skipNewPipe && (newPipeForAll || url.isYoutubeURL())) {
            val np = scope.async {
                runCatching { NewPipeUtil(app).getStreamingUrlAndChapters(url, audioOnly) }.getOrNull()?.getOrNull()?.first.orEmpty().filter { it.isNotBlank() }
            }
            val r = withTimeoutOrNull(PATIENCE_MS) { np.await() }
            if (!r.isNullOrEmpty()) { urls = r; via = "newpipe" }
            else if (r == null) {
                // slow, not failed: ask yt-dlp as well, first answer wins
                triedYtdlp = true
                val yt = scope.async { viaYtdlp(app, url, audioOnly) }
                val first = select<Pair<String, List<String>>> { np.onAwait { "newpipe" to it }; yt.onAwait { "ytdlp" to it } }
                if (first.second.isNotEmpty()) { urls = first.second; via = first.first }
                else {
                    val other = if (first.first == "newpipe") yt.await() else np.await()
                    if (other.isNotEmpty()) { urls = other; via = if (first.first == "newpipe") "ytdlp" else "newpipe" }
                }
            }
        }
        if (urls.isEmpty() && !triedYtdlp) {
            val y = viaYtdlp(app, url, audioOnly, progressive)
            if (y.isNotEmpty()) { urls = y; via = if (progressive) "ytdlp-progressive" else "ytdlp" }
        }
        StartupTrace.mark("resolve-end", via.ifEmpty { "failed" })
        if (urls.isNotEmpty()) cache[k] = Entry(urls, SystemClock.elapsedRealtime())
        return urls
    }
}
