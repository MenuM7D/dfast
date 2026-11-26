package dfast.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoplayPickRulesTest {
    private fun c(u: String, t: String = u, a: String = "", d: Long = 200) = AutoCand(u, t, a, "", d, false)
    private val ctx = AutoplayPick.Ctx("Some English song", "Chan", 200)

    @Test fun skipsShorts() =
        assertEquals("b", AutoplayPick.pick(listOf(c("a", d = 45), c("b")), emptyList(), "x", emptyList(), ctx)!!.url)

    @Test fun skipsNearDuplicateTitle() =
        assertEquals("b", AutoplayPick.pick(listOf(c("a", t = "Some English song (Official)"), c("b", t = "Other thing")), emptyList(), "x", emptyList(), ctx)!!.url)

    @Test fun keepsScript() =
        assertEquals("b", AutoplayPick.pick(listOf(c("a", t = "اغنية عربية"), c("b", t = "Another english one")), emptyList(), "x", emptyList(), ctx)!!.url)

    @Test fun keepsLengthRatio() =
        assertEquals("b", AutoplayPick.pick(listOf(c("a", t = "aa", d = 3000), c("b", t = "bb", d = 250)), emptyList(), "x", emptyList(), ctx)!!.url)

    @Test fun comfortSkippedWhenNothingPasses() =
        assertEquals("a", AutoplayPick.pick(listOf(c("a", t = "اغنية", d = 3000)), emptyList(), "x", emptyList(), ctx)!!.url)

    @Test fun sameChannelInFirstFiveWins() {
        val l = listOf(c("a", t = "t1"), c("b", t = "t2"), c("c", t = "t3", a = "Chan"))
        assertEquals("c", AutoplayPick.pick(l, emptyList(), "x", emptyList(), ctx)!!.url)
    }

    @Test fun sameChannelBeyondWindowIgnored() {
        val l = (1..6).map { c("v$it", t = "t$it") } + c("z", t = "tz", a = "Chan")
        assertEquals("v1", AutoplayPick.pick(l, emptyList(), "x", emptyList(), ctx)!!.url)
    }

    @Test fun fallsBackToLocal() =
        assertEquals("l", AutoplayPick.pick(emptyList(), listOf(c("l")), "x", emptyList(), ctx)!!.url)

    @Test fun nothingAtAll() = assertNull(AutoplayPick.pick(emptyList(), emptyList(), "x", emptyList(), ctx))

    @Test fun inactivityBoundary() {
        val lim = AutoplayPick.INACTIVITY_LIMIT_MS
        assertTrue(AutoplayPick.allowed(true, false, lim - 1, 0))
        assertFalse(AutoplayPick.allowed(true, false, lim, 0))
        assertTrue(AutoplayPick.allowed(true, false, lim + 5, 6)) // a touch resets it
        assertFalse(AutoplayPick.allowed(false, false, 0, 0))
        assertFalse(AutoplayPick.allowed(true, true, 0, 0))
    }
}
