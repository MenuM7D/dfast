package dfast.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityPickTest {
    private fun v(h: Int, codec: String = "avc1.640028", tbr: Double = h.toDouble(), url: String = "v$h-$codec") = Fmt(url, h, codec, "none", null, "mp4", tbr)
    private fun a(url: String, container: String = "m4a", codec: String = "mp4a.40.2", tbr: Double = 128.0) = Fmt(url, 0, "none", codec, null, container, tbr)
    private val ladder = listOf(v(360), v(720), v(720, "vp9", 900.0, "v720-vp9"), v(1080), a("aud-m4a"), a("aud-webm", "webm", "opus", 160.0))

    @Test fun pairIsVideoThenAudioAndPrefersH264AndM4a() {
        assertEquals(listOf("v720-avc1.640028", "aud-m4a"), QualityPick.pairFor(ladder, 720))
    }

    @Test fun firstPlayPicksBestUpTo720() {
        assertEquals(720, QualityPick.defaultLabel(ladder))
        assertEquals(listOf("v720-avc1.640028", "aud-m4a"), QualityPick.defaultPair(ladder))
    }

    @Test fun onlyHighQualitiesGivesTheLowestAbove720() {
        val fs = listOf(v(1080), v(1440), a("aud"))
        assertEquals(1080, QualityPick.defaultLabel(fs))
    }

    @Test fun heightWithoutAudioPartnerIsSkipped() {
        val fs = listOf(v(720), v(360))                       // no audio stream at all
        assertEquals(0, QualityPick.defaultLabel(fs)); assertTrue(QualityPick.defaultPair(fs).isEmpty())
    }

    @Test fun manifestsAndMissingLinksAreIgnored() {
        val fs = listOf(v(720, url = "https://x/a.m3u8"), v(480, url = ""), v(360), a("aud"))
        assertEquals(360, QualityPick.defaultLabel(fs))
        assertTrue(QualityPick.pairFor(fs, 720).isEmpty())
    }

    @Test fun mixedFileWithPictureAndSoundIsOneLink() {
        val fs = listOf(Fmt("muxed18", 360, "avc1", "mp4a", null, "mp4", 500.0))
        assertEquals(listOf("muxed18"), QualityPick.pairFor(fs, 360))
    }

    @Test fun emptyListingGivesNothing() {
        assertEquals(0, QualityPick.defaultLabel(emptyList())); assertTrue(QualityPick.defaultPair(emptyList()).isEmpty())
    }
}
