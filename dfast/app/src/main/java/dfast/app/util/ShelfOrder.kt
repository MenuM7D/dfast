package dfast.app.util

import android.content.Context

/** Home shelves keep their normal order, but the ones the user actually opens move up (a little, and fading over weeks). Pure maths + a tiny store. */
object ShelfOrder {
    const val HALF_LIFE_DAYS = 14.0
    const val MAX_BONUS = 25

    /** hits that count now: halves every [HALF_LIFE_DAYS] */
    fun decayed(count: Double, lastMs: Long, nowMs: Long): Double {
        if (count <= 0 || nowMs <= lastMs) return count.coerceAtLeast(0.0)
        val days = (nowMs - lastMs) / 86_400_000.0
        return count * Math.pow(0.5, days / HALF_LIFE_DAYS)
    }
    /** sort key: base position x10, minus up to 2.5 positions for use. Lower shows first. */
    fun rank(base: Int, hits: Double): Int = base * 10 - minOf(MAX_BONUS.toDouble(), hits * 5).toInt()

    private const val PREFS = "shelf_hits"
    fun hit(c: Context, key: String, nowMs: Long = System.currentTimeMillis()) {
        if (key.isBlank()) return
        val sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val (cnt, last) = parse(sp.getString(key, null))
        sp.edit().putString(key, "${decayed(cnt, last, nowMs) + 1}|$nowMs").apply()
    }
    fun hits(c: Context, key: String, nowMs: Long = System.currentTimeMillis()): Double {
        val (cnt, last) = parse(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null))
        return decayed(cnt, last, nowMs)
    }
    internal fun parse(s: String?): Pair<Double, Long> {
        val p = s?.split("|") ?: return 0.0 to 0L
        return (p.getOrNull(0)?.toDoubleOrNull() ?: 0.0) to (p.getOrNull(1)?.toLongOrNull() ?: 0L)
    }
}
