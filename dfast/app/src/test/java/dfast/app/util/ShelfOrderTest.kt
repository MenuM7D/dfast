package dfast.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfOrderTest {
    private val day = 86_400_000L
    @Test fun noUseKeepsBaseOrder() { assertEquals(50, ShelfOrder.rank(5, 0.0)); assertTrue(ShelfOrder.rank(1, 0.0) < ShelfOrder.rank(2, 0.0)) }
    @Test fun usedShelfMovesUpButIsCapped() {
        assertTrue(ShelfOrder.rank(4, 3.0) < ShelfOrder.rank(4, 0.0))
        assertEquals(ShelfOrder.rank(4, 5.0), ShelfOrder.rank(4, 500.0))
        // capped at 2.5 positions: a base-5 shelf never jumps above a base-2 shelf
        assertTrue(ShelfOrder.rank(5, 500.0) > ShelfOrder.rank(2, 0.0))
    }
    @Test fun usageFadesWithTime() {
        assertEquals(4.0, ShelfOrder.decayed(8.0, 0, (14 * day)), 1e-6 * 8)
        assertEquals(0.0, ShelfOrder.decayed(0.0, 0, day), 0.0)
        assertEquals(3.0, ShelfOrder.decayed(3.0, 100, 100), 0.0)
    }
    @Test fun parseIsSafe() {
        assertEquals(0.0 to 0L, ShelfOrder.parse(null)); assertEquals(0.0 to 0L, ShelfOrder.parse("junk"))
        assertEquals(2.5 to 7L, ShelfOrder.parse("2.5|7"))
    }
}
