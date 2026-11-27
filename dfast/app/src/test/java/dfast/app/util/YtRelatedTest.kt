package dfast.app.util

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YtRelatedTest {
    // shape of a real watch-next response (trimmed): three video lockups and one playlist lockup
    private val sample = """{"contents": {"twoColumnWatchNextResults": {"secondaryResults": {"secondaryResults": {"results": [{"lockupViewModel": {"contentId": "RFahjn6vfYY", "contentType": "LOCKUP_CONTENT_TYPE_VIDEO", "contentImage": {"thumbnailViewModel": {"image": {"sources": [{"url": "https://i.ytimg.com/vi/RFahjn6vfYY/hqdefault.jpg?sqp=-oaymwEjCNACELwBSFryq4qpAxUIARUAAAAAGAElAADIQj0AgKJDeAE=&rs=AOn4CLDKusTAs2ZT_bSfR1kzeiDgG6XX0g", "width": 336, "height": 188}]}, "overlays": [{"thumbnailBottomOverlayViewModel": {"badges": [{"thumbnailBadgeViewModel": {"text": "11:13"}}]}}]}}, "metadata": {"lockupMetadataViewModel": {"title": {"content": "Zack D films Vs Original"}, "metadata": {"contentMetadataViewModel": {"metadataRows": [{"metadataParts": [{"text": {"content": "Sthenics Films"}}]}]}}}}}}, {"lockupViewModel": {"contentId": "fNFzfwLM72c", "contentType": "LOCKUP_CONTENT_TYPE_VIDEO", "contentImage": {"thumbnailViewModel": {"image": {"sources": [{"url": "https://i.ytimg.com/vi/fNFzfwLM72c/hqdefault.jpg?sqp=-oaymwEjCNACELwBSFryq4qpAxUIARUAAAAAGAElAADIQj0AgKJDeAE=&rs=AOn4CLCfpn-LxR0HqqFnWKOgbUQpA5SPDA", "width": 336, "height": 188}]}, "overlays": [{"thumbnailBottomOverlayViewModel": {"badges": [{"thumbnailBadgeViewModel": {"text": "4:10"}}]}}]}}, "metadata": {"lockupMetadataViewModel": {"title": {"content": "Bee Gees - Stayin' Alive (Official Music Video)"}, "metadata": {"contentMetadataViewModel": {"metadataRows": [{"metadataParts": [{"text": {"content": "beegees", "styleRuns": [{"startIndex": 7, "styleRunExtensions": {"styleRunColorMapExtension": {"colorMap": [{"key": "USER_INTERFACE_THEME_DARK", "value": 4289374890}, {"key": "USER_INTERFACE_THEME_LIGHT", "value": 4284506208}]}}}], "attachmentRuns": [{"startIndex": 7, "length": 0, "element": {"type": {"imageType": {"image": {"sources": [{"clientResource": {"imageName": "AUDIO_BADGE"}, "width": 14, "height": 14}]}}}, "properties": {"layoutProperties": {"height": {"value": 14, "unit": "DIMENSION_UNIT_POINT"}, "width": {"value": 14, "unit": "DIMENSION_UNIT_POINT"}, "margin": {"left": {"value": 4, "unit": "DIMENSION_UNIT_POINT"}}}}}, "alignment": "ALIGNMENT_VERTICAL_CENTER"}]}}]}]}}}}}}, {"lockupViewModel": {"contentId": "djV11Xbc914", "contentType": "LOCKUP_CONTENT_TYPE_VIDEO", "contentImage": {"thumbnailViewModel": {"image": {"sources": [{"url": "https://i.ytimg.com/vi/djV11Xbc914/hqdefault.jpg?sqp=-oaymwEjCNACELwBSFryq4qpAxUIARUAAAAAGAElAADIQj0AgKJDeAE=&rs=AOn4CLDjCgpUC_e_8rg9tIrX2mYOtlPqzg", "width": 336, "height": 188}]}, "overlays": [{"thumbnailBottomOverlayViewModel": {"badges": [{"thumbnailBadgeViewModel": {"text": "4:04"}}]}}]}}, "metadata": {"lockupMetadataViewModel": {"title": {"content": "a-ha - Take On Me (Official Video) [4K]"}, "metadata": {"contentMetadataViewModel": {"metadataRows": [{"metadataParts": [{"text": {"content": "a-ha", "styleRuns": [{"startIndex": 4, "styleRunExtensions": {"styleRunColorMapExtension": {"colorMap": [{"key": "USER_INTERFACE_THEME_LIGHT", "value": 4284506208}, {"key": "USER_INTERFACE_THEME_DARK", "value": 4289374890}]}}}], "attachmentRuns": [{"startIndex": 4, "length": 0, "element": {"type": {"imageType": {"image": {"sources": [{"clientResource": {"imageName": "AUDIO_BADGE"}, "width": 14, "height": 14}]}}}, "properties": {"layoutProperties": {"height": {"value": 14, "unit": "DIMENSION_UNIT_POINT"}, "width": {"value": 14, "unit": "DIMENSION_UNIT_POINT"}, "margin": {"left": {"value": 4, "unit": "DIMENSION_UNIT_POINT"}}}}}, "alignment": "ALIGNMENT_VERTICAL_CENTER"}]}}]}]}}}}}}, {"lockupViewModel": {"contentId": "PLxxxxxxxxxxx", "contentType": "LOCKUP_CONTENT_TYPE_PLAYLIST"}}]}}}}}"""

    @Test fun readsVideoLockupsOnly() {
        val r = YtRelated.parse(JSONObject(sample))
        assertEquals(3, r.size)
        assertEquals("https://www.youtube.com/watch?v=RFahjn6vfYY", r[0].url)
        assertEquals("Zack D films Vs Original", r[0].title)
        assertEquals("Sthenics Films", r[0].author)
        assertEquals(673L, r[0].durationSec)
        assertEquals(250L, r[1].durationSec)
        assertTrue(r[0].thumb.startsWith("https://i.ytimg.com/"))
    }

    @Test fun skipsTheVideoItself() {
        val r = YtRelated.parse(JSONObject(sample), "RFahjn6vfYY")
        assertEquals(2, r.size)
        assertTrue(r.none { it.url.endsWith("RFahjn6vfYY") })
    }

    @Test fun clockParsing() {
        assertEquals(250L, YtRelated.clock("4:10"))
        assertEquals(3723L, YtRelated.clock("1:02:03"))
        assertEquals(0L, YtRelated.clock("LIVE"))
        assertEquals(0L, YtRelated.clock(""))
    }
}
