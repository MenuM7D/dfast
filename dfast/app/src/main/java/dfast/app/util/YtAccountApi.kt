package dfast.app.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Real YouTube subscribe/unsubscribe and the list of subscribed channels, through the signed-in
 * session cookies (YouTube's own web requests, not an official API). Needs testing on a device.
 */
object YtAccountApi {
    data class Chan(val id: String, val name: String, val thumb: String)

    private var subs: List<Chan>? = null
    private val subIds = HashSet<String>()

    fun isSubscribed(id: String) = subIds.contains(id)
    fun cachedSubs(): List<Chan>? = subs
    fun clear() { subs = null; subIds.clear() }

    private fun cookies(c: Context): Map<String, String> {
        val f = GoogleAccount.sessionFile(c)
        val m = HashMap<String, String>()
        if (!f.exists()) return m
        f.readLines().forEach { raw ->
            val line = raw.removePrefix("#HttpOnly_")
            if (line.isBlank() || line.startsWith("#")) return@forEach
            val p = line.split("\t")
            if (p.size >= 7 && (p[0].contains("youtube.com") || p[0].contains("google.com"))) m[p[5]] = p[6]
        }
        return m
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun post(c: Context, path: String, extra: JSONObject, host: String = "www.youtube.com", client: String = "WEB",
                     ver: String = "2.20251120.01.00", needAuth: Boolean = true): JSONObject? = try {
        val ck = cookies(c)
        val sapisid = ck["SAPISID"] ?: ck["__Secure-3PAPISID"]
        if (sapisid == null && needAuth) null else {
            val ts = System.currentTimeMillis() / 1000
            val origin = "https://$host"
            val body = extra.put("context", JSONObject().put("client", JSONObject()
                .put("clientName", client).put("clientVersion", ver).put("hl", java.util.Locale.getDefault().language.ifEmpty { "en" })))
            val conn = URL("$origin/youtubei/v1/$path?prettyPrint=false").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"; conn.doOutput = true; conn.connectTimeout = 15000; conn.readTimeout = 20000
            conn.setRequestProperty("Content-Type", "application/json")
            if (ck.isNotEmpty()) conn.setRequestProperty("Cookie", ck.entries.joinToString("; ") { "${it.key}=${it.value}" })
            if (sapisid != null) conn.setRequestProperty("Authorization", "SAPISIDHASH ${ts}_${sha1("$ts $sapisid $origin")}")
            conn.setRequestProperty("Origin", origin); conn.setRequestProperty("X-Origin", origin)
            conn.setRequestProperty("X-Goog-AuthUser", "0")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode in 200..299) JSONObject(conn.inputStream.bufferedReader().readText()) else null
        }
    } catch (e: Exception) { null }

    /** Returns true when YouTube accepted the change. */
    fun setSubscribed(c: Context, channelId: String, on: Boolean): Boolean {
        val r = post(c, if (on) "subscription/subscribe" else "subscription/unsubscribe", JSONObject().put("channelIds", JSONArray().put(channelId)))
        if (r != null) { if (on) subIds.add(channelId) else subIds.remove(channelId); subs = null }
        return r != null
    }

    private fun walk(o: Any?, out: MutableList<Chan>) {
        when (o) {
            is JSONObject -> {
                val cr = o.optJSONObject("channelRenderer")
                if (cr != null) {
                    val id = cr.optString("channelId")
                    val t = cr.optJSONObject("title")
                    val name = t?.optString("simpleText").orEmpty().ifEmpty { t?.optJSONArray("runs")?.optJSONObject(0)?.optString("text").orEmpty() }
                    val th = cr.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                    var u = th?.optJSONObject(th.length() - 1)?.optString("url").orEmpty()
                    if (u.startsWith("//")) u = "https:$u"
                    if (id.isNotEmpty()) out.add(Chan(id, name, u))
                }
                o.keys().forEach { walk(o.opt(it), out) }
            }
            is JSONArray -> for (i in 0 until o.length()) walk(o.opt(i), out)
        }
    }

    /** Subscribed channels of the signed-in account (first page). Call off the main thread. */
    fun listSubscriptions(c: Context, force: Boolean = false): List<Chan> {
        if (!force) subs?.let { return it }
        val r = post(c, "browse", JSONObject().put("browseId", "FEchannels")) ?: return emptyList()
        val out = ArrayList<Chan>()
        walk(r, out)
        val list = out.distinctBy { it.id }
        subs = list
        subIds.clear(); list.forEach { subIds.add(it.id) }
        return list
    }

    private fun txt(o: JSONObject?): String {
        if (o == null) return ""
        o.optString("simpleText").takeIf { it.isNotEmpty() }?.let { return it }
        val r = o.optJSONArray("runs") ?: return o.optString("content")
        return (0 until r.length()).joinToString("") { r.optJSONObject(it)?.optString("text").orEmpty() }
    }
    private fun lastThumb(o: JSONObject?): String {
        val th = o?.optJSONArray("thumbnails") ?: return ""
        var u = th.optJSONObject(th.length() - 1)?.optString("url").orEmpty()
        if (u.startsWith("//")) u = "https:$u"
        return u
    }

    private fun item(id: String, title: String, author: String, dur: String, thumb: String) =
        dfast.app.database.models.ResultItem(0, "https://www.youtube.com/watch?v=$id", title, author, dur,
            thumb.ifEmpty { "https://i.ytimg.com/vi/$id/hqdefault.jpg" }, "youtube", "", emptyList(), "", null)

    private fun collect(o: Any?, out: MutableList<dfast.app.database.models.ResultItem>) {
        when (o) {
            is JSONObject -> {
                for (k in listOf("videoRenderer", "compactVideoRenderer", "gridVideoRenderer", "playlistVideoRenderer")) {
                    val v = o.optJSONObject(k) ?: continue
                    val id = v.optString("videoId")
                    if (id.isNotEmpty()) out.add(item(id, txt(v.optJSONObject("title")),
                        txt(v.optJSONObject("ownerText") ?: v.optJSONObject("longBylineText") ?: v.optJSONObject("shortBylineText")),
                        txt(v.optJSONObject("lengthText")), lastThumb(v.optJSONObject("thumbnail"))))
                }
                val lv = o.optJSONObject("lockupViewModel")
                if (lv != null && lv.optString("contentType").contains("VIDEO")) {
                    val id = lv.optString("contentId")
                    val md = lv.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
                    val title = md?.optJSONObject("title")?.optString("content").orEmpty()
                    val rows = md?.optJSONObject("metadata")?.optJSONObject("contentMetadataViewModel")?.optJSONArray("metadataRows")
                    val author = rows?.optJSONObject(0)?.optJSONArray("metadataParts")?.optJSONObject(0)?.optJSONObject("text")?.optString("content").orEmpty()
                    if (id.isNotEmpty() && title.isNotEmpty()) out.add(item(id, title, author, "", "https://i.ytimg.com/vi/$id/hqdefault.jpg"))
                }
                for (k in listOf("videoWithContextRenderer", "reelItemRenderer", "playlistPanelVideoRenderer")) {
                    val v = o.optJSONObject(k) ?: continue
                    val id = v.optString("videoId")
                    if (id.isNotEmpty()) out.add(item(id, txt(v.optJSONObject("headline") ?: v.optJSONObject("title")),
                        txt(v.optJSONObject("shortBylineText") ?: v.optJSONObject("longBylineText")), txt(v.optJSONObject("lengthText")), lastThumb(v.optJSONObject("thumbnail"))))
                }
                val sl = o.optJSONObject("shortsLockupViewModel")
                if (sl != null) {
                    val id = sl.optJSONObject("onTap")?.optJSONObject("innertubeCommand")?.optJSONObject("reelWatchEndpoint")?.optString("videoId").orEmpty()
                    val t = sl.optJSONObject("overlayMetadata")?.optJSONObject("primaryText")?.optString("content").orEmpty()
                    if (id.isNotEmpty() && t.isNotEmpty()) out.add(item(id, t, "", "", "https://i.ytimg.com/vi/$id/hqdefault.jpg"))
                }
                val tr = o.optJSONObject("musicTwoRowItemRenderer")
                if (tr != null) {
                    val id = tr.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")?.optString("videoId").orEmpty()
                    if (id.isNotEmpty()) out.add(item(id, txt(tr.optJSONObject("title")), txt(tr.optJSONObject("subtitle")), "",
                        lastThumb(tr.optJSONObject("thumbnailRenderer")?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail"))))
                }
                val rl = o.optJSONObject("musicResponsiveListItemRenderer")
                if (rl != null) {
                    val id = rl.optJSONObject("playlistItemData")?.optString("videoId").orEmpty()
                    val cols = rl.optJSONArray("flexColumns")
                    fun col(i: Int) = txt(cols?.optJSONObject(i)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text"))
                    if (id.isNotEmpty()) out.add(item(id, col(0), col(1), "",
                        lastThumb(rl.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail"))))
                }
                o.keys().forEach { collect(o.opt(it), out) }
            }
            is JSONArray -> for (i in 0 until o.length()) collect(o.opt(i), out)
        }
    }

    /** Videos of a YouTube browse page (home, subscriptions, history...). Works signed out for the home feed. Call off the main thread. */
    fun videos(c: Context, browseId: String, needAuth: Boolean = true): List<dfast.app.database.models.ResultItem> {
        val r = post(c, "browse", JSONObject().put("browseId", browseId), needAuth = needAuth) ?: return emptyList()
        val out = ArrayList<dfast.app.database.models.ResultItem>()
        collect(r, out)
        var tok = webCont(r)
        var pages = 0
        while (tok.isNotEmpty() && out.size < 30 && pages < 3) {
            val r2 = post(c, "browse", JSONObject().put("continuation", tok), needAuth = needAuth) ?: break
            collect(r2, out); tok = webCont(r2); pages++
        }
        return out.distinctBy { it.url }.filter { it.title.isNotBlank() }
    }

    private fun webCont(o: Any?): String {
        when (o) {
            is JSONObject -> {
                o.optJSONObject("continuationItemRenderer")?.optJSONObject("continuationEndpoint")?.optJSONObject("continuationCommand")?.optString("token")?.takeIf { it.isNotEmpty() }?.let { return it }
                o.keys().forEach { k -> val t = webCont(o.opt(k)); if (t.isNotEmpty()) return t }
            }
            is JSONArray -> for (i in 0 until o.length()) { val t = webCont(o.opt(i)); if (t.isNotEmpty()) return t }
        }
        return ""
    }


    data class MCard(val title: String, val sub: String, val thumb: String, val playlistId: String)
    data class MShelf(val title: String, val songs: List<dfast.app.database.models.ResultItem>, val cards: List<MCard>, val list: Boolean = false)
    data class MChip(val title: String, val params: String)
    data class MPage(val chips: List<MChip>, val shelves: List<MShelf>)

    private fun mThumb(o: JSONObject?): String =
        lastThumb(o?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail") ?: o?.optJSONObject("croppedSquareThumbnailRenderer")?.optJSONObject("thumbnail"))

    private fun parseShelf(sh: JSONObject): MShelf? {
        val title = txt(sh.optJSONObject("header")?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")?.optJSONObject("title"))
        val contents = sh.optJSONArray("contents") ?: return null
        val songs = ArrayList<dfast.app.database.models.ResultItem>()
        val cards = ArrayList<MCard>()
        for (i in 0 until contents.length()) {
            val e = contents.optJSONObject(i) ?: continue
            val rl = e.optJSONObject("musicResponsiveListItemRenderer")
            if (rl != null) {
                val id = rl.optJSONObject("playlistItemData")?.optString("videoId").orEmpty()
                val cols = rl.optJSONArray("flexColumns")
                fun col(k: Int) = txt(cols?.optJSONObject(k)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text"))
                if (id.isNotEmpty()) songs.add(item(id, col(0), col(1), "", mThumb(rl.optJSONObject("thumbnail"))))
                continue
            }
            val tr = e.optJSONObject("musicTwoRowItemRenderer") ?: continue
            val nav = tr.optJSONObject("navigationEndpoint")
            val vid = nav?.optJSONObject("watchEndpoint")?.optString("videoId").orEmpty()
            val th = mThumb(tr.optJSONObject("thumbnailRenderer"))
            if (vid.isNotEmpty()) { songs.add(item(vid, txt(tr.optJSONObject("title")), txt(tr.optJSONObject("subtitle")), "", th)); continue }
            var pl = nav?.optJSONObject("watchPlaylistEndpoint")?.optString("playlistId").orEmpty()
            if (pl.isEmpty()) {
                val b = nav?.optJSONObject("browseEndpoint")?.optString("browseId").orEmpty()
                if (b.startsWith("VL")) pl = b.substring(2) else if (b.startsWith("MPRE") || b.startsWith("OLAK")) pl = b
            }
            if (pl.isNotEmpty()) cards.add(MCard(txt(tr.optJSONObject("title")), txt(tr.optJSONObject("subtitle")), th, pl))
        }
        if (title.isEmpty() || (songs.isEmpty() && cards.isEmpty())) return null
        return MShelf(title, songs, cards, list = songs.size >= 3 && cards.isEmpty() && contents.optJSONObject(0)?.has("musicResponsiveListItemRenderer") == true)
    }

    private fun shelves(o: Any?, out: MutableList<MShelf>) {
        when (o) {
            is JSONObject -> {
                val sh = o.optJSONObject("musicCarouselShelfRenderer")
                if (sh != null) parseShelf(sh)?.let { out.add(it) }
                o.keys().forEach { shelves(o.opt(it), out) }
            }
            is JSONArray -> for (i in 0 until o.length()) shelves(o.opt(i), out)
        }
    }

    private fun contToken(o: Any?): String {
        when (o) {
            is JSONObject -> {
                o.optJSONObject("nextContinuationData")?.optString("continuation")?.takeIf { it.isNotEmpty() }?.let { return it }
                o.keys().forEach { k -> val t = contToken(o.opt(k)); if (t.isNotEmpty()) return t }
            }
            is JSONArray -> for (i in 0 until o.length()) { val t = contToken(o.opt(i)); if (t.isNotEmpty()) return t }
        }
        return ""
    }

    private fun chips(o: Any?, out: MutableList<MChip>) {
        when (o) {
            is JSONObject -> {
                val ch = o.optJSONObject("chipCloudChipRenderer")
                if (ch != null) {
                    val t = txt(ch.optJSONObject("text"))
                    val pr = ch.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("params").orEmpty()
                    if (t.isNotEmpty() && pr.isNotEmpty()) out.add(MChip(t, pr))
                }
                o.keys().forEach { chips(o.opt(it), out) }
            }
            is JSONArray -> for (i in 0 until o.length()) chips(o.opt(i), out)
        }
    }

    /** YouTube Music home (signed in or not): mood chips plus personalised shelves in YouTube's order, one continuation page. Call off the main thread. */
    fun musicPage(c: Context, params: String? = null): MPage {
        val body = JSONObject().put("browseId", "FEmusic_home")
        if (!params.isNullOrEmpty()) body.put("params", params)
        val r = post(c, "browse", body, host = "music.youtube.com", client = "WEB_REMIX", ver = "1.20250120.01.00", needAuth = false) ?: return MPage(emptyList(), emptyList())
        val out = ArrayList<MShelf>(); shelves(r, out)
        val ch = ArrayList<MChip>(); chips(r, ch)
        val tok = contToken(r)
        if (tok.isNotEmpty()) {
            val r2 = post(c, "browse", JSONObject().put("continuation", tok), host = "music.youtube.com", client = "WEB_REMIX", ver = "1.20250120.01.00", needAuth = false)
            if (r2 != null) shelves(r2, out)
        }
        return MPage(ch.distinctBy { it.title }, out)
    }

    fun musicShelves(c: Context): List<MShelf> = musicPage(c).shelves

    /** Videos and songs of the signed-in YouTube Music home. */
    fun musicHome(c: Context): List<dfast.app.database.models.ResultItem> {
        val r = post(c, "browse", JSONObject().put("browseId", "FEmusic_home"), host = "music.youtube.com", client = "WEB_REMIX", ver = "1.20250120.01.00") ?: return emptyList()
        val out = ArrayList<dfast.app.database.models.ResultItem>()
        collect(r, out)
        return out.distinctBy { it.url }.filter { it.title.isNotBlank() }
    }

    private fun findKey(o: Any?, key: String): JSONObject? {
        when (o) {
            is JSONObject -> { o.optJSONObject(key)?.let { return it }; o.keys().forEach { k -> findKey(o.opt(k), key)?.let { return it } } }
            is JSONArray -> for (i in 0 until o.length()) findKey(o.opt(i), key)?.let { return it }
        }
        return null
    }

    /** Name and photo of the signed-in account, or null. Call off the main thread. */
    fun accountInfo(c: Context): Pair<String, String>? {
        val r = post(c, "account/account_menu", JSONObject()) ?: return null
        val name = txt(findKey(r, "accountName"))
        val photo = lastThumb(findKey(r, "accountPhoto"))
        return if (name.isNotBlank() || photo.isNotBlank()) name to photo else null
    }
}
