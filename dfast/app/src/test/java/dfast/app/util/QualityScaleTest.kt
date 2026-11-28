package dfast.app.util

import dfast.app.util.QualityScale.Level
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class QualityScaleTest {
    private fun v(vararg h: Int) = h.map { Level("${it}p", it) }
    @Test fun realLevelsHighestFirst() {
        val r = QualityScale.build(v(360, 1080, 720, 720), 720)!!
        assertEquals(listOf("1080p", "720p", "360p"), r.labels); assertEquals(1, r.selected)
    }
    @Test fun genericChoiceSelectsBest() = assertEquals(0, QualityScale.build(v(360, 720), null)!!.selected)
    @Test fun singleLevelHidesScale() { assertNull(QualityScale.build(v(720, 720), 720)); assertNull(QualityScale.build(emptyList(), null)) }
    @Test fun neverMoreThanSevenAndKeepsEnds() {
        val r = QualityScale.build(v(144, 240, 360, 480, 540, 720, 900, 1080, 1440, 2160), 540)!!
        assertTrue(r.labels.size <= QualityScale.MAX); assertEquals("2160p", r.labels.first()); assertEquals("144p", r.labels.last()); assertEquals("540p", r.labels[r.selected])
    }
    @Test fun cardIsWiredIn() {
        val res = File("src/main/res").let { if (it.exists()) it else File("app/src/main/res") }
        for (f in listOf("fragment_download_audio", "fragment_download_video")) {
            val t = File(res, "layout/$f.xml").readText(); assertTrue(t.contains("@layout/format_card_scale") && !t.contains("@layout/format_item"))
        }
        val c = File(res, "layout/format_card_scale.xml").readText()
        for (id in listOf("format_card_constraintLayout", "container", "format_note", "format_id", "audio_formats", "codec", "file_size", "bitrate", "format_loading_progress", "quality_scale")) assertTrue(id, c.contains("@+id/$id\""))
        assertTrue(!c.contains("ellipsize") && !c.contains("maxLines"))
    }
    @Test fun realLevelsAreLoadedInTheBackgroundForNewSources() {
        val j = File("src/main/java").let { if (it.exists()) it else File("app/src/main/java") }
        val dir = File(j, "dfast/app/ui/downloadcard")
        for (f in listOf("DownloadAudioFragment", "DownloadVideoFragment")) {
            val t = File(dir, "$f.kt").readText()
            assertTrue(f, t.contains("QualityScaleBinder.warm(") && t.contains("QualityScaleBinder.levelsSource("))
        }
        val b = File(dir, "QualityScaleBinder.kt").readText()
        assertTrue(b.contains("levelCount(res) < 2") && b.contains("fun levelsSource") && b.contains("distinct") .not() || b.contains("toSet().size"))
        // only the scale uses the fetched list: the chosen format and the saved item are not written by it
        val w = b.substring(b.indexOf("fun warm("))
        assertTrue(!w.contains("downloadItem") && !w.contains(".format ="))
    }
    @Test fun cardSitsInlineWithTheFields() {
        val res = File("src/main/res").let { if (it.exists()) it else File("app/src/main/res") }
        assertTrue(File(res, "layout/format_card_scale.xml").readText().contains("android:layout_marginHorizontal=\"10dp\""))
    }
}
