package dfast.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BeatSyncTest {
    /** A player that is 500 ms ahead of its speakers, position 1000 ms at the flush. */
    private fun drive(s: BeatSync, gen: Int = 1, flushAgo: Long = 20, steps: Int = 200, leadMs: Double = 500.0, speed: Float = 1f, p0: Long = 1000) {
        s.observe(gen, 1000L, 1000L + flushAgo, 0.0, p0, true, speed)        // first look after the flush
        for (i in 1..steps) {
            val t = i * 16L
            s.observe(gen, 1000L, 1000L + flushAgo + t, leadMs + t, p0 + t, true, speed)   // fed runs ahead by the lead, position follows time
        }
    }

    @Test fun measuresTheLeadWhileThePlayerPlays() {
        val s = BeatSync(); drive(s)
        assertTrue(s.trusted); assertEquals(500.0, s.leadMs(), 5.0)
    }

    @Test fun followsDifferentOutputs() {
        val a = BeatSync(); drive(a, leadMs = 250.0); val b = BeatSync(); drive(b, leadMs = 750.0)
        assertEquals(250.0, a.leadMs(), 5.0); assertEquals(750.0, b.leadMs(), 5.0)
    }

    @Test fun usesTheFallbackUntilItHasSamples() {
        val s = BeatSync(); drive(s, steps = 5)
        assertTrue(!s.trusted); assertEquals(BeatSync.FALLBACK_MS, s.leadMs(), 0.0)
    }

    @Test fun aBaselineTakenLongAfterTheFlushIsNotTrusted() {
        val s = BeatSync(); drive(s, flushAgo = 900)
        assertTrue(!s.trusted); assertEquals(BeatSync.FALLBACK_MS, s.leadMs(), 0.0)
    }

    @Test fun otherPlaybackSpeedsAreNotMeasured() {
        val s = BeatSync(); drive(s, speed = 1.5f)
        assertTrue(!s.trusted)
    }

    @Test fun implausibleSamplesAreIgnored() {
        val s = BeatSync(); drive(s)
        val before = s.leadMs()
        s.observe(1, 1000L, 9000L, 99999.0, 1000, true, 1f); s.observe(1, 1000L, 9000L, 0.0, 5000, true, 1f)
        assertEquals(before, s.leadMs(), 1e-9)
    }

    @Test fun aNewFlushStartsAgainAndAdoptsTheNewLead() {
        val s = BeatSync(); drive(s, gen = 1, leadMs = 500.0); drive(s, gen = 2, leadMs = 300.0, p0 = 40000)
        assertEquals(300.0, s.leadMs(), 5.0)
    }

    @Test fun pausedPlayerKeepsTheLastLead() {
        val s = BeatSync(); drive(s)
        val l = s.leadMs(); s.observe(1, 1000L, 20000L, 0.0, 1000, false, 1f)
        assertEquals(l, s.leadMs(), 1e-9)
    }

    @Test fun anOnsetIsShownWhenItsSoundIsHeard() {
        assertEquals(10_650.0, BeatSync.fireAtMs(10_000L, 500.0, 150.0, 0.0), 1e-9)
        assertEquals(10_500.0, BeatSync.fireAtMs(10_000L, 500.0, 0.0, 0.0), 1e-9)   // wired: no extra
    }
}
