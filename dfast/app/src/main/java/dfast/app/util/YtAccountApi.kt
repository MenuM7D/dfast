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

    private fun post(c: Context, path: String, extra: JSONObject): JSONObject? = try {
        val ck = cookies(c)
        val sapisid = ck["SAPISID"] ?: ck["__Secure-3PAPISID"]
        if (sapisid == null) null else {
            val ts = System.currentTimeMillis() / 1000
            val origin = "https://www.youtube.com"
            val body = extra.put("context", JSONObject().put("client", JSONObject()
                .put("clientName", "WEB").put("clientVersion", "2.20250120.01.00").put("hl", "en")))
            val conn = URL("$origin/youtubei/v1/$path?prettyPrint=false").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"; conn.doOutput = true; conn.connectTimeout = 15000; conn.readTimeout = 20000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Cookie", ck.entries.joinToString("; ") { "${it.key}=${it.value}" })
            conn.setRequestProperty("Authorization", "SAPISIDHASH ${ts}_${sha1("$ts $sapisid $origin")}")
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
}
