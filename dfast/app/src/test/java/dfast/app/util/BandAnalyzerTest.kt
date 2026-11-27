package dfast.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class BandAnalyzerTest {
    private val rate = 44100
    private fun tone(freq: Double, seconds: Double, amp: Float = 0.5f) = FloatArray((rate * seconds).toInt()) { (amp * sin(2.0 * PI * freq * it / rate)).toFloat() }

    private fun lastFrame(x: FloatArray): BandAnalyzer.Frame {
        val a = BandAnalyzer(rate); var last: BandAnalyzer.Frame? = null
        var i = 0
        while (i < x.size) { val n = minOf(1000, x.size - i); val c = x.copyOfRange(i, i + n); a.feed(c, n).forEach { last = it }; i += n }
        return last!!
    }

    @Test fun framesComeEveryHopAndAre24Bands() {
        val a = BandAnalyzer(rate); val x = tone(440.0, 1.0)
        val frames = a.feed(x, x.size)
        assertEquals((x.size - BandAnalyzer.N) / BandAnalyzer.HOP + 1, frames.size)
        assertEquals(24, frames[0].levels.size)
        assertTrue(frames.all { f -> f.levels.all { it in 0f..1f } })
    }

    @Test fun loudestBandMovesUpWithTheTone() {
        // a steady tone is its own running maximum, so alternate tone and silence to make the level rise above the 0.5 floor
        fun peak(freq: Double): Int {
            val x = tone(freq, 1.0) + FloatArray(rate / 2) + tone(freq, 0.5)
            val f = lastFrame(x); return f.levels.indices.maxByOrNull { f.levels[it] }!!
        }
        val lowB = peak(120.0); val midB = peak(1000.0); val highB = peak(6000.0)
        assertTrue("low $lowB < mid $midB", lowB < midB)
        assertTrue("mid $midB < high $highB", midB < highB)
    }

    @Test fun silenceGivesFlatBars() {
        val f = lastFrame(FloatArray(rate))
        assertTrue(f.levels.all { it < 0.01f })
    }
}
