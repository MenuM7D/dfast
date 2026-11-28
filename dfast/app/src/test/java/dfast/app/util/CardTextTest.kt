package dfast.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardTextTest {
    @Test fun oneLine() {
        assertEquals("القلب كبير", CardText.oneLine("  القلب\n\u200Fكبير \r\n"))
        assertEquals("a b", CardText.oneLine("a\u2028b"))
        assertNull(CardText.oneLine("\n \u200B"))
        assertNull(CardText.oneLine(null))
    }
}
