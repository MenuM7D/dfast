package dfast.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayNavTest {
    @Test fun historyOrderAndEmpty() {
        val h = PlayHistory<String>({ it })
        assertFalse(h.canBack()); assertNull(h.pop())
        h.push("a"); h.push("b")
        assertTrue(h.canBack()); assertEquals("b", h.pop()); assertEquals("a", h.pop()); assertFalse(h.canBack())
    }
    @Test fun historySameTwiceStoredOnceAndCapped() {
        val h = PlayHistory<String>({ it }, cap = 3)
        h.push("a"); h.push("a"); assertEquals(1, h.size)
        h.push("b"); h.push("c"); h.push("d"); assertEquals(3, h.size); assertEquals("d", h.pop()); h.pop(); assertEquals("b", h.pop())
    }
    @Test fun doubleTapRightForwardLtr() {
        val t = TapSkip()
        assertTrue(t.tap(0.9f, false, 1000) is TapSkip.R.Pending)
        val r = t.tap(0.9f, false, 1200) as TapSkip.R.Skip
        assertTrue(r.forward); assertEquals(10_000, r.deltaMs); assertEquals(10_000, r.totalMs)
    }
    @Test fun doubleTapLeftBackLtr() {
        val t = TapSkip(); t.tap(0.1f, false, 1000)
        assertFalse((t.tap(0.1f, false, 1100) as TapSkip.R.Skip).forward)
    }
    @Test fun sidesMirrorInRtl() {
        val t = TapSkip(); t.tap(0.9f, true, 1000)
        assertFalse((t.tap(0.9f, true, 1100) as TapSkip.R.Skip).forward)
        val u = TapSkip(); u.tap(0.1f, true, 1000)
        assertTrue((u.tap(0.1f, true, 1100) as TapSkip.R.Skip).forward)
    }
    @Test fun stacksWhileTapsKeepComing() {
        val t = TapSkip(); t.tap(0.9f, false, 1000); t.tap(0.9f, false, 1100)
        assertEquals(20_000, (t.tap(0.9f, false, 1500) as TapSkip.R.Skip).totalMs)
        assertEquals(30_000, (t.tap(0.9f, false, 2200) as TapSkip.R.Skip).totalMs)
    }
    @Test fun stackEndsAfterPauseAndOnOtherSide() {
        val t = TapSkip(); t.tap(0.9f, false, 1000); t.tap(0.9f, false, 1100)
        assertTrue(t.tap(0.9f, false, 5000) is TapSkip.R.Pending) // too late: a fresh first tap
        val u = TapSkip(); u.tap(0.9f, false, 1000); u.tap(0.9f, false, 1100)
        assertTrue(u.tap(0.1f, false, 1300) is TapSkip.R.Pending) // other side starts over
    }
    @Test fun slowSecondTapIsNotADoubleTap() {
        val t = TapSkip(); t.tap(0.9f, false, 1000)
        assertTrue(t.tap(0.9f, false, 1400) is TapSkip.R.Pending)
    }
    @Test fun middleNeverSkips() {
        val t = TapSkip(); t.tap(0.5f, false, 1000)
        assertTrue(t.tap(0.5f, false, 1100) is TapSkip.R.Pending)
    }
    @Test fun skipTargetClamps() {
        assertEquals(0, skipTarget(4000, 10_000, false, 100_000))
        assertEquals(100_000, skipTarget(95_000, 10_000, true, 100_000))
        assertEquals(25_000, skipTarget(15_000, 10_000, true, 0))
    }
    @Test fun clock() { assertEquals("3:05", fmtClock(185_000)); assertEquals("1:02:03", fmtClock(3_723_000)); assertEquals("0:00", fmtClock(-5)) }
    @Test fun fullscreenFollowsVideoShape() {
        assertTrue(fullscreenPortrait(1080, 1920)); assertFalse(fullscreenPortrait(1920, 1080)); assertFalse(fullscreenPortrait(0, 0))
        assertTrue(fullscreenPortrait(1920, 1080, 90)); assertFalse(fullscreenPortrait(1080, 1080))
    }
    @Test fun cardSlots() {
        assertEquals(CardSlot.OVER_VIDEO, cardSlot(false, false)); assertEquals(CardSlot.TOP, cardSlot(true, true)); assertEquals(CardSlot.RIGHT_ABOVE_DOCK, cardSlot(true, false))
    }
}
