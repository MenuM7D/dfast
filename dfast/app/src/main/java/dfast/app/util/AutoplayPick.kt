package dfast.app.util

/** One possible next item. durationSec: -1 = unknown (local items), 0 = YouTube gave no length (live / premiere / broken). */
data class AutoCand(val url: String, val title: String, val author: String, val thumb: String, val durationSec: Long, val live: Boolean)

/** Autoplay: which item comes after the one that just ended. Pure logic, no Android, so it is unit tested. */
object AutoplayPick {
    const val RECENT_WINDOW = 20
    /** chained autoplays without any touch stop after this long (YouTube stops on inactivity too) */
    const val INACTIVITY_LIMIT_MS = 4L * 60 * 60 * 1000

    /** the first usable item of [related]; only when none is usable the local pool is tried. Never the same item, never one of the last [RECENT_WINDOW] played, never live or zero length. */
    fun next(related: List<AutoCand>, local: List<AutoCand>, current: String, recent: List<String>): AutoCand? {
        val avoid = recent.takeLast(RECENT_WINDOW).toSet() + current
        fun ok(c: AutoCand) = c.url.isNotBlank() && c.url !in avoid && !c.live && c.durationSec != 0L
        return related.firstOrNull(::ok) ?: local.firstOrNull(::ok)
    }

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
