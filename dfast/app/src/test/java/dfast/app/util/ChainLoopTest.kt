package dfast.app.util

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ChainLoopTest {
    private val names = listOf("Alpha Dawn", "Bravo Tide", "Charlie Wind", "Delta Frost", "Echo Ember", "Foxtrot Haze")
    private fun cand(i: Int) = AutoCand("u$i", names[i], "artist$i", "", 200, false)
    // B and C list each other first: the shape that made the notification chain bounce
    private val related = mapOf(
        0 to listOf(1, 2, 3, 4, 5), 1 to listOf(0, 2, 3, 4, 5), 2 to listOf(1, 3, 4, 5, 0),
        3 to listOf(2, 4, 5, 0, 1), 4 to listOf(3, 5, 0, 1, 2), 5 to listOf(4, 0, 1, 2, 3))

    private fun walk(record: Boolean, music: Boolean, shuffle: Boolean, steps: Int): List<Int> {
        val recent = ArrayList<String>(); val authors = ArrayList<String>(); val titles = ArrayList<String>()
        var cur = 0; recent.add("u0"); authors.add("artist0"); titles.add(names[0])
        val out = arrayListOf(0)
        repeat(steps) {
            val c = cand(cur)
            val pick = AutoplayPick.pick(related[cur]!!.map(::cand), emptyList(), c.url, recent.toList(),
                AutoplayPick.Ctx(c.title, c.author, 200),
                AutoplayPick.Opts(music = music, recentAuthors = authors.toList(), recentTitles = titles.toList(), shuffle = shuffle, rnd = kotlin.random.Random(7))) ?: return out
            cur = pick.url.removePrefix("u").toInt(); out.add(cur)
            if (record) { recent.add(pick.url); authors.add(pick.author); titles.add(pick.title) }
        }
        return out
    }
    private fun bounces(w: List<Int>) = w.windowed(3).any { it[0] == it[2] }

    @Test fun withoutMemoryItBounces() { assertTrue(bounces(walk(false, false, false, 8))) }
    @Test fun withMemoryNoSongRepeatsWithinSixSteps() {
        for ((m, sh) in listOf(false to false, true to false, false to true)) {
            val w = walk(true, m, sh, 5)
            assertEquals("music=$m shuffle=$sh $w", w.size, w.toSet().size)
        }
    }
    private val j = File("src/main/java").let { if (it.exists()) it else File("app/src/main/java") }
    @Test fun chainGoRecordsEverySwitch() {
        val chain = File(j, "dfast/app/util/PlayChain.kt").readText()
        val go = chain.substring(chain.indexOf("private fun go("), chain.indexOf("private suspend fun pickNext"))
        assertTrue(go.contains("AutoplayState.played(t.url)") && go.contains("AutoplayState.playedMeta(t.author, t.title)"))
        assertTrue(go.indexOf("playStream") < go.indexOf("AutoplayState.played"))
    }
    @Test fun autoplayToggleAppliesAtOnce() {
        val act = File(j, "dfast/app/ui/play/PlayerActivity.kt").readText()
        val f = act.substring(act.indexOf("private fun onAutoplayToggled"), act.indexOf("private fun onEnded"))
        assertTrue(f.contains(".apply()") && !f.contains(".commit()"))
        assertTrue(f.indexOf("applyHold()") in 0 until f.indexOf("cancelNext()"))
        // the end-of-song chain reads the same saved setting live, so notification-side playback follows the toggle with no restart
        assertTrue(File(j, "dfast/app/util/PlayChain.kt").readText().contains("getBoolean(\"autoplay_next\", true)"))
    }
}
