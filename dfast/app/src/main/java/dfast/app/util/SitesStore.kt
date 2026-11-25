package dfast.app.util

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject

/** Sites list for the Sites screen: built-in sites (can be deleted), custom sites, and visit history. */
object SitesStore {
    data class Site(val name: String, val url: String, val color: Int, val glyph: String, val custom: Boolean)

    private val defaults = listOf(
        Site("YouTube", "https://m.youtube.com", 0xFFE62117.toInt(), "youtube", false),
        Site("Instagram", "https://www.instagram.com", 0xFFC13584.toInt(), "instagram", false),
        Site("TikTok", "https://www.tiktok.com", 0xFF222222.toInt(), "tiktok", false),
        Site("Facebook", "https://m.facebook.com", 0xFF1877F2.toInt(), "facebook", false),
        Site("X / Twitter", "https://x.com", 0xFF333333.toInt(), "x", false),
        Site("SoundCloud", "https://m.soundcloud.com", 0xFFFF5500.toInt(), "soundcloud", false),
        Site("Reddit", "https://www.reddit.com", 0xFFFF4500.toInt(), "reddit", false),
        Site("Dailymotion", "https://www.dailymotion.com", 0xFF0066DC.toInt(), "dailymotion", false)
    )
    /** Brand icon (Simple Icons, CC0) for built-in sites, a generic globe for custom ones. */
    fun iconRes(c: Context, s: Site): Int {
        val id = if (s.glyph.isEmpty()) 0 else c.resources.getIdentifier("ic_brand_" + s.glyph, "drawable", c.packageName)
        return if (id != 0) id else dfast.app.R.drawable.ic_o_globe
    }
    private val palette = intArrayOf(0xFF7C5CFF.toInt(), 0xFF43A047.toInt(), 0xFFEF6C00.toInt(), 0xFF00897B.toInt(), 0xFFD81B60.toInt(), 0xFF5E35B1.toInt())

    private fun p(c: Context) = PreferenceManager.getDefaultSharedPreferences(c)

    private fun customArr(c: Context): JSONArray = runCatching { JSONArray(p(c).getString("sites_custom", "[]")) }.getOrDefault(JSONArray())

    private fun custom(c: Context): List<Site> {
        val arr = customArr(c)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val n = o.optString("name")
            Site(n, o.optString("url"), palette[Math.abs(n.hashCode()) % palette.size], "", true)
        }
    }

    fun all(c: Context): List<Site> {
        val gone = p(c).getStringSet("sites_deleted", emptySet()) ?: emptySet()
        return defaults.filter { !gone.contains(it.name) } + custom(c)
    }

    fun add(c: Context, name: String, url: String) {
        val arr = customArr(c)
        arr.put(JSONObject().put("name", name).put("url", url))
        p(c).edit { putString("sites_custom", arr.toString()) }
    }

    fun remove(c: Context, s: Site) {
        if (s.custom) {
            val arr = customArr(c)
            val out = JSONArray()
            var removed = false
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (!removed && o.optString("name") == s.name && o.optString("url") == s.url) removed = true else out.put(o)
            }
            p(c).edit { putString("sites_custom", out.toString()) }
        } else {
            val gone = HashSet(p(c).getStringSet("sites_deleted", emptySet()) ?: emptySet())
            gone.add(s.name)
            p(c).edit { putStringSet("sites_deleted", gone) }
        }
    }

    fun history(c: Context): List<Pair<String, String>> {
        val arr = runCatching { JSONArray(p(c).getString("sites_history", "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).map { val o = arr.getJSONObject(it); o.optString("name") to o.optString("url") }
    }

    fun addHistory(c: Context, name: String, url: String) {
        val list = history(c).filter { it.second != url }.toMutableList()
        list.add(0, name to url)
        val arr = JSONArray()
        list.take(30).forEach { arr.put(JSONObject().put("name", it.first).put("url", it.second)) }
        p(c).edit { putString("sites_history", arr.toString()) }
    }

    fun clearHistory(c: Context) { p(c).edit { putString("sites_history", "[]") } }
}
