package dfast.app.util

import android.content.Context
import dfast.app.database.models.ResultItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Last good copy of each account feed on disk (see docs/FEED_SAFEGUARDS.md).
 * Used when a live load returns nothing, so a YouTube-side change never leaves an empty screen.
 */
object FeedCache {
    private fun f(c: Context, key: String) = File(File(c.filesDir, "feedcache").apply { mkdirs() }, key.replace(Regex("[^A-Za-z0-9_]"), "_") + ".json")

    fun save(c: Context, key: String, items: List<ResultItem>) {
        if (items.isEmpty()) return
        runCatching {
            val a = JSONArray()
            items.take(40).forEach { a.put(JSONObject().put("u", it.url).put("t", it.title).put("a", it.author).put("d", it.duration).put("h", it.thumb)) }
            f(c, key).writeText(a.toString())
        }
    }

    fun load(c: Context, key: String): List<ResultItem> = runCatching {
        val a = JSONArray(f(c, key).readText())
        (0 until a.length()).map { val o = a.getJSONObject(it)
            ResultItem(0, o.optString("u"), o.optString("t"), o.optString("a"), o.optString("d"), o.optString("h"), "youtube", "", emptyList(), "", null) }
    }.getOrDefault(emptyList())

    fun clearAll(c: Context) { runCatching { File(c.filesDir, "feedcache").deleteRecursively() } }
}
