package dfast.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RingDynamicsTest {
    private class Run(val maxLobe: Float, val maxPunch: Float, val beats: Int, val lobe: Float, val ripple: Float)

    private fun run(secs: Int, live: Boolean = true, f: (Float) -> FloatArray): Run {
        val r = RingDynamics(); val n = secs * 1000 / 16
        var maxLobe = 0f; var maxPunch = 0f; var beats = 0
        for (i in 0 until n) {
            val t = i * 0.016f; val x = f(t)
            r.step(16, x[0], x[1], x[2], live, t)
            if (r.beat) beats++
            if (i >= n / 3) maxLobe = maxOf(maxLobe, r.lobe)
            maxPunch = maxOf(maxPunch, r.punch)
        }
        return Run(maxLobe, maxPunch, beats, r.lobe, r.ripple)
    }

    @Test fun steadyQuietSongStaysSmallAndNeverPunches() {
        val r = run(30) { floatArrayOf(0.12f, 0.10f, 0.05f) }
        assertTrue(r.maxLobe < 0.3f); assertEquals(0f, r.maxPunch, 0f); assertEquals(0, r.beats)
    }

    @Test fun calmSongWithSoftPulsesDoesNotPunch() {
        val r = run(30) { t -> floatArrayOf(0.15f + if (t % 1f < 0.1f) 0.08f else 0f, 0.1f, 0.05f) }
        assertEquals(0f, r.maxPunch, 0f); assertTrue(r.maxLobe < 0.5f)
    }

    @Test fun bassHeavyFunkReachesFullLobesAndPunches() {
        val r = run(60) { t -> floatArrayOf(if (t % 0.5f < 0.12f) 0.9f else 0.25f, 0.5f, 0.3f) }
        assertTrue(r.maxLobe > 0.8f); assertTrue(r.maxPunch > 0.8f); assertTrue(r.beats > 60)
    }

    @Test fun midEnergySongIsBetweenCalmAndFunk() {
        val calm = run(60) { t -> floatArrayOf(if (t % 0.6f < 0.1f) 0.2f else 0.1f, 0.15f, 0.1f) }
        val mid = run(60) { t -> floatArrayOf(if (t % 0.6f < 0.1f) 0.5f else 0.2f, 0.35f, 0.2f) }
        val funk = run(60) { t -> floatArrayOf(if (t % 0.5f < 0.12f) 0.9f else 0.25f, 0.5f, 0.3f) }
        assertTrue(calm.maxLobe < mid.maxLobe); assertTrue(mid.maxLobe < funk.maxLobe)
    }

    @Test fun noAudioDataGivesGentleIdleBreathing() {
        val r = run(10, live = false) { floatArrayOf(0f, 0f, 0f) }
        assertTrue(r.lobe in 0.03f..0.12f); assertEquals(0f, r.maxPunch, 0f); assertEquals(0, r.beats)
    }

    @Test fun silenceWithLiveDataStaysAtTheFloorWithoutBadNumbers() {
        val r = run(10) { floatArrayOf(0f, 0f, 0f) }
        assertEquals(RingDynamics.FLOOR, r.lobe, 0.001f); assertTrue(!r.ripple.isNaN())
    }

    @Test fun outOfRangeInputIsClamped() {
        val r = run(5) { floatArrayOf(9f, -3f, Float.MAX_VALUE) }
        assertTrue(r.maxLobe <= 1f); assertTrue(!r.lobe.isNaN())
    }
}
