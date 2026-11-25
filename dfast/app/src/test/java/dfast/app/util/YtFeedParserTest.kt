package dfast.app.util

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression checks for the YouTube account feed parser (docs/FEED_SAFEGUARDS.md).
 * The samples are minimal copies of the response shapes the parser must keep reading.
 * When YouTube changes a shape, add the new sample here BEFORE changing the parser.
 */
class YtFeedParserTest {
    private fun parse(s: String) = YtAccountApi.parseVideoItems(JSONObject(s))

    @Test fun classicVideoRenderer() {
        val r = parse("""{"contents":[{"richItemRenderer":{"content":{"videoRenderer":{"videoId":"aaaaaaaaaaa","title":{"runs":[{"text":"Hello"}]},"ownerText":{"runs":[{"text":"Chan"}]},"lengthText":{"simpleText":"3:21"},"thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/a.jpg"}]}}}}}]}""")
        assertEquals(1, r.size); assertEquals("Hello", r[0].title); assertEquals("Chan", r[0].author)
        assertTrue(r[0].url.endsWith("watch?v=aaaaaaaaaaa"))
    }

    @Test fun gridAndPlaylistVideo() {
        val r = parse("""{"a":[{"gridVideoRenderer":{"videoId":"bbbbbbbbbbb","title":{"simpleText":"Grid"}}},{"playlistVideoRenderer":{"videoId":"ccccccccccc","title":{"simpleText":"Pl"}}}]}""")
        assertEquals(listOf("Grid", "Pl"), r.map { it.title })
    }

    @Test fun lockupViewModelVideo() {
        val r = parse("""{"x":{"lockupViewModel":{"contentType":"LOCKUP_CONTENT_TYPE_VIDEO","contentId":"ddddddddddd","metadata":{"lockupMetadataViewModel":{"title":{"content":"New style"},"metadata":{"contentMetadataViewModel":{"metadataRows":[{"metadataParts":[{"text":{"content":"Author"}}]}]}}}}}}}""")
        assertEquals(1, r.size); assertEquals("New style", r[0].title); assertEquals("Author", r[0].author)
        assertTrue(r[0].thumb.contains("ddddddddddd"))
    }

    @Test fun lockupNonVideoIsIgnored() {
        val r = parse("""{"x":{"lockupViewModel":{"contentType":"LOCKUP_CONTENT_TYPE_PLAYLIST","contentId":"PLxxxxxxxx","metadata":{"lockupMetadataViewModel":{"title":{"content":"A list"}}}}}}""")
        assertTrue(r.isEmpty())
    }

    @Test fun shortsLockup() {
        val r = parse("""{"x":{"shortsLockupViewModel":{"onTap":{"innertubeCommand":{"reelWatchEndpoint":{"videoId":"eeeeeeeeeee"}}},"overlayMetadata":{"primaryText":{"content":"A short"}}}}}""")
        assertEquals(1, r.size); assertEquals("A short", r[0].title)
    }

    @Test fun musicRows() {
        val r = parse("""{"x":{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"fffffffffff"},"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song"}]}}},{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Artist"}]}}}]}}}""")
        assertEquals(1, r.size); assertEquals("Song", r[0].title)
    }

    @Test fun duplicatesAndBlankTitlesAreDropped() {
        val r = parse("""{"a":[{"videoRenderer":{"videoId":"ggggggggggg","title":{"simpleText":"Once"}}},{"videoRenderer":{"videoId":"ggggggggggg","title":{"simpleText":"Once"}}},{"videoRenderer":{"videoId":"hhhhhhhhhhh","title":{"simpleText":""}}}]}""")
        assertEquals(1, r.size)
    }

    @Test fun continuationToken() {
        val t = YtAccountApi.webCont(JSONObject("""{"a":[{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"TOK123"}}}}]}"""))
        assertEquals("TOK123", t)
        assertEquals("", YtAccountApi.webCont(JSONObject("""{"a":1}""")))
    }
}
