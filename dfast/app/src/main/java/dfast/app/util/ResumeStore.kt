package dfast.app.util

import android.content.Context

/** Resume position per video + the "Continue watching" list. Pure rules on top, a tiny SharedPreferences store below. */
object ResumeStore {
    data class Entry(val url: String, val title: String, val author: String, val thumb: String, val duration: String, val posMs: Long, val durMs: Long, val ts: Long)

    const val MIN_DUR_MS = 120_000L
    const val MIN_POS_MS = 15_000L
    const val END_MARGIN_MS = 20_000L
    const val MAX_ENTRIES = 20
    const val REWIND_MS = 3_000L

    /** worth remembering: a real video (2 min+), watched past the first 15 s and not (almost) finished */
    fun shouldKeep(posMs: Long, durMs: Long): Boolean = durMs >= MIN_DUR_MS && posMs >= MIN_POS_MS && posMs <= durMs - END_MARGIN_MS
    /** finished (or nearly): forget it */
    fun finished(posMs: Long, durMs: Long): Boolean = durMs > 0 && posMs > durMs - END_MARGIN_MS
    fun resumeAt(posMs: Long): Long = (posMs - REWIND_MS).coerceAtLeast(0L)
    /** newest first, one entry per url, capped */
    fun merge(list: List<Entry>, e: Entry, max: Int = MAX_ENTRIES): List<Entry> = (listOf(e) + list.filter { it.url != e.url }).take(max)

    private const val PREFS = "resume_store"
    fun list(c: Context): List<Entry> = runCatching {
        val a = org.json.JSONArray(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("list", "[]"))
        (0 until a.length()).map { val o = a.getJSONObject(it)
            Entry(o.getString("u"), o.optString("t"), o.optString("a"), o.optString("th"), o.optString("d"), o.optLong("p"), o.optLong("l"), o.optLong("ts")) }
    }.getOrDefault(emptyList())
    private fun write(c: Context, l: List<Entry>) {
        val a = org.json.JSONArray()
        l.forEach { a.put(org.json.JSONObject().put("u", it.url).put("t", it.title).put("a", it.author).put("th", it.thumb).put("d", it.duration).put("p", it.posMs).put("l", it.durMs).put("ts", it.ts)) }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("list", a.toString()).apply()
    }
    fun save(c: Context, e: Entry) = runCatching { write(c, merge(list(c), e)) }
    fun remove(c: Context, url: String) = runCatching { val l = list(c); if (l.any { it.url == url }) write(c, l.filter { it.url != url }) }
    fun get(c: Context, url: String): Entry? = list(c).firstOrNull { it.url == url }
}

/** the 11-character YouTube id inside any of its link shapes, "" when there is none */
object UrlIds {
    private val ID = Regex("(?:[?&]v=|youtu\\.be/|/shorts/|/embed/|/live/)([A-Za-z0-9_-]{11})")
    fun of(url: String): String = ID.find(url)?.groupValues?.get(1).orEmpty()
}
