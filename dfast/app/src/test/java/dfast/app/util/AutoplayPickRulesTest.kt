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
        assertEquals("b", AutoplayPick.pick(listOf(c("a", t = "Some English Song!"), c("b", t = "Other thing")), emptyList(), "x", emptyList(), ctx)!!.url)

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

class AutoplayPickStep1Test {
    private fun c(u: String, t: String = u, a: String = "", d: Long = 200) = AutoCand(u, t, a, "", d, false)
    private val ctx = AutoplayPick.Ctx("Some English song", "Chan", 200)
    private fun pick(l: List<AutoCand>, o: AutoplayPick.Opts = AutoplayPick.Opts()) = AutoplayPick.pick(l, emptyList(), "x", emptyList(), ctx, o)?.url

    @Test fun longCompilationNotPickedWhenOthersExist() = assertEquals("b", pick(listOf(c("a", t = "aaa", d = 5000), c("b", t = "bbb"))))
    @Test fun fullAlbumTitleNotPicked() = assertEquals("b", pick(listOf(c("a", t = "Artist Full Album 2020"), c("b", t = "Some other song"))))
    @Test fun nonStopAndOneHourNotPicked() {
        assertEquals("b", pick(listOf(c("a", t = "Best songs non stop"), c("b", t = "plain title"))))
        assertEquals("b", pick(listOf(c("a", t = "Rain sounds 1 hour"), c("b", t = "plain title"))))
    }
    @Test fun remixIsNotAMix() = assertEquals("a", pick(listOf(c("a", t = "Song (Remix)"), c("b", t = "plain title"))))
    @Test fun longFormKeptWhenItIsTheOnlyOne() = assertEquals("a", pick(listOf(c("a", t = "Full Album", d = 300))))
    @Test fun longFormAllowedWhenCurrentIsLong() {
        val longCtx = AutoplayPick.Ctx("Long show", "Chan", 4000)
        assertEquals("a", AutoplayPick.pick(listOf(c("a", t = "Another long show", d = 4200)), emptyList(), "x", emptyList(), longCtx)!!.url)
        assertFalse(AutoplayPick.longForm(c("a", d = 4200), longCtx))
        assertTrue(AutoplayPick.longForm(c("a", d = 4200), ctx))
    }
    @Test fun musicPrefersOtherArtist() {
        val l = listOf(c("a", t = "t1", a = "Chan"), c("b", t = "t2", a = "Other"), c("c", t = "t3", a = "Third"))
        assertEquals("b", pick(l, AutoplayPick.Opts(music = true)))
    }
    @Test fun musicAvoidsRecentAuthorsAndFallsBack() {
        val l = listOf(c("a", t = "t1", a = "Chan"), c("b", t = "t2", a = "Other"), c("c", t = "t3", a = "Third"))
        assertEquals("c", pick(l, AutoplayPick.Opts(music = true, recentAuthors = listOf("Other"))))
        assertEquals("a", pick(listOf(c("a", t = "t1", a = "Chan")), AutoplayPick.Opts(music = true)))
    }
    @Test fun musicBlankAuthorsStillPick() = assertEquals("a", pick(listOf(c("a", t = "t1"), c("b", t = "t2")), AutoplayPick.Opts(music = true)))
    @Test fun skipsRecentTitlesButFallsBack() {
        assertEquals("b", pick(listOf(c("a", t = "Seen Song"), c("b", t = "Fresh")), AutoplayPick.Opts(recentTitles = listOf("seen song!"))))
        assertEquals("a", pick(listOf(c("a", t = "Seen Song")), AutoplayPick.Opts(recentTitles = listOf("Seen Song"))))
    }
    @Test fun shuffleStaysInsideEligibleTop() {
        val l = (1..30).map { c("v$it", t = "title number $it") } + c("short", t = "s", d = 30)
        val seen = HashSet<String>()
        for (seed in 0 until 60) seen.add(pick(l, AutoplayPick.Opts(shuffle = true, rnd = kotlin.random.Random(seed)))!!)
        assertTrue(seen.size > 1)
        assertTrue(seen.all { it.startsWith("v") && it.removePrefix("v").toInt() <= 10 })
    }
    @Test fun dedupeKeepsFirst() {
        val l = listOf(c("a", t = "Song (Official)"), c("b", t = "Song official"), c("c", t = "Different"))
        assertEquals(listOf("a", "c"), AutoplayPick.dedupe(l) { it.title }.map { it.url })
    }
    @Test fun rankLongLast() = assertEquals(listOf("b", "d", "a", "c"), AutoplayPick.rankLongFormLast(listOf("a", "b", "c", "d")) { it == "a" || it == "c" })
    @Test fun inactivityLimits() {
        assertEquals(30L * 60 * 1000, AutoplayPick.limitFor(true)); assertEquals(4L * 3600 * 1000, AutoplayPick.limitFor(false))
        assertFalse(AutoplayPick.allowed(true, false, 31L * 60 * 1000, 0, AutoplayPick.limitFor(true)))
        assertTrue(AutoplayPick.allowed(true, false, 29L * 60 * 1000, 0, AutoplayPick.limitFor(true)))
    }
}
