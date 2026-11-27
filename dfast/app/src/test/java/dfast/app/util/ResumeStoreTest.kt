package dfast.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeStoreTest {
    private fun e(u: String, ts: Long = 0) = ResumeStore.Entry(u, "t", "a", "", "", 1000, 200000, ts)
    @Test fun keepRules() {
        assertTrue(ResumeStore.shouldKeep(60_000, 300_000))
        assertFalse(ResumeStore.shouldKeep(10_000, 300_000))       // barely started
        assertFalse(ResumeStore.shouldKeep(290_000, 300_000))      // nearly finished
        assertFalse(ResumeStore.shouldKeep(60_000, 90_000))        // a clip, not worth it
        assertTrue(ResumeStore.shouldKeep(15_000, 120_000)); assertTrue(ResumeStore.shouldKeep(100_000, 120_000)); assertFalse(ResumeStore.shouldKeep(101_000, 120_000))
    }
    @Test fun finishedRule() { assertTrue(ResumeStore.finished(285_000, 300_000)); assertFalse(ResumeStore.finished(60_000, 300_000)); assertFalse(ResumeStore.finished(5, 0)) }
    @Test fun resumeRewindsALittle() { assertEquals(57_000, ResumeStore.resumeAt(60_000)); assertEquals(0, ResumeStore.resumeAt(1000)) }
    @Test fun mergeNewestFirstNoDuplicatesCapped() {
        var l = listOf<ResumeStore.Entry>()
        for (i in 1..25) l = ResumeStore.merge(l, e("u$i"))
        assertEquals(20, l.size); assertEquals("u25", l.first().url); assertEquals("u6", l.last().url)
        l = ResumeStore.merge(l, e("u10")); assertEquals("u10", l.first().url); assertEquals(1, l.count { it.url == "u10" }); assertEquals(20, l.size)
    }
    @Test fun idsFromLinkShapes() {
        assertEquals("dQw4w9WgXcQ", UrlIds.of("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=abc"))
        assertEquals("dQw4w9WgXcQ", UrlIds.of("https://youtu.be/dQw4w9WgXcQ?t=5"))
        assertEquals("dQw4w9WgXcQ", UrlIds.of("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals("", UrlIds.of("https://example.com/x"))
    }
}
