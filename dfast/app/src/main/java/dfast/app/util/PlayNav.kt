package dfast.app.util

/** Back-stack of what this player screen chain has played. Previous is enabled only when it is not empty. Pure logic, unit tested. */
class PlayHistory<T>(private val key: (T) -> String, private val cap: Int = 50) {
    private val items = ArrayDeque<T>()
    val size get() = items.size
    fun canBack() = items.isNotEmpty()
    /** remember [item] before moving on; the same item twice in a row is stored once */
    fun push(item: T) {
        if (items.isNotEmpty() && key(items.last()) == key(item)) return
        items.addLast(item); while (items.size > cap) items.removeFirst()
    }
    fun pop(): T? = if (items.isEmpty()) null else items.removeLast()
    fun clear() = items.clear()
}

/**
 * Double-tap to skip, YouTube style. Feed it every tap on the video; it says what to do.
 * Left/right thirds skip, the middle only toggles the controls. After a double tap, further single taps
 * on the same side keep skipping while they come within [stackMs] of each other.
 * [rtl] is ignored on purpose: right is always forward and left always back, in every language (as YouTube does).
 */
class TapSkip(private val skipMs: Long = 10_000, private val doubleMs: Long = 300, private val stackMs: Long = 800) {
    enum class Side { LEFT, RIGHT, MID }
    sealed class R {
        /** a first tap: the caller shows/hides the controls after [doubleMs] unless a Skip follows */
        object Pending : R()
        /** [forward] direction, [deltaMs] this step (always positive), [totalMs] stacked so far (always positive) */
        data class Skip(val forward: Boolean, val deltaMs: Long, val totalMs: Long, val side: Side) : R()
    }
    private var lastTapAt = 0L
    private var lastSide: Side? = null
    private var stackSide: Side? = null
    private var stackUntil = 0L
    private var stackTotal = 0L

    fun sideOf(xFrac: Float): Side = if (xFrac < 0.4f) Side.LEFT else if (xFrac > 0.6f) Side.RIGHT else Side.MID
    fun forwardOn(side: Side, @Suppress("UNUSED_PARAMETER") rtl: Boolean): Boolean = side == Side.RIGHT

    fun tap(xFrac: Float, rtl: Boolean, now: Long): R {
        val side = sideOf(xFrac)
        if (side != Side.MID && stackSide == side && now <= stackUntil) {
            stackTotal += skipMs; stackUntil = now + stackMs
            return R.Skip(forwardOn(side, rtl), skipMs, stackTotal, side)
        }
        if (side != Side.MID && lastSide == side && lastTapAt > 0 && now - lastTapAt <= doubleMs) {
            stackSide = side; stackTotal = skipMs; stackUntil = now + stackMs; lastTapAt = 0; lastSide = null
            return R.Skip(forwardOn(side, rtl), skipMs, stackTotal, side)
        }
        stackSide = null; stackTotal = 0
        lastTapAt = now; lastSide = side
        return R.Pending
    }
    fun reset() { lastTapAt = 0; lastSide = null; stackSide = null; stackTotal = 0 }
}

/** the position after a skip, clamped to the video */
fun skipTarget(positionMs: Long, deltaMs: Long, forward: Boolean, durationMs: Long): Long {
    val t = positionMs + if (forward) deltaMs else -deltaMs
    return t.coerceIn(0L, if (durationMs > 0) durationMs else Long.MAX_VALUE)
}

/** "3:05" / "1:02:03" */
fun fmtClock(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** fullscreen follows the video: taller than wide = portrait, otherwise landscape; unknown size = landscape (the usual case) */
fun fullscreenPortrait(videoWidth: Int, videoHeight: Int, rotationDeg: Int = 0): Boolean {
    if (videoWidth <= 0 || videoHeight <= 0) return false
    val rotated = rotationDeg == 90 || rotationDeg == 270
    val w = if (rotated) videoHeight else videoWidth
    val h = if (rotated) videoWidth else videoHeight
    return h > w
}

/** where the next card goes: portrait fullscreen = top (P2), landscape fullscreen = right above the dock (F1), normal = over the video */
enum class CardSlot { OVER_VIDEO, TOP, RIGHT_ABOVE_DOCK }
fun cardSlot(fullscreen: Boolean, portrait: Boolean): CardSlot = if (!fullscreen) CardSlot.OVER_VIDEO else if (portrait) CardSlot.TOP else CardSlot.RIGHT_ABOVE_DOCK
