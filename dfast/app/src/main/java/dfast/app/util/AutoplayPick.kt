package dfast.app.util

/** One possible next item. durationSec: -1 = unknown (local items), 0 = YouTube gave no length (live / premiere / broken). */
data class AutoCand(val url: String, val title: String, val author: String, val thumb: String, val durationSec: Long, val live: Boolean)

/** Autoplay: which item comes after the one that just ended. Pure logic, no Android, so it is unit tested. */
object AutoplayPick {
    const val RECENT_WINDOW = 20
    /** chained autoplays without any touch stop after this long (YouTube stops on inactivity too) */
    const val INACTIVITY_LIMIT_MS = 4L * 60 * 60 * 1000
    /** on mobile data YouTube stops after 30 minutes without a touch */
    const val INACTIVITY_MOBILE_MS = 30L * 60 * 1000
    fun limitFor(onMobileData: Boolean) = if (onMobileData) INACTIVITY_MOBILE_MS else INACTIVITY_LIMIT_MS

    /** Extra pick context. music: artist diversity instead of the same-channel bonus. shuffle: random among the best few. */
    data class Opts(
        val music: Boolean = false,
        val recentAuthors: List<String> = emptyList(),
        val recentTitles: List<String> = emptyList(),
        val shuffle: Boolean = false,
        val rnd: kotlin.random.Random = kotlin.random.Random.Default
    )
    const val LONG_FORM_SEC = 2700L
    private val LONG_FORM_TITLE = Regex("(?i)\\b(full album|non[- ]?stop|mix|compilation|1 hour|one hour|[2-9] hours?|10 hours)\\b|ساعة كاملة|ساعات|البوم كامل|ألبوم كامل")
    /** a long compilation / mix / full album: not a good "next" unless the video before it was one too */
    fun longForm(c: AutoCand, ctx: Ctx?): Boolean {
        if (ctx != null && ctx.durationSec > LONG_FORM_SEC) return false
        return c.durationSec > LONG_FORM_SEC || LONG_FORM_TITLE.containsMatchIn(c.title)
    }
    /** the same list without near-duplicate titles (first one wins), order kept */
    fun <T> dedupe(items: List<T>, title: (T) -> String): List<T> {
        val out = ArrayList<T>()
        for (i in items) if (out.none { nearDuplicate(title(it), title(i)) }) out.add(i)
        return out
    }
    /** shown lists: long-form items stay but go below the rest, order otherwise kept */
    fun <T> rankLongFormLast(items: List<T>, isLong: (T) -> Boolean): List<T> = items.filterNot(isLong) + items.filter(isLong)

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
    fun pick(related: List<AutoCand>, local: List<AutoCand>, current: String, recent: List<String>, ctx: Ctx? = null, opts: Opts = Opts()): AutoCand? {
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
        var pool = usable.filter(::comfy).ifEmpty { usable }
        pool = pool.filterNot { longForm(it, ctx) }.ifEmpty { pool }
        pool = pool.filter { c -> opts.recentTitles.none { nearDuplicate(c.title, it) } }.ifEmpty { pool }
        if (pool.isNotEmpty()) {
            val author = ctx?.author.orEmpty()
            if (opts.music) {
                val avoid = (opts.recentAuthors.takeLast(3) + author).filter { it.isNotBlank() }.toSet()
                return pool.firstOrNull { it.author !in avoid } ?: pool.first()
            }
            if (opts.shuffle) return pool.take(10).let { it[opts.rnd.nextInt(it.size)] }
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
    fun allowed(switchOn: Boolean, repeatOne: Boolean, nowMs: Long, lastTouchMs: Long, limitMs: Long = INACTIVITY_LIMIT_MS): Boolean =
        switchOn && !repeatOne && nowMs - lastTouchMs < limitMs

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
    private val authors = ArrayDeque<String>()
    private val titles = ArrayDeque<String>()
    @Synchronized fun played(url: String) { played.remove(url); played.addLast(url); while (played.size > 40) played.removeFirst() }
    /** what the screen has played lately, for artist diversity and title de-duplication */
    @Synchronized fun playedMeta(author: String, title: String) {
        authors.addLast(author); while (authors.size > 10) authors.removeFirst()
        titles.addLast(title); while (titles.size > 6) titles.removeFirst()
    }
    @Synchronized fun recentAuthors(): List<String> = authors.toList()
    @Synchronized fun recentTitles(): List<String> = titles.toList()
    @Synchronized fun recent(): List<String> = played.toList()
    fun touch() { lastTouchMs = System.currentTimeMillis() }
}
