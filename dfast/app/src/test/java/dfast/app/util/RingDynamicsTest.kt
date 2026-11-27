package dfast.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RingDynamicsTest {
    private class Run(val maxLobe: Float, val minLobe: Float, val maxPunch: Float, val beats: Int, val lobe: Float, val minRipple: Float)

    private fun run(secs: Int, live: Boolean = true, f: (Float) -> FloatArray): Run {
        val r = RingDynamics(); val n = secs * 1000 / 16
        var maxLobe = 0f; var minLobe = 1f; var maxPunch = 0f; var beats = 0; var minRip = 1f
        for (i in 0 until n) {
            val t = i * 0.016f; val x = f(t)
            r.step(16, x[0], x[1], x[2], live, t)
            if (r.beat) beats++
            if (i >= n / 3) { maxLobe = maxOf(maxLobe, r.lobe); minLobe = minOf(minLobe, r.lobe); minRip = minOf(minRip, r.ripple) }
            maxPunch = maxOf(maxPunch, r.punch)
        }
        return Run(maxLobe, minLobe, maxPunch, beats, r.lobe, minRip)
    }
    private val quiet = { _: Float -> floatArrayOf(0.04f, 0.03f, 0.02f) }
    private val quietPulse = { t: Float -> floatArrayOf(0.05f + if (t % 1f < 0.15f) 0.06f else 0f, 0.04f, 0.02f) }
    private val mid = { t: Float -> floatArrayOf(if (t % 0.6f < 0.1f) 0.5f else 0.2f, 0.35f, 0.2f) }
    private val funk = { t: Float -> floatArrayOf(if (t % 0.5f < 0.12f) 0.9f else 0.25f, 0.5f, 0.3f) }

    @Test fun steadyQuietSongIsAliveButSmallAndNeverPunches() {
        val r = run(30, f = quiet)
        assertTrue(r.maxLobe < 0.35f)                 // small
        assertTrue(r.maxLobe - r.minLobe > 0.04f)     // breathing: never a still picture
        assertTrue(r.minRipple >= RingDynamics.RIPPLE_FLOOR - 0.001f)
        assertEquals(0f, r.maxPunch, 0f); assertEquals(0, r.beats)
    }

    @Test fun quietSoftBassPulsesAreVisibleButStillCalm() {
        val r = run(30, f = quietPulse)
        assertTrue(r.maxLobe - r.minLobe > 0.2f)      // the soft pulse shows
        assertTrue(r.maxLobe < 0.55f)                 // but stays calm
        assertEquals(0f, r.maxPunch, 0f)              // no zoom punch on a calm song
    }

    @Test fun bassHeavyFunkReachesFullLobesAndPunches() {
        val r = run(60, f = funk)
        assertTrue(r.maxLobe > 0.85f); assertTrue(r.maxPunch > 0.8f); assertTrue(r.beats > 60); assertTrue(r.maxLobe <= 1f)
    }

    @Test fun sizeFollowsTheSongsCharacter() {
        val calm = run(60, f = quietPulse); val m = run(60, f = mid); val f = run(60, f = funk)
        assertTrue(calm.maxLobe < m.maxLobe); assertTrue(m.maxLobe < f.maxLobe)
    }

    @Test fun noAudioDataGivesGentleIdleBreathing() {
        val r = run(10, live = false) { floatArrayOf(0f, 0f, 0f) }
        assertTrue(r.maxLobe in 0.03f..0.14f); assertTrue(r.maxLobe - r.minLobe > 0.04f)
        assertEquals(0f, r.maxPunch, 0f); assertEquals(0, r.beats)
    }

    @Test fun silenceWithLiveDataStillBreathesWithoutBadNumbers() {
        val r = run(10) { floatArrayOf(0f, 0f, 0f) }
        assertTrue(r.maxLobe - r.minLobe > 0.04f); assertTrue(r.maxLobe < 0.14f); assertTrue(!r.lobe.isNaN())
    }

    @Test fun outOfRangeInputIsClamped() {
        val r = run(5) { floatArrayOf(9f, -3f, Float.MAX_VALUE) }
        assertTrue(r.maxLobe <= 1f); assertTrue(!r.lobe.isNaN())
    }
}
