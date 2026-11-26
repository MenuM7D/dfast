package dfast.app.util

/** One possible next item. durationSec: -1 = unknown (local items), 0 = YouTube gave no length (live / premiere / broken). */
data class AutoCand(val url: String, val title: String, val author: String, val thumb: String, val durationSec: Long, val live: Boolean)

/** Autoplay: which item comes after the one that just ended. Pure logic, no Android, so it is unit tested. */
object AutoplayPick {
    const val RECENT_WINDOW = 20
    /** chained autoplays without any touch stop after this long (YouTube stops on inactivity too) */
    const val INACTIVITY_LIMIT_MS = 4L * 60 * 60 * 1000

    /** What the finished video looks like; lets the pick keep language and length continuity. */
    data class Ctx(val title: String, val author: String, val durationSec: Long)

    /** Shorts are not offered as "next" (YouTube's own lists are 3+ minute videos): 1..60 s */
    const val SHORT_MAX_SEC = 60L
    const val RATIO_MIN = 0.4
    const val RATIO_MAX = 2.5
    /** how far down the list a same-channel item may jump ahead of the first eligible one */
    const val SAME_CHANNEL_WINDOW = 5

    fun isArabic(s: String) = s.any { it in '\u0600'..'\u06FF' }
    private fun words(s: String) = Regex("[\\p{L}\\p{N}]+").findAll(s.lowercase()).map { it.value }.toSet()
    /** a re-upload / same song: the same title once punctuation and brackets are ignored, or nearly the same words */
    fun nearDuplicate(a: String, b: String): Boolean {
        val wa = words(a); val wb = words(b)
        if (wa.isEmpty() || wb.isEmpty()) return false
        if (wa == wb) return true
        return wa.intersect(wb).size.toDouble() / wa.union(wb).size >= 0.8
    }

    /**
     * Picks the next item from [related] (YouTube's order for the video that just ended), then from [local] only if nothing in related is usable.
     * Hard rules: not the same video, not one of the last [RECENT_WINDOW] played, not live, not zero length, not a Short, not a near-duplicate title.
     * Comfort rules (language of the title, length within 0.4x-2.5x): used when something passes them, skipped when nothing does.
     * Among the eligible, a same-channel item inside the first [SAME_CHANNEL_WINDOW] wins, otherwise the first one.
     */
    fun pick(related: List<AutoCand>, local: List<AutoCand>, current: String, recent: List<String>, ctx: Ctx? = null): AutoCand? {
        val avoid = recent.takeLast(RECENT_WINDOW).toSet() + current
        fun ok(c: AutoCand) = c.url.isNotBlank() && c.url !in avoid && !c.live && c.durationSec != 0L &&
            !(c.durationSec in 1..SHORT_MAX_SEC) && !(ctx != null && ctx.title.isNotBlank() && nearDuplicate(c.title, ctx.title))
        fun comfy(c: AutoCand): Boolean {
            if (ctx == null) return true
            if (ctx.title.isNotBlank() && c.title.isNotBlank() && isArabic(c.title) != isArabic(ctx.title)) return false
            if (ctx.durationSec > 0 && c.durationSec > 0) { val r = c.durationSec.toDouble() / ctx.durationSec; if (r < RATIO_MIN || r > RATIO_MAX) return false }
            return true
        }
        val usable = related.filter(::ok)
        val pool = usable.filter(::comfy).ifEmpty { usable }
        if (pool.isNotEmpty()) {
            val author = ctx?.author.orEmpty()
            val same = if (author.isBlank()) null else pool.take(SAME_CHANNEL_WINDOW).firstOrNull { it.author == author }
            return same ?: pool.first()
        }
        return local.firstOrNull { it.url.isNotBlank() && it.url !in avoid && !it.live && it.durationSec != 0L }
    }

    /** the first usable item (no context): same as [pick] without the comfort rules */
    fun next(related: List<AutoCand>, local: List<AutoCand>, current: String, recent: List<String>): AutoCand? = pick(related, local, current, recent, null)

    /** the next item opens in the mode the screen is in; an item the user taps himself always opens as a normal video */
    fun audioFor(currentIsAudio: Boolean, userTapped: Boolean): Boolean = if (userTapped) false else currentIsAudio

    /** autoplay needs the switch on, no repeat-one, and (for chained plays) a touch within the inactivity limit */
    fun allowed(switchOn: Boolean, repeatOne: Boolean, nowMs: Long, lastTouchMs: Long): Boolean =
        switchOn && !repeatOne && nowMs - lastTouchMs < INACTIVITY_LIMIT_MS

    /** "3:44" / "1:02:03" -> seconds, -1 when it is not a length */
    fun parseDuration(s: String): Long {
        val parts = s.trim().split(":")
        if (s.isBlank() || parts.any { it.toLongOrNull() == null }) return -1
        return parts.fold(0L) { acc, p -> acc * 60 + p.toLong() }
    }
}

/** Process-wide autoplay memory: the last videos played in this session, and the last touch. */
object AutoplayState {
    private val played = ArrayDeque<String>()
    @Volatile var lastTouchMs = System.currentTimeMillis()
    @Synchronized fun played(url: String) { played.remove(url); played.addLast(url); while (played.size > 40) played.removeFirst() }
    @Synchronized fun recent(): List<String> = played.toList()
    fun touch() { lastTouchMs = System.currentTimeMillis() }
}
