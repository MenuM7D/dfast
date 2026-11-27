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
        assertTrue(r.maxPunch < 0.45f)                // a calm song kicks gently
    }

    @Test fun bassHeavyFunkReachesFullLobesAndPunches() {
        val r = run(60, f = funk)
        assertTrue(r.maxLobe > 0.85f); assertTrue(r.maxPunch > 0.8f); assertTrue(r.beats > 100); assertTrue(r.maxLobe <= 1f)
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

    private fun runWith(mode: String, f: (Float) -> FloatArray): Run {
        val r = RingDynamics(); r.intensity = RingDynamics.intensityFor(mode)
        var maxL = 0f; var minL = 1f; var maxP = 0f
        for (i in 0 until 30 * 1000 / 16) { val t = i * 0.016f; val x = f(t); r.step(16, x[0], x[1], x[2], true, t)
            if (i > 600) { maxL = maxOf(maxL, r.lobe); minL = minOf(minL, r.lobe) }; maxP = maxOf(maxP, r.punch) }
        return Run(maxL, minL, maxP, 0, r.lobe, 0f)
    }

    @Test fun intensityModesOrderAndStayInRange() {
        val calm = runWith("calm", funk); val auto = runWith("auto", funk); val strong = runWith("strong", funk)
        assertTrue(calm.maxLobe < auto.maxLobe); assertTrue(auto.maxLobe <= strong.maxLobe)
        assertTrue(strong.maxLobe <= 1f); assertTrue(calm.minLobe >= 0f)
        assertTrue(calm.maxPunch <= auto.maxPunch)
        assertEquals(1f, RingDynamics.intensityFor("nonsense"), 0f)
        assertEquals(1f, RingDynamics.intensityFor(null), 0f)
    }

    @Test fun quietSongStaysAliveInEveryMode() {
        for (m in listOf("calm", "auto", "strong")) {
            val r = runWith(m, quiet)
            assertTrue(m, r.maxLobe - r.minLobe > 0.03f)
            assertTrue(m, r.maxLobe < 0.5f)
        }
    }

    // ---- beat detection across the whole song (the old rule fired only on the very loudest hits) ----
    private val perMin = 60 / 0.5   // funk kick every 0.5 s = 120 kicks in 60 s
    @Test fun funkKicksAreFoundAlmostEveryTime() {
        val r = run(60, f = funk)
        assertTrue("beats=${r.beats}", r.beats in 105..125)
    }

    @Test fun softSongOnItsOwnBeatsStillKicksButGently() {
        val r = run(60, f = quietPulse)                 // one soft bass pulse per second = 60
        assertTrue("beats=${r.beats}", r.beats in 50..65)
        assertTrue("punch=${r.maxPunch}", r.maxPunch > 0.1f && r.maxPunch < 0.45f)
    }

    @Test fun slowSadBalladWithTwoSecondBeats() {
        val r = run(60) { t -> floatArrayOf(if (t % 2f < 0.2f) 0.12f else 0.05f, 0.04f, 0.02f) }
        assertTrue("beats=${r.beats}", r.beats in 25..32); assertTrue(r.maxPunch < 0.45f)
    }

    @Test fun oneHugeHitDoesNotSilenceTheNormalBeatsAfterIt() {
        val r = run(60) { t -> floatArrayOf(if (t > 10f && t < 10.3f) 0.98f else if (t % 0.6f < 0.1f) 0.5f else 0.2f, 0.3f, 0.2f) }
        assertTrue("beats=${r.beats}", r.beats > 85)
    }

    @Test fun steadyLoudOrQuietOrNoiseDoNotShake() {
        assertEquals(0, run(60) { floatArrayOf(0.8f, 0.5f, 0.3f) }.beats)
        assertEquals(0, run(60) { floatArrayOf(0.04f, 0.03f, 0.02f) }.beats)
        val rnd = java.util.Random(1)
        assertTrue(run(60) { floatArrayOf(0.3f + (rnd.nextFloat() - 0.5f) * 0.06f, 0.2f, 0.1f) }.beats <= 3)
    }

    @Test fun noBeatsAreInventedWithoutLiveData() {
        val r = run(20, live = false) { floatArrayOf(0.9f, 0.9f, 0.9f) }
        assertEquals(0, r.beats); assertEquals(0f, r.maxPunch, 0f)
    }

    @Test fun beatsStartAfterTheStatisticsWarmUp() {
        val r = RingDynamics(); var first = -1f
        for (i in 0 until 400) { val t = i * 0.016f; r.step(16, if (t % 0.5f < 0.12f) 0.9f else 0.25f, 0.3f, 0.2f, true, t); if (r.beat && first < 0) first = t }
        assertTrue("first=$first", first >= 1.2f)
    }

    @Test fun everyBeatStartsAShockWaveThatTravelsOutAndFades() {
        val r = RingDynamics(); var seen = false; var maxProg = 0f
        for (i in 0 until 600) { val t = i * 0.016f; r.step(16, if (t % 0.5f < 0.12f) 0.9f else 0.25f, 0.3f, 0.2f, true, t)
            for (w in 0 until 3) { val p = r.waveProgress(w); if (p in 0f..1f && r.waveStrength(w) > 0.5f) { seen = true; maxProg = maxOf(maxProg, p) } } }
        assertTrue(seen); assertTrue(maxProg > 0.9f)
    }
}
