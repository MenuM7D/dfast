package dfast.app.util

import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/**
 * Channel tabs the extractor library cannot do: sort chips (Latest / Popular / Oldest) on the Videos and Live
 * tabs, and the Shows tab. It reads YouTube's own browse response and never throws: any failure or an
 * unknown shape gives an empty result, and the caller then hides the feature and keeps the normal tabs.
 */
object ChannelBrowse {
    const val TAB_VIDEOS = "EgZ2aWRlb3PyBgQKAjoA"
    const val TAB_LIVE = "EgdzdHJlYW1z8gYECgJ6AA%3D%3D"
    const val TAB_SHOWS = "EgVzaG93c_IGBAoCYgA%3D"

    class Chip(val label: String, val token: String)
    class Parsed(val streams: List<StreamInfoItem>, val shows: List<PlaylistInfoItem>, val next: String?, val chips: List<Chip>)

    private fun txt(o: JSONObject?): String {
        if (o == null) return ""
        o.optString("simpleText", "").takeIf { it.isNotEmpty() }?.let { return it }
        o.optString("content", "").takeIf { it.isNotEmpty() }?.let { return it }
        val runs = o.optJSONArray("runs") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until runs.length()) sb.append(runs.optJSONObject(i)?.optString("text", "") ?: "")
        return sb.toString()
    }

    /** "1:02:03" or "41:41" to seconds, or -1 when the text is not a duration (for example "LIVE"). */
    internal fun durationOf(s: String): Long {
        val p = s.trim().split(":")
        if (p.size !in 2..3 || p.any { it.isEmpty() || !it.all { c -> c.isDigit() } }) return -1
        return p.fold(0L) { a, b -> a * 60 + b.toLong() }
    }

    private fun stream(id: String, title: String, views: String, published: String, dur: Long, author: String): StreamInfoItem {
        val s = StreamInfoItem(ServiceList.YouTube.serviceId, "https://www.youtube.com/watch?v=$id", title,
            if (dur < 0) StreamType.LIVE_STREAM else StreamType.VIDEO_STREAM)
        s.uploaderName = author
        s.duration = dur
        if (published.isNotEmpty()) s.textualUploadDate = published
        val digits = views.takeWhile { !it.isWhitespace() }
        s.viewCount = parseCount(digits)
        return s
    }

    /** "4M" "5.5M" "214K" "1,234" to a number; -1 when unknown. */
    internal fun parseCount(t: String): Long {
        val m = Regex("^([0-9][0-9,.]*)([KMB]?)$").find(t.trim()) ?: return -1
        val n = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return -1
        val mul = when (m.groupValues[2]) { "K" -> 1e3; "M" -> 1e6; "B" -> 1e9; else -> 1.0 }
        return (n * mul).toLong()
    }

    fun parse(root: Any?, author: String = ""): Parsed {
        val streams = ArrayList<StreamInfoItem>()
        val shows = ArrayList<PlaylistInfoItem>()
        val chips = ArrayList<Chip>()
        val nexts = ArrayList<String>()
        val seen = HashSet<String>()
        fun walk(o: Any?) {
            when (o) {
                is JSONObject -> {
                    o.optJSONObject("lockupViewModel")?.let { l ->
                        if (l.optString("contentType") == "LOCKUP_CONTENT_TYPE_VIDEO") {
                            val id = l.optString("contentId")
                            val md = l.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
                            val title = txt(md?.optJSONObject("title"))
                            if (id.isNotEmpty() && title.isNotEmpty() && seen.add(id)) {
                                val parts = ArrayList<String>()
                                val rows = md?.optJSONObject("metadata")?.optJSONObject("contentMetadataViewModel")?.optJSONArray("metadataRows")
                                if (rows != null) for (i in 0 until rows.length()) {
                                    val ps = rows.optJSONObject(i)?.optJSONArray("metadataParts") ?: continue
                                    for (j in 0 until ps.length()) parts.add(txt(ps.optJSONObject(j)?.optJSONObject("text")))
                                }
                                var dur = -1L
                                val ov = l.optJSONObject("contentImage")?.optJSONObject("thumbnailViewModel")?.optJSONArray("overlays")
                                if (ov != null) for (i in 0 until ov.length()) {
                                    val bs = ov.optJSONObject(i)?.optJSONObject("thumbnailBottomOverlayViewModel")?.optJSONArray("badges") ?: continue
                                    for (j in 0 until bs.length()) {
                                        val d = durationOf(bs.optJSONObject(j)?.optJSONObject("thumbnailBadgeViewModel")?.optString("text", "") ?: "")
                                        if (d >= 0) dur = d
                                    }
                                }
                                streams.add(stream(id, title, parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" }, dur, author))
                            }
                        }
                    }
                    o.optJSONObject("videoRenderer")?.let { v ->
                        val id = v.optString("videoId"); val title = txt(v.optJSONObject("title"))
                        if (id.isNotEmpty() && title.isNotEmpty() && seen.add(id))
                            streams.add(stream(id, title, txt(v.optJSONObject("viewCountText")), txt(v.optJSONObject("publishedTimeText")), durationOf(txt(v.optJSONObject("lengthText"))), author))
                    }
                    o.optJSONObject("gridShowRenderer")?.let { g ->
                        val bid = g.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId", "") ?: ""
                        val title = txt(g.optJSONObject("title"))
                        if (bid.startsWith("VL") && title.isNotEmpty() && seen.add(bid)) {
                            val p = PlaylistInfoItem(ServiceList.YouTube.serviceId, "https://www.youtube.com/playlist?list=" + bid.removePrefix("VL"), title)
                            p.uploaderName = txt(g.optJSONObject("shortBylineText")).ifBlank { author }
                            val th = g.optJSONObject("thumbnailRenderer")?.optJSONObject("showCustomThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                            val u = th?.optJSONObject(th.length() - 1)?.optString("url", "") ?: ""
                            if (u.isNotEmpty()) p.thumbnails = listOf(Image(u, -1, -1, Image.ResolutionLevel.UNKNOWN))
                            shows.add(p)
                        }
                    }
                    o.optJSONObject("continuationItemRenderer")?.let { c ->
                        val t = c.optJSONObject("continuationEndpoint")?.optJSONObject("continuationCommand")?.optString("token", "") ?: ""
                        if (t.isNotEmpty()) nexts.add(t)
                    }
                    o.optJSONObject("chipViewModel")?.let { c ->
                        val label = c.optString("text", "")
                        val t = c.optJSONObject("tapCommand")?.optJSONObject("innertubeCommand")?.optJSONObject("continuationCommand")?.optString("token", "") ?: ""
                        if (label.isNotEmpty() && t.isNotEmpty()) chips.add(Chip(label, t))
                    }
                    val it = o.keys()
                    while (it.hasNext()) walk(o.opt(it.next()))
                }
                is JSONArray -> for (i in 0 until o.length()) walk(o.opt(i))
            }
        }
        walk(root)
        return Parsed(streams, shows, nexts.lastOrNull(), chips)
    }

    /** The three sort chips in order Latest, Popular, Oldest, or null when the page does not have exactly that. */
    fun sortChips(p: Parsed): List<Chip>? {
        val by = p.chips.associateBy { it.label }
        val l = by["Latest"]; val po = by["Popular"]; val o = by["Oldest"]
        return if (l != null && po != null && o != null) listOf(l, po, o) else null
    }

    private fun post(body: JSONObject): JSONObject? = try {
        val ctx = JSONObject().put("client", JSONObject().put("clientName", "WEB").put("clientVersion", "2.20260101.00.00").put("hl", "en").put("gl", "US"))
        body.put("context", ctx)
        val h = mapOf("Content-Type" to listOf("application/json"), "Origin" to listOf("https://www.youtube.com"))
        val r = NewPipe.getDownloader().post("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false", h, body.toString().toByteArray(Charsets.UTF_8))
        if (r.responseCode() == 200) JSONObject(r.responseBody()) else null
    } catch (e: Exception) { null }

    /** First page of a tab. The caller must use English text (hl=en) so the chip labels match. */
    fun tab(channelId: String, params: String, author: String = ""): Parsed? =
        post(JSONObject().put("browseId", channelId).put("params", java.net.URLDecoder.decode(params, "UTF-8")))?.let { parse(it, author) }

    fun more(token: String, author: String = ""): Parsed? =
        post(JSONObject().put("continuation", token))?.let { parse(it, author) }
}
