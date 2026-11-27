package dfast.app.util

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelBrowseTest {
    private fun res(n: String) = ChannelBrowseTest::class.java.classLoader!!.getResourceAsStream(n)!!.readBytes().toString(Charsets.UTF_8)

    @Test fun durationAndCount() {
        assertEquals(2501L, ChannelBrowse.durationOf("41:41"))
        assertEquals(3723L, ChannelBrowse.durationOf("1:02:03"))
        assertEquals(-1L, ChannelBrowse.durationOf("LIVE"))
        assertEquals(5_500_000L, ChannelBrowse.parseCount("5.5M"))
        assertEquals(214_000L, ChannelBrowse.parseCount("214K"))
        assertEquals(-1L, ChannelBrowse.parseCount("x"))
    }

    @Test fun savedVideosTabHasSortChipsAndItems() {
        val p = ChannelBrowse.parse(JSONObject(res("channel_videos_sample.json")), "Chan")
        val chips = ChannelBrowse.sortChips(p)
        assertNotNull(chips)
        assertEquals(listOf("Latest", "Popular", "Oldest"), chips!!.map { it.label })
        assertTrue(p.streams.isNotEmpty())
        assertTrue(p.streams.all { it.url.contains("watch?v=") && it.name.isNotEmpty() })
        assertNotNull(p.next)
        assertTrue(chips.none { it.token == p.next })
    }

    @Test fun savedShowsTab() {
        val p = ChannelBrowse.parse(JSONObject(res("channel_shows_sample.json")))
        assertTrue(p.shows.isNotEmpty())
        assertTrue(p.shows.all { it.url.contains("playlist?list=") })
        assertNull(ChannelBrowse.sortChips(p))
    }

    @Test fun unknownShapeIsEmptyNotACrash() {
        val p = ChannelBrowse.parse(JSONObject("""{"a":[1,2,{"b":null}]}"""))
        assertTrue(p.streams.isEmpty() && p.shows.isEmpty() && p.next == null)
        assertNull(ChannelBrowse.sortChips(p))
    }
}
