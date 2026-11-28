package dfast.app.util

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NextPrefetchTest {
    private val r = MediaRef("https://youtu.be/B", "B")
    @Test fun usedOnlyForTheSameSongAndShuffle() {
        NextPrefetch.clear()
        assertNull(NextPrefetch.take("A", false))
        NextPrefetch.store("A", r, false)
        assertEquals(r, NextPrefetch.take("A", false))
        assertNull(NextPrefetch.take("other", false))
        assertNull(NextPrefetch.take("A", true))
        NextPrefetch.clear(); assertFalse(NextPrefetch.has("A", false))
    }
    @Test fun keepsSeveralSongsAhead() {
        NextPrefetch.clear()
        for (i in 1..5) NextPrefetch.store("s$i", MediaRef("u${i + 1}", "t"), false)
        for (i in 1..5) assertEquals("u${i + 1}", NextPrefetch.take("s$i", false)?.url)
        for (i in 6..12) NextPrefetch.store("s$i", r, false)
        assertNull(NextPrefetch.take("s1", false)); assertNotNull(NextPrefetch.take("s12", false))
    }
    @Test fun wiredInEveryNextPath() {
        val j = File("src/main/java").let { if (it.exists()) it else File("app/src/main/java") }
        val act = File(j, "dfast/app/ui/play/PlayerActivity.kt").readText()
        assertTrue(Regex("NextPrefetch\\.take\\(ref\\.url, shuffleOn\\)").findAll(act).count() >= 2)
        assertTrue(act.contains("NextPrefetch.store(forUrl"))
        assertTrue(!act.contains("p.duration - p.currentPosition <= 20_000 && prefetchedFor"))
        val chain = File(j, "dfast/app/util/PlayChain.kt").readText()
        assertTrue(chain.contains("NextPrefetch.take(c.url, shuffle) ?: pickNext(c)") && chain.contains("fun warm()"))
        assertTrue(chain.contains("fun rollAhead") && chain.contains("AHEAD = 3") && act.contains("PlayChain.rollAhead(n, sh)"))
        assertTrue(File(j, "dfast/app/services/PlaybackService.kt").readText().contains("PlayChain.warm()"))
    }
}
