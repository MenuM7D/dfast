package dfast.app.util

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Layout rules that keep text from being cut in the side menu and the More page.
 * (Measuring real font widths is done offline: see docs/text_fit_report.md. The unit test
 * classpath has no font engine, so this test checks the layout rules only.)
 */
class TextFitTest {
    private val res = File("src/main/res")

    @Test
    fun morePageRowsCanWrapAndGrow() {
        val text = File(res, "layout/fragment_more.xml").readText()
        assertTrue("More page must not force one line", !text.contains("maxLines=\"1\"") && !text.contains("singleLine=\"true\""))
        assertTrue("More rows must not use a fixed height", !Regex("layout_height=\"\\d+dp\"").containsMatchIn(text))
    }

    @Test
    fun sideMenuAllowsTwoLines() {
        val text = File(res, "layout/activity_main.xml").readText()
        assertTrue("Side menu items must allow at least two lines", Regex("itemMaxLines=\"([2-9])\"").containsMatchIn(text))
    }

    @Test
    fun fontsAreBundled() {
        listOf("plex_regular", "plex_medium", "plex_semibold", "plex_bold").forEach {
            assertTrue("$it.ttf is missing", File(res, "font/$it.ttf").length() > 100_000)
        }
    }
}
