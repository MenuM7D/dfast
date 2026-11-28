package dfast.app.util


/**
 * The song that Next will play, chosen and its stream resolved while the current song is still playing, so the tap only has to switch.
 * One entry: the pick for the song [forUrl]. It is used only for that song, with the same shuffle state, and only while fresh
 * (the resolved stream stays in StreamResolver's cache for 25 minutes, the entry is dropped before that).
 */
object NextPrefetch {
    const val MAX_AGE_MS = 20 * 60_000L
    private class Entry(val forUrl: String, val target: MediaRef, val shuffle: Boolean, val at: Long)
    @Volatile private var entry: Entry? = null

    fun store(forUrl: String, target: MediaRef, shuffle: Boolean) { entry = Entry(forUrl, target, shuffle, System.currentTimeMillis()) }
    fun take(forUrl: String, shuffle: Boolean): MediaRef? =
        entry?.takeIf { it.forUrl == forUrl && it.shuffle == shuffle && System.currentTimeMillis() - it.at < MAX_AGE_MS }?.target
    fun has(forUrl: String, shuffle: Boolean) = take(forUrl, shuffle) != null
    fun clear() { entry = null }
}
