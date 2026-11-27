package dfast.app.util

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings, More and the drawer use only our own icon set, no emoji, no hard-coded English, no repeated rows. */
class SettingsCleanTest {
    private val res = File("src/main/res")
    private val settingsXml = File(res, "xml").listFiles { f -> f.name.endsWith("preferences.xml") || f.name.endsWith("preference.xml") }!!.toList()

    private fun strip(t: String) = t.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

    @Test
    fun everySettingsIconIsTabler() {
        val bad = ArrayList<String>()
        (settingsXml + File(res, "layout/fragment_more.xml") + File(res, "menu/drawer_menu.xml")).forEach { f ->
            Regex("(?:icon|drawableStartCompat|drawableEndCompat)=\"@drawable/([a-z_0-9]+)\"").findAll(strip(f.readText())).forEach {
                val n = it.groupValues[1]
                if (!n.startsWith("ti_") && n != "bg_dfast_tile") bad += "${f.name}:$n"
            }
        }
        assertTrue("Icons that are not from the Tabler set: $bad", bad.isEmpty())
    }

    @Test
    fun noEmojiAndNoHardCodedTitles() {
        val bad = ArrayList<String>()
        settingsXml.forEach { f ->
            val t = strip(f.readText())
            if (t.any { it.code in 0x1F000..0x1FAFF || it.code in 0x2600..0x27BF }) bad += "${f.name}: emoji"
            Regex("(?:title|summary)=\"([^@\"][^\"]*)\"").findAll(t).forEach { bad += "${f.name}: ${it.groupValues[1]}" }
        }
        val allowedLiteral = setOf("GNU General Public License v3.0", "YouTube", "aria2c", "SponsorBlock")
        val left = bad.filter { b -> allowedLiteral.none { b.endsWith(it) } && !Regex(".*: \\d\\d:\\d\\d").matches(b) && !b.endsWith(": Ember") }
        assertTrue("Hard-coded or emoji text in settings: $left", left.isEmpty())
    }

    @Test
    fun telegramAndSourceCodeLiveOnlyOnMore() {
        val root = File(res, "xml/root_preferences.xml").readText()
        assertTrue(!root.contains("key=\"telegram\"") && !root.contains("key=\"rreth\""))
        val more = File(res, "layout/fragment_more.xml").readText()
        assertTrue(more.contains("telegram_row") && more.contains("source_row"))
        assertTrue("download queue is already in the drawer", !more.contains("download_queue"))
    }

    @Test
    fun everyIconInTheAppIsTabler() {
        val exempt = listOf("ic_brand_", "ic_launcher", "ic_dfast", "ic_wordmark", "sc_", "terminal_icon", "bg_", "ic_no_results", "ic_stat_", "ic_heart_filled", "ic_pd_heart_fill", "empty")
        val bad = ArrayList<String>()
        File(res, "drawable").listFiles { f -> f.name.endsWith(".xml") }!!.forEach { f ->
            val t = f.readText()
            val n = f.name.removeSuffix(".xml")
            if (t.contains("<vector") && exempt.none { n.startsWith(it) } && !t.contains("Tabler Icons")) bad += n
        }
        assertTrue("Vector icons that are not from the Tabler set: $bad", bad.isEmpty())
    }

    @Test
    fun sectionHeadersUseDFastStyle() {
        val st = File(res, "values/styles.xml").readText()
        assertTrue(st.contains("preferenceCategoryStyle") && st.contains("dfast_preference_category"))
        val l = File(res, "layout/dfast_preference_category.xml").readText()
        assertTrue(l.contains("@android:id/title") && !l.contains("maxLines") && !l.contains("singleLine"))
    }
}
