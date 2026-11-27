package dfast.app.util

import kotlin.math.max
import kotlin.math.min

/** Colours for the audio screen's glass background, taken from the cover. Pure maths (pixels in, ARGB colours out). */
object CoverGlass {
    /** Up to 4 vivid colours (most common saturated hues of the cover), each lifted to a bright, saturated tone. Falls back to [fallback]. */
    fun palette(pixels: IntArray, fallback: Int): IntArray {
        val sums = Array(12) { FloatArray(4) } // r,g,b,count per hue bin
        for (p in pixels) {
            val r = (p shr 16 and 255) / 255f; val g = (p shr 8 and 255) / 255f; val b = (p and 255) / 255f
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
            if (mx < 0.45f || mx <= 0f || d / mx < 0.35f) continue
            val h = when {
                d == 0f -> 0f
                mx == r -> (((g - b) / d) % 6f + 6f) % 6f
                mx == g -> (b - r) / d + 2f
                else -> (r - g) / d + 4f
            } / 6f
            val bin = (h * 12).toInt().coerceIn(0, 11)
            sums[bin][0] += r; sums[bin][1] += g; sums[bin][2] += b; sums[bin][3] += 1f
        }
        val top = sums.filter { it[3] > 0f }.sortedByDescending { it[3] }.take(4)
        if (top.isEmpty()) return intArrayOf(fallback, fallback, fallback, fallback)
        val out = top.map { s -> lift(s[0] / s[3], s[1] / s[3], s[2] / s[3]) }.toMutableList()
        while (out.size < 4) out.add(out[out.size % top.size])
        return out.toIntArray()
    }

    /** Same hue, saturation 0.8, value 0.95. */
    private fun lift(r: Float, g: Float, b: Float): Int {
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
        val h = if (d == 0f) 0f else when (mx) { r -> (((g - b) / d) % 6f + 6f) % 6f; g -> (b - r) / d + 2f; else -> (r - g) / d + 4f }
        val s = 0.8f; val v = 0.95f
        val c = v * s; val x = c * (1f - kotlin.math.abs(h % 2f - 1f)); val m = v - c
        val (rr, gg, bb) = when (h.toInt().coerceIn(0, 5)) {
            0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x); 3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        fun ch(f: Float) = ((f + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(rr) shl 16) or (ch(gg) shl 8) or ch(bb)
    }

    // ---- frosted mesh glass: a 3x3 mesh of the cover colours, smoothly blended, no cover pixels ----
    private fun hue(c: Int): Float {
        val r = (c shr 16 and 255) / 255f; val g = (c shr 8 and 255) / 255f; val b = (c and 255) / 255f
        val mx = max(r, max(g, b)); val d = mx - min(r, min(g, b))
        if (d == 0f) return 0f
        val h = when (mx) { r -> (((g - b) / d) % 6f + 6f) % 6f; g -> (b - r) / d + 2f; else -> (r - g) / d + 4f }
        return h * 60f
    }
    private fun hueGap(a: Int, b: Int): Float { val d = kotlin.math.abs(hue(a) - hue(b)); return min(d, 360f - d) }
    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(sh: Int) = (((a shr sh and 255) * (1f - t)) + ((b shr sh and 255) * t) + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Keeps the mesh calm when the cover has contrasting colours: every colour more than 50 degrees of hue away from the main one is pulled toward it (up to 65 % at 130 degrees and more). */
    fun tame(pal: IntArray): IntArray {
        if (pal.isEmpty()) return pal
        val main = pal[0]
        return IntArray(pal.size) { i -> if (i == 0) main else mix(pal[i], main, (((hueGap(pal[i], main) - 50f) / 80f).coerceIn(0f, 1f)) * 0.65f) }
    }

    /** n x n opaque ARGB pixels of the mesh: [shift] rotates which colour sits where (a second mesh with another shift is cross-faded to make the colours drift). [dim] 0..1 darkens toward the screen's dark base. */
    fun mesh(pal: IntArray, n: Int, shift: Int, dim: Float): IntArray {
        val p = tame(pal); val m = max(1, p.size - 1).coerceAtMost(3)
        fun col(i: Int, j: Int) = p[(i + j + shift).mod(m.coerceAtLeast(1)).coerceAtMost(p.size - 1)]
        fun sm(t: Float) = t * t * (3f - 2f * t)
        val base = (11 shl 16) or (11 shl 8) or 16
        return IntArray(n * n) { idx ->
            val x = (idx % n) / (n - 1f) * 2f; val y = (idx / n) / (n - 1f) * 2f
            val i0 = min(x.toInt(), 1); val j0 = min(y.toInt(), 1); val fx = sm(x - i0); val fy = sm(y - j0)
            val top = mix(col(i0, j0), col(i0 + 1, j0), fx); val bot = mix(col(i0, j0 + 1), col(i0 + 1, j0 + 1), fx)
            mix(base or (0xFF shl 24), mix(top, bot, fy), dim.coerceIn(0f, 1f))
        }
    }
}
