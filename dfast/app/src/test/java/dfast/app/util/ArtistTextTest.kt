package dfast.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistTextTest {
    @Test fun dropsPlayCounts() {
        assertEquals("Lana Del Rey", ArtistText.clean("Lana Del Rey • 233 مليون عملية تشغيل"))
        assertEquals("Lana Del Rey", ArtistText.clean("Lana Del Rey • 233M plays"))
        assertEquals("Song • Artist", ArtistText.clean("Song • Artist • 1.2B views"))
    }
    @Test fun leavesPlainNamesAlone() {
        assertEquals("Maroon 5", ArtistText.clean("Maroon 5"))
        assertEquals("حوده بندق", ArtistText.clean("حوده بندق"))
        assertEquals("", ArtistText.clean(""))
        assertEquals("Maroon 5 • Pop", ArtistText.clean("Maroon 5 • Pop"))
    }
}
