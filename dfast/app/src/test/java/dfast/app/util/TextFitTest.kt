package dfast.app.util

import java.awt.Font
import java.awt.font.FontRenderContext
import java.awt.font.TextLayout
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test

/**
 * Checks that the labels of the side menu and the More page still fit with the bundled font,
 * in every language, at the biggest common system text size (1.3x).
 *
 * Widths are measured with the real font file. 1 px here stands for 1 dp. These are estimates of
 * the real layout, not pixel proof. Rules checked:
 *  - the longest single word must fit on one line (a word cannot wrap, so it would be cut);
 *  - the whole label must fit in two lines (the side menu allows two lines, the More rows wrap freely).
 */
class TextFitTest {
    private val res = File("src/main/res")
    private val names = listOf(
        "home", "play_title", "o_downloads", "nav_library", "all", "o_saved_offline", "play_favorites",
        "play_playlists", "play_recent", "more", "settings", "observe_sources", "downloads",
        "download_queue", "terminal", "command_templates", "logs", "cookies", "source_code"
    )

    // Side menu: 300dp wide, 24dp padding both sides, 24dp icon, about 16dp gap.
    private val drawerTextWidth = 300f - 48f - 24f - 16f
    private val textSizeDp = 14f * 1.3f

    private fun load(): Font = Font.createFont(Font.TRUETYPE_FONT, File(res, "font/plex_medium.ttf")).deriveFont(textSizeDp)

    private fun strings(file: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val out = HashMap<String, String>()
        val nodes = doc.getElementsByTagName("string")
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            out[n.attributes.getNamedItem("name").nodeValue] = n.textContent
        }
        return out
    }

    private fun width(font: Font, text: String): Float {
        if (text.isEmpty()) return 0f
        return TextLayout(text, font, FontRenderContext(null, true, true)).advance
    }

    @Test
    fun labelsFitTheSideMenu() {
        val font = try {
            System.setProperty("java.awt.headless", "true")
            load().also { width(it, "test") }
        } catch (t: Throwable) {
            assumeNoException("Font measuring is not available on this machine", t)
            return
        }
        val problems = ArrayList<String>()
        var measured = 0
        res.listFiles { f -> f.isDirectory && f.name.startsWith("values") }!!.sortedBy { it.name }.forEach { dir ->
            val file = File(dir, "strings.xml")
            if (!file.exists()) return@forEach
            val map = try { strings(file) } catch (e: Exception) { return@forEach }
            names.forEach { key ->
                val text = map[key]?.replace("\\'", "'")?.trim() ?: return@forEach
                measured++
                val longestWord = text.split(Regex("\\s+")).maxOf { width(font, it) }
                val whole = width(font, text)
                if (longestWord > drawerTextWidth) problems += "${dir.name}/$key: one word is ${longestWord.toInt()}dp wide, room is ${drawerTextWidth.toInt()}dp: $text"
                else if (whole > drawerTextWidth * 2f) problems += "${dir.name}/$key: needs more than two lines (${whole.toInt()}dp): $text"
            }
        }
        println("TextFitTest measured $measured labels, ${problems.size} problems")
        assertTrue("Labels that would be cut:\n" + problems.joinToString("\n"), problems.isEmpty())
    }
}
