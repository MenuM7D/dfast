package dfast.app.util

import dfast.app.util.UpdateReminder.HOUR
import dfast.app.util.UpdateReminder.State
import org.junit.Assert.*
import org.junit.Test

class UpdateReminderTest {
    private val t0 = 1_000_000_000L
    @Test fun newVersionShowsAtOnce() { assertTrue(UpdateReminder.shouldShow("v1.0.2", State(), t0)) }
    @Test fun notAgainRightAfterLater() {
        val s = UpdateReminder.afterShown("v1.0.2", State(), t0)
        assertFalse(UpdateReminder.shouldShow("v1.0.2", s, t0 + 1 * HOUR)); assertFalse(UpdateReminder.shouldShow("v1.0.2", s, t0 + 23 * HOUR))
    }
    @Test fun firstReminderAfter24h() { assertTrue(UpdateReminder.shouldShow("v1.0.2", UpdateReminder.afterShown("v1.0.2", State(), t0), t0 + 24 * HOUR)) }
    @Test fun secondReminderAfter3DaysThenNever() {
        val s1 = UpdateReminder.afterShown("v1.0.2", State(), t0)
        val s2 = UpdateReminder.afterShown("v1.0.2", s1, t0 + 25 * HOUR)
        assertFalse(UpdateReminder.shouldShow("v1.0.2", s2, t0 + 25 * HOUR + 71 * HOUR)); assertTrue(UpdateReminder.shouldShow("v1.0.2", s2, t0 + 25 * HOUR + 72 * HOUR))
        val s3 = UpdateReminder.afterShown("v1.0.2", s2, t0 + 100 * HOUR)
        assertFalse(UpdateReminder.shouldShow("v1.0.2", s3, t0 + 10_000 * HOUR))
    }
    @Test fun newerVersionRestartsTheCycle() {
        val s3 = State("v1.0.2", 3, t0)
        assertTrue(UpdateReminder.shouldShow("v1.0.3", s3, t0 + HOUR)); assertEquals(1, UpdateReminder.afterShown("v1.0.3", s3, t0).shown)
    }
}
