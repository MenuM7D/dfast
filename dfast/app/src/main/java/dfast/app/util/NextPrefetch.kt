package dfast.app.util


/**
 * The songs that Next will play, chosen and their streams resolved while the current song is still playing, so a tap only has to switch.
 * A rolling chain: entry per song [forUrl] -> the pick that follows it, kept for the next few songs ahead, so tapping Next repeatedly
 * stays fast. An entry is used only with the same shuffle state and only while fresh (the resolved stream stays in StreamResolver's
 * cache for 25 minutes, entries are dropped before that).
 */
object NextPrefetch {
    const val MAX_AGE_MS = 20 * 60_000L
    const val MAX_ENTRIES = 8
    private class Entry(val target: MediaRef, val shuffle: Boolean, val at: Long)
    private val map = LinkedHashMap<String, Entry>()

    @Synchronized fun store(forUrl: String, target: MediaRef, shuffle: Boolean) {
        map.remove(forUrl)
        map[forUrl] = Entry(target, shuffle, System.currentTimeMillis())
        while (map.size > MAX_ENTRIES) map.remove(map.keys.first())
    }
    @Synchronized fun take(forUrl: String, shuffle: Boolean): MediaRef? =
        map[forUrl]?.takeIf { it.shuffle == shuffle && System.currentTimeMillis() - it.at < MAX_AGE_MS }?.target
    fun has(forUrl: String, shuffle: Boolean) = take(forUrl, shuffle) != null
    @Synchronized fun clear() { map.clear() }
}
