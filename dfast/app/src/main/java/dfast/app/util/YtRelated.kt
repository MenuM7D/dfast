package dfast.app.util

import org.json.JSONArray
import org.json.JSONObject

/**
 * YouTube's own "up next / related" list for one video, read from the watch-next endpoint (the same data the YouTube
 * site shows beside the video). The pure parser is unit tested against a real response shape; the fetch is the only network part.
 * Newer YouTube pages describe related videos as "lockups", older ones as "compactVideoRenderer": both are read.
 */
object YtRelated {
    private val cache = HashMap<String, Pair<Long, List<AutoCand>>>()
    private const val TTL_MS = 10 * 60 * 1000L

    fun parse(root: JSONObject, selfId: String = ""): List<AutoCand> {
        val out = ArrayList<AutoCand>()
        val seen = HashSet<String>()
        fun add(id: String, title: String, author: String, thumb: String, dur: Long, live: Boolean) {
            if (id.length != 11 || id == selfId || title.isBlank() || !seen.add(id)) return
            out.add(AutoCand("https://www.youtube.com/watch?v=$id", title, author.removeSuffix(" - Topic"), thumb, dur, live))
        }
        fun walk(o: Any?) {
            when (o) {
                is JSONObject -> {
                    val keys = o.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = o.opt(k)
                        if (k == "lockupViewModel" && v is JSONObject) lockup(v, ::add) else if (k == "compactVideoRenderer" && v is JSONObject) compact(v, ::add) else walk(v)
                    }
                }
                is JSONArray -> for (i in 0 until o.length()) walk(o.opt(i))
            }
        }
        // only the list beside the video, never the video's own description, comments or channel blocks
        val side = root.optJSONObject("contents")?.optJSONObject("twoColumnWatchNextResults")?.optJSONObject("secondaryResults")
        walk(side ?: root)
        return out
    }

    private fun lockup(v: JSONObject, add: (String, String, String, String, Long, Boolean) -> Unit) {
        if (v.optString("contentType") != "LOCKUP_CONTENT_TYPE_VIDEO") return
        val id = v.optString("contentId")
        val md = v.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
        val title = md?.optJSONObject("title")?.optString("content").orEmpty()
        val author = md?.optJSONObject("metadata")?.optJSONObject("contentMetadataViewModel")?.optJSONArray("metadataRows")
            ?.optJSONObject(0)?.optJSONArray("metadataParts")?.optJSONObject(0)?.optJSONObject("text")?.optString("content").orEmpty()
        val tv = v.optJSONObject("contentImage")?.optJSONObject("thumbnailViewModel")
        val src = tv?.optJSONObject("image")?.optJSONArray("sources")
        val thumb = src?.optJSONObject((src.length() - 1).coerceAtLeast(0))?.optString("url").orEmpty()
        var badge = ""
        val ov = tv?.optJSONArray("overlays")
        if (ov != null) for (i in 0 until ov.length()) {
            val bs = ov.optJSONObject(i)?.optJSONObject("thumbnailBottomOverlayViewModel")?.optJSONArray("badges") ?: continue
            for (j in 0 until bs.length()) { val t = bs.optJSONObject(j)?.optJSONObject("thumbnailBadgeViewModel")?.optString("text").orEmpty(); if (t.isNotEmpty()) badge = t }
        }
        val live = badge.equals("LIVE", true) || badge.equals("مباشر", true)
        add(id, title, author, thumb, if (live) 0 else clock(badge), live)
    }

    private fun compact(v: JSONObject, add: (String, String, String, String, Long, Boolean) -> Unit) {
        val id = v.optString("videoId")
        val title = v.optJSONObject("title")?.optString("simpleText").orEmpty().ifEmpty { v.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text").orEmpty() }
        val author = v.optJSONObject("longBylineText")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text").orEmpty()
        val th = v.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        val thumb = th?.optJSONObject((th.length() - 1).coerceAtLeast(0))?.optString("url").orEmpty()
        val len = v.optJSONObject("lengthText")?.optString("simpleText").orEmpty()
        add(id, title, author, thumb, clock(len), len.isEmpty())
    }

    /** "4:10" / "1:02:03" -> seconds; 0 when it is not a clock */
    fun clock(s: String): Long {
        val p = s.trim().split(":")
        if (p.isEmpty() || p.size > 3 || p.any { it.isEmpty() || !it.all(Char::isDigit) }) return 0
        return p.fold(0L) { a, x -> a * 60 + x.toLong() }
    }

    /** blocking; call off the main thread. Empty on any failure (the caller then falls back and may retry). */
    fun fetch(videoId: String): List<AutoCand> {
        if (videoId.length != 11) return emptyList()
        synchronized(cache) { cache[videoId]?.let { if (System.currentTimeMillis() - it.first < TTL_MS) return it.second } }
        val list = runCatching {
            val body = JSONObject().put("videoId", videoId).put("context", JSONObject().put("client", JSONObject()
                .put("clientName", "WEB").put("clientVersion", "2.20250120.01.00").put("hl", java.util.Locale.getDefault().language.ifEmpty { "en" })))
            val conn = java.net.URL("https://www.youtube.com/youtubei/v1/next?prettyPrint=false").openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"; conn.doOutput = true; conn.connectTimeout = 8000; conn.readTimeout = 10000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode in 200..299) parse(JSONObject(conn.inputStream.bufferedReader().readText()), videoId) else emptyList()
        }.getOrDefault(emptyList())
        if (list.isNotEmpty()) synchronized(cache) { if (cache.size > 40) cache.clear(); cache[videoId] = System.currentTimeMillis() to list }
        return list
    }
}
