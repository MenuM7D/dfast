package dfast.app.util

/** Battery saver for the ring: when on, the ring and background stop moving at low battery (not charging). */
object RingPower {
    const val LOW_PERCENT = 20
    fun saverActive(enabled: Boolean, percent: Int, charging: Boolean): Boolean =
        enabled && percent in 0..LOW_PERCENT && !charging
}
