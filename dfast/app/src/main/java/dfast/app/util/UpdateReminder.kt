package dfast.app.util

/**
 * When the update dialog may open by itself (pure, unit tested).
 * First time a new version is seen: at once. After "Later": one reminder after 24 h, one more after 3 days, then never
 * (the bell and the notification stay). "Skip this version" is checked by the caller (the version is never offered).
 * A new version starts the cycle again.
 */
object UpdateReminder {
    const val HOUR = 3_600_000L
    data class State(val tag: String = "", val shown: Int = 0, val lastShownAt: Long = 0L)

    fun shouldShow(tag: String, s: State, now: Long): Boolean {
        if (s.tag != tag) return true
        return when (s.shown) {
            0 -> true
            1 -> now - s.lastShownAt >= 24 * HOUR
            2 -> now - s.lastShownAt >= 72 * HOUR
            else -> false
        }
    }

    fun afterShown(tag: String, s: State, now: Long) = State(tag, if (s.tag == tag) s.shown + 1 else 1, now)
}
