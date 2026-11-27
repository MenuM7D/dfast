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
}
