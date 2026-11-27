package dfast.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

class OnsetDetectorTest {
    private fun lcg(n: Int, noise: Double): DoubleArray {
        var s = 12345L; val o = DoubleArray(n)
        for (i in 0 until n) { s = (s * 1664525L + 1013904223L) and 0xFFFFFFFFL; o[i] = (s / 4294967296.0 - 0.5) * noise * 3.4641016 }
        return o
    }
    /** Kick drums (a 45-135 Hz thump, 250 ms) over an optional sustained 55 Hz bass and a little noise. */
    private fun signal(sr: Int, secs: Int, kicks: List<Double>, amp: Double, bed: Double, noise: Double): FloatArray {
        val n = sr * secs; val x = lcg(n, noise)
        if (bed > 0) for (i in 0 until n) x[i] += bed * sin(2.0 * PI * 55.0 * i / sr)
        for (tb in kicks) {
            val m = (0.25 * sr).toInt(); val i0 = (tb * sr).toInt(); var acc = 0.0
            for (j in 0 until m) { val t = j.toDouble() / sr; acc += 45.0 + 90.0 * exp(-t * 30.0); if (i0 + j < n) x[i0 + j] += sin(2.0 * PI * acc / sr) * exp(-t * 12.0) * amp }
        }
        return FloatArray(n) { x[it].toFloat() }
    }
    private fun detect(x: FloatArray, sr: Int, chunk: Int = x.size): List<OnsetDetector.Onset> {
        val d = OnsetDetector(sr); val out = ArrayList<OnsetDetector.Onset>(); var i = 0
        while (i < x.size) { val n = minOf(chunk, x.size - i); out.addAll(d.feed(x.copyOfRange(i, i + n), n)); i += n }
        return out
    }
    private fun strong(l: List<OnsetDetector.Onset>) = l.filter { it.strength >= OnsetDetector.MIN_STRENGTH }

    private fun kicksTest(sr: Int) {
        val kicks = List(34) { 2.0 + 0.5 * it }
        val on = strong(detect(signal(sr, 20, kicks, 0.8, 0.25, 0.01), sr))
        val hits = kicks.drop(3).count { tb -> on.any { Math.abs(it.timeS - tb) < 0.03 && it.strength >= 0.3f } }
        assertTrue("hits=$hits", hits >= 29)               // 31 kicks after the warm-up
        assertTrue("total=${on.size}", on.size <= 40)      // no storm of extra triggers
    }
    @Test fun kicksOverASustainedBassAreFoundAt48k() = kicksTest(48000)
    @Test fun kicksOverASustainedBassAreFoundAt44k() = kicksTest(44100)

    @Test fun onsetTimeIsWithinThirtyMillisecondsOfTheKick() {
        val kicks = List(20) { 2.0 + 0.5 * it }
        val on = strong(detect(signal(48000, 14, kicks, 0.8, 0.25, 0.01), 48000))
        val err = kicks.drop(3).mapNotNull { tb -> on.map { it.timeS - tb }.filter { Math.abs(it) < 0.1 }.minByOrNull { Math.abs(it) } }
        assertTrue(err.size >= 15); assertTrue("worst=${err.maxOf { Math.abs(it) }}", err.all { Math.abs(it) < 0.03 })
    }

    @Test fun softPulsesInAQuietSongAreFound() {
        val kicks = List(10) { 2.0 + 1.7 * it }
        val on = strong(detect(signal(48000, 20, kicks, 0.15, 0.0, 0.003), 48000))
        val hits = kicks.drop(1).count { tb -> on.any { Math.abs(it.timeS - tb) < 0.03 } }
        assertTrue("hits=$hits", hits >= 8)
    }

    @Test fun steadyToneWithNoiseAndSilenceGiveNoOnsets() {
        assertTrue(strong(detect(signal(48000, 20, emptyList(), 0.0, 0.3, 0.01), 48000)).size <= 1)
        assertEquals(0, detect(FloatArray(48000 * 10), 48000).size)
    }

    @Test fun chunkSizeDoesNotChangeTheResult() {
        val x = signal(48000, 12, List(16) { 2.0 + 0.5 * it }, 0.8, 0.25, 0.01)
        val a = detect(x, 48000); val b = detect(x, 48000, 1000); val c = detect(x, 48000, 4097)
        assertTrue(a.isNotEmpty()); assertEquals(a.size, b.size); assertEquals(a.size, c.size)
        for (i in a.indices) { assertEquals(a[i].timeS, b[i].timeS, 1e-9); assertEquals(a[i].strength, c[i].strength, 1e-6f) }
    }

    @Test fun anOnsetNeverComesWithinFrameWarmUp() {
        val on = detect(signal(48000, 6, List(10) { 0.1 + 0.5 * it }, 0.8, 0.0, 0.01), 48000)
        assertTrue(on.all { it.timeS > 1.0 })
    }

    @Test fun oddSampleRatesDoNotCrash() {
        for (sr in intArrayOf(8000, 22050, 32000, 96000)) detect(signal(sr, 4, listOf(1.5, 2.0, 2.5), 0.8, 0.1, 0.01), sr)
    }
}
