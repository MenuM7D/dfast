package dfast.app.util

import dfast.app.ui.more.settings.ExpertSettings
import dfast.app.ui.more.settings.SettingsPages
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every row of the five old settings files must live on exactly one new page, so nothing is lost. */
class SettingsPagesTest {
    private val res = File("src/main/res")
    private val oldFiles = listOf("general_preferences", "folders_preference", "downloading_preferences", "processing_preferences", "advanced_preferences")

    private fun keysOf(name: String): List<String> {
        val text = File(res, "xml/$name.xml").readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        return Regex("key=\"([^\"]+)\"").findAll(text).map { it.groupValues[1] }.toList()
    }

    @Test
    fun everyOldKeyResolvesOnExactlyOnePage() {
        val placed = SettingsPages.pages.flatMap { it.keys }
        val dupes = placed.groupBy { it }.filter { it.value.size > 1 }.keys
        assertTrue("Keys placed twice: $dupes", dupes.isEmpty())
        val old = oldFiles.flatMap { keysOf(it) }.filter { it != "reset_preferences" }.toSet()
        val missing = old.filter { it !in placed }
        assertTrue("Old rows with no new page: $missing", missing.isEmpty())
        val unknown = placed.filter { it !in old }
        assertTrue("Page rows that do not exist in any old file: $unknown", unknown.isEmpty())
    }

    @Test
    fun searchFindsMovedRows() {
        oldFiles.flatMap { keysOf(it) }.filter { it != "reset_preferences" }.forEach {
            assertTrue("search cannot reach $it", SettingsPages.pageForKey(it) != null)
            assertTrue("no group for $it", SettingsPages.groupForKey(it) != null)
        }
    }

    @Test
    fun expertRowsStayOnTheirPagesAndDeveloperSwitchIsNotMoved() {
        assertTrue(SettingsPages.pageForKey("developer_mode") == null)
        assertEquals(8, SettingsPages.pages.size)
        assertTrue(ExpertSettings.keys.filter { SettingsPages.pageForKey(it) == null }.all { File(res, "xml").listFiles()!!.any { f -> f.readText().contains("\"$it\"") } })
    }

    @Test
    fun everyLanguageHasThePageTexts() {
        val need = listOf("o_pg_files", "o_pg_home", "o_pg_formats", "o_pg_engine", "o_pg_data", "o_pg_playback", "o_g_savedata", "o_g_bgplay", "o_g_tap", "o_g_when", "o_g_what", "o_g_fast", "o_g_with", "o_g_saveto", "o_g_names", "o_g_storage", "o_g_look", "o_g_cards", "o_g_player", "o_g_start", "o_g_gest", "o_g_cmds", "o_g_fmtsel", "o_g_behavior", "o_g_conn", "o_g_speed")
        val missing = ArrayList<String>()
        res.listFiles { f -> f.isDirectory && f.name.startsWith("values") }!!.forEach { d ->
            val f = File(d, "strings.xml")
            if (f.exists() && f.readText().contains("name=\"o_expert_mode\"")) {
                val t = f.readText()
                need.filter { !t.contains("name=\"$it\"") }.forEach { missing += "${d.name}:$it" }
            }
        }
        assertTrue("Missing page texts: $missing", missing.isEmpty())
    }
}
