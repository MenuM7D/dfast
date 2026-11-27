package dfast.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverGlassTest {
    private fun px(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test fun dullOrBlackCoverFallsBack() {
        val p = CoverGlass.palette(IntArray(100) { px(10, 10, 10) } + IntArray(100) { px(128, 128, 128) }, 0xFF7C5CFF.toInt())
        assertEquals(4, p.size); assertTrue(p.all { it == 0xFF7C5CFF.toInt() })
    }

    @Test fun mostCommonVividHueComesFirstAndIsBright() {
        val pixels = IntArray(300) { px(220, 40, 40) } + IntArray(100) { px(40, 60, 230) }
        val p = CoverGlass.palette(pixels, 0)
        val r = p[0] shr 16 and 255; val g = p[0] shr 8 and 255; val b = p[0] and 255
        assertTrue(r > 200 && g < 100 && b < 100)           // red first
        assertTrue((p[1] and 255) > 200)                       // then blue
        assertEquals(4, p.size)
    }

    private fun hueOf(c: Int): Float {
        val r = (c shr 16 and 255) / 255f; val g = (c shr 8 and 255) / 255f; val b = (c and 255) / 255f
        val mx = maxOf(r, g, b); val d = mx - minOf(r, g, b); if (d == 0f) return 0f
        return 60f * when (mx) { r -> (((g - b) / d) % 6f + 6f) % 6f; g -> (b - r) / d + 2f; else -> (r - g) / d + 4f }
    }
    private fun gap(a: Int, b: Int): Float { val d = Math.abs(hueOf(a) - hueOf(b)); return minOf(d, 360f - d) }
    private val red = px(240, 50, 50); private val blue = px(50, 80, 240); private val orange = px(250, 140, 50)

    @Test fun tameKeepsMainAndPullsContrastingColoursTowardIt() {
        val t = CoverGlass.tame(intArrayOf(red, blue, orange, blue))
        assertEquals(red, t[0])
        assertTrue(gap(t[1], red) < gap(blue, red) - 20f)       // blue pulled toward red
        assertEquals(orange, t[2])                              // a close hue is left alone
    }

    @Test fun tameLeavesASingleColourFamilyUntouched() {
        val p = intArrayOf(red, px(240, 90, 50), px(250, 40, 90), px(255, 120, 60))
        assertTrue(CoverGlass.tame(p).contentEquals(p))
    }

    @Test fun meshIsOpaqueSmoothAndDimmed() {
        val n = 48; val m = CoverGlass.mesh(intArrayOf(red, blue, orange, red), n, 0, 0.5f)
        assertEquals(n * n, m.size)
        assertTrue(m.all { (it ushr 24) == 0xFF })
        var worst = 0
        for (y in 0 until n) for (x in 0 until n - 1) {
            val a = m[y * n + x]; val b = m[y * n + x + 1]
            for (sh in intArrayOf(16, 8, 0)) worst = maxOf(worst, Math.abs((a shr sh and 255) - (b shr sh and 255)))
        }
        assertTrue("worst step $worst", worst < 14)             // no blocks: neighbouring pixels differ by little
        assertTrue(m.all { (it shr 16 and 255) <= 200 })        // dimmed: never the full bright red
    }

    @Test fun differentShiftsGiveDifferentMeshes() {
        val a = CoverGlass.mesh(intArrayOf(red, blue, orange, red), 16, 0, 0.5f)
        val b = CoverGlass.mesh(intArrayOf(red, blue, orange, red), 16, 1, 0.5f)
        assertTrue(!a.contentEquals(b))
    }

    @Test fun dimZeroIsTheDarkBaseAndOutOfRangeIsClamped() {
        val z = CoverGlass.mesh(intArrayOf(red, blue, orange, red), 8, 0, 0f)
        assertTrue(z.all { it == px(11, 11, 16) })
        assertTrue(CoverGlass.mesh(intArrayOf(red), 8, 5, 3f).all { (it ushr 24) == 0xFF })
    }
}
