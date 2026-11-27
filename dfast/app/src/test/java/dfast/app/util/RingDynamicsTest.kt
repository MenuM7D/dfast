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
        assertTrue(r.maxLobe > 0.85f); assertTrue(r.maxLobe <= 1f)
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

    // ---- onsets (found by OnsetDetector, released by BeatSync) drive the kick, the glow and the shock waves ----
    private fun loudSong(): RingDynamics { val r = RingDynamics(); for (i in 0 until 1000) { val t = i * 0.016f; r.step(16, if (t % 0.5f < 0.12f) 0.9f else 0.25f, 0.5f, 0.3f, true, t) }; return r }

    @Test fun strongOnsetOnLoudSongKicksFullyAndFades() {
        val r = loudSong(); r.onset(1f)
        assertTrue("punch=${r.punch}", r.punch > 0.95f && r.punch <= 1f)
        for (i in 0 until 40) r.step(16, 0.25f, 0.5f, 0.3f, true, 20f + i * 0.016f)
        assertTrue(r.punch < 0.1f)
    }

    @Test fun weakerOnsetsKickLess() {
        val a = loudSong(); a.onset(0.2f); val b = loudSong(); b.onset(1f)
        assertTrue(a.punch < b.punch); assertTrue(a.punch > 0.2f)
    }

    @Test fun calmSongKicksGentlyAndCalmModeLess() {
        val calm = RingDynamics(); for (i in 0 until 1000) calm.step(16, 0.05f, 0.04f, 0.02f, true, i * 0.016f)
        calm.onset(1f); assertTrue("punch=${calm.punch}", calm.punch < 0.55f)
        val c = loudSong(); c.intensity = RingDynamics.intensityFor("calm"); c.onset(1f)
        val s = loudSong(); s.intensity = RingDynamics.intensityFor("strong"); s.onset(1f)
        assertTrue(c.punch < s.punch); assertTrue(s.punch <= 1f)
    }

    @Test fun everyOnsetStartsAShockWaveThatTravelsOutAndFades() {
        val r = loudSong(); r.onset(0.8f); var maxProg = 0f; var seen = false
        for (i in 0 until 60) { r.step(16, 0.25f, 0.5f, 0.3f, true, 20f + i * 0.016f)
            for (w in 0 until 3) { val p = r.waveProgress(w); if (p in 0f..1f && r.waveStrength(w) > 0.5f) { seen = true; maxProg = maxOf(maxProg, p) } } }
        assertTrue(seen); assertTrue(maxProg > 0.9f)
    }

    @Test fun threeQuickOnsetsUseThreeWaves() {
        val r = loudSong(); r.onset(1f); r.step(16, 0.3f, 0.5f, 0.3f, true, 30f); r.onset(1f); r.step(16, 0.3f, 0.5f, 0.3f, true, 30f); r.onset(1f)
        var live = 0; for (w in 0 until 3) if (r.waveProgress(w) < 0.5f) live++
        assertEquals(3, live)
    }

    @Test fun noOnsetMeansNoPunchOnLoudSteadyMusic() {
        val r = RingDynamics(); var mx = 0f
        for (i in 0 until 2000) { r.step(16, 0.8f, 0.5f, 0.3f, true, i * 0.016f); mx = maxOf(mx, r.punch) }
        assertEquals(0f, mx, 0f)
    }
}
