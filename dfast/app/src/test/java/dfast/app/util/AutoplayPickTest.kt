package dfast.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoplayPickTest {
    private fun c(u: String, d: Long = 200, live: Boolean = false) = AutoCand(u, u, "", "", d, live)

    @Test fun firstUsableRelatedWins() = assertEquals("a", AutoplayPick.next(listOf(c("a"), c("b")), emptyList(), "x", emptyList())!!.url)

    @Test fun skipsTheSameVideo() = assertEquals("b", AutoplayPick.next(listOf(c("a"), c("b")), emptyList(), "a", emptyList())!!.url)

    @Test fun skipsLiveAndZeroLength() =
        assertEquals("d", AutoplayPick.next(listOf(c("a", live = true), c("b", d = 0), c("d")), emptyList(), "x", emptyList())!!.url)

    @Test fun noRepeatOfLast20() {
        val recent = (1..25).map { "v$it" }
        // v1..v5 fell out of the window of 20, v6..v25 are still blocked
        assertEquals("v3", AutoplayPick.next(listOf(c("v10"), c("v3")), emptyList(), "x", recent)!!.url)
        assertNull(AutoplayPick.next(listOf(c("v10"), c("v25")), emptyList(), "x", recent))
    }

    @Test fun fallsBackToLocalPoolOnlyWhenRelatedHasNothingUsable() {
        assertEquals("loc", AutoplayPick.next(emptyList(), listOf(c("loc", d = -1)), "x", emptyList())!!.url)
        assertEquals("loc", AutoplayPick.next(listOf(c("a", live = true)), listOf(c("loc", d = -1)), "x", emptyList())!!.url)
        assertEquals("a", AutoplayPick.next(listOf(c("a")), listOf(c("loc")), "x", emptyList())!!.url)
    }

    @Test fun localWithUnknownLengthIsAllowed() = assertEquals("l", AutoplayPick.next(emptyList(), listOf(c("l", d = -1)), "x", emptyList())!!.url)

    @Test fun nothingUsableMeansStop() {
        assertNull(AutoplayPick.next(emptyList(), emptyList(), "x", emptyList()))
        assertNull(AutoplayPick.next(listOf(c("x")), listOf(c("x")), "x", emptyList()))
        assertNull(AutoplayPick.next(listOf(c("")), emptyList(), "x", emptyList()))
    }

    @Test fun modeMemory() {
        assertTrue(AutoplayPick.audioFor(currentIsAudio = true, userTapped = false))
        assertFalse(AutoplayPick.audioFor(currentIsAudio = false, userTapped = false))
        assertFalse(AutoplayPick.audioFor(currentIsAudio = true, userTapped = true))
    }

    @Test fun switchRepeatAndInactivity() {
        val now = 10L * 60 * 60 * 1000
        assertTrue(AutoplayPick.allowed(true, false, now, now - 1000))
        assertFalse(AutoplayPick.allowed(false, false, now, now - 1000))
        assertFalse(AutoplayPick.allowed(true, true, now, now - 1000))
        assertFalse(AutoplayPick.allowed(true, false, now, now - AutoplayPick.INACTIVITY_LIMIT_MS))
    }

    @Test fun durationParsing() {
        assertEquals(224L, AutoplayPick.parseDuration("3:44"))
        assertEquals(3723L, AutoplayPick.parseDuration("1:02:03"))
        assertEquals(-1L, AutoplayPick.parseDuration(""))
        assertEquals(-1L, AutoplayPick.parseDuration("LIVE"))
    }
}
