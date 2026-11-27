package dfast.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RingPowerTest {
    @Test fun stopsOnlyWhenEnabledLowAndNotCharging() {
        assertTrue(RingPower.saverActive(true, 20, false))
        assertTrue(RingPower.saverActive(true, 5, false))
        assertFalse(RingPower.saverActive(true, 21, false))
        assertFalse(RingPower.saverActive(true, 10, true))
        assertFalse(RingPower.saverActive(false, 10, false))
        assertFalse(RingPower.saverActive(true, -1, false))   // unknown level never stops the ring
    }
}
