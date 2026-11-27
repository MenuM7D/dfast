package dfast.app.util

import dfast.app.util.SearchFilter.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchFilterTest {
    private val now = 1_000_000_000_000L
    private val day = 86_400_000L
    @Test fun chipsMapToKinds() {
        assertEquals(Kind.VIDEOS, SearchFilter.kindOf("videos")); assertEquals(Kind.SHORTS, SearchFilter.kindOf("shorts"))
        assertEquals(Kind.LIVE, SearchFilter.kindOf("live")); assertEquals(Kind.RECENT, SearchFilter.kindOf("recent"))
        assertEquals(Kind.ALL, SearchFilter.kindOf("all")); assertFalse(SearchFilter.isVideoChip("playlists"))
    }
    @Test fun shortsBoundary() {
        assertTrue(SearchFilter.keep(Kind.SHORTS, 60, false, null, now)); assertFalse(SearchFilter.keep(Kind.SHORTS, 61, false, null, now))
        assertFalse(SearchFilter.keep(Kind.SHORTS, 0, false, null, now))
    }
    @Test fun videosExcludeShortsAndLive() {
        assertFalse(SearchFilter.keep(Kind.VIDEOS, 60, false, null, now)); assertTrue(SearchFilter.keep(Kind.VIDEOS, 61, false, null, now))
        assertFalse(SearchFilter.keep(Kind.VIDEOS, 0, true, null, now))
    }
    @Test fun liveOnlyLive() { assertTrue(SearchFilter.keep(Kind.LIVE, 0, true, null, now)); assertFalse(SearchFilter.keep(Kind.LIVE, 300, false, null, now)) }
    @Test fun recentWindow() {
        assertTrue(SearchFilter.keep(Kind.RECENT, 300, false, now - 29 * day, now))
        assertFalse(SearchFilter.keep(Kind.RECENT, 300, false, now - 31 * day, now))
        assertFalse(SearchFilter.keep(Kind.RECENT, 300, false, null, now))
        assertFalse(SearchFilter.keep(Kind.RECENT, 300, false, now + day, now))
    }
    @Test fun allNeedsLength() { assertTrue(SearchFilter.keep(Kind.ALL, 5, false, null, now)); assertFalse(SearchFilter.keep(Kind.ALL, 0, true, null, now)) }
    @Test fun suffix() { assertEquals(" #shorts", SearchFilter.querySuffix(Kind.SHORTS)); assertEquals("", SearchFilter.querySuffix(Kind.ALL)) }
}
