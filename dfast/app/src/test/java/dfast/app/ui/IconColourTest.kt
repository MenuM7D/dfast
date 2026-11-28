package dfast.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class IconColourTest {
    private val res = File("src/main/res").let { if (it.exists()) it else File("app/src/main/res") }
    @Test fun oneIconColourAttributeDrivesTheDrawables() {
        val bad = File(res, "drawable").listFiles()!!.filter { it.name.startsWith("ti_") && it.readText().contains("strokeColor=\"#FFFFFFFF\"") }.map { it.name }
        assertTrue("ti_ icons with their own white: $bad", bad.isEmpty())
    }
    /** beta.12 regression: values-night/BaseTheme had no dfastIcon, so in dark mode every icon stroke resolved to nothing. */
    @Test fun everyBaseThemeVariantDefinesTheIconAttribute() {
        val files = res.listFiles()!!.filter { it.name.startsWith("values") }.map { File(it, "themes.xml") }.filter { it.exists() && it.readText().contains("<style name=\"BaseTheme\"") }
        assertTrue("expected default and night BaseTheme", files.size >= 2 && files.any { it.parentFile.name == "values-night" })
        for (f in files) {
            val base = f.readText().substringAfter("<style name=\"BaseTheme\"").substringBefore("</style>")
            assertTrue("dfastIcon missing from BaseTheme in ${f.parentFile.name}", base.contains("name=\"dfastIcon\""))
        }
    }

    /** One colour for every icon: white or purple. No icon drawable, layout tint or code tint may carry its own colour. */
    @Test fun noHardcodedIconColours() {
        // semantic or non-theme assets: error red, brand logos, launcher/notification/wordmark art, shortcut badges, icons on filled buttons
        val keep = Regex("^(ic_brand_.*|ic_launcher.*|terminal_icon.*|ic_dfast_.*|ic_wordmark.*|ic_stat_.*|sc_.*|.*_red|baseline_error_24|baseline_token_24|ic_o_delete|ic_o_more_h|ic_frame|ic_pd_heart_fill)\\.xml$")
        val bad = ArrayList<String>()
        for (d in res.listFiles()!!.filter { it.name.startsWith("drawable") }) for (f in d.listFiles()!!.filter { it.name.endsWith(".xml") && !keep.matches(it.name) }) {
            val t = f.readText()
            if (!t.contains("<vector")) continue
            Regex("(strokeColor|fillColor)=\"([^\"]*)\"").findAll(t).forEach { m ->
                val v = m.groupValues[2]
                if (v != "?attr/dfastIcon" && v != "#00000000" && v != "@android:color/transparent") bad.add("${d.name}/${f.name}: $v")
            }
        }
        assertTrue("vector icons with their own colour: ${bad.take(40)}", bad.isEmpty())
        val tintRe = Regex("(app:tint|android:tint|app:iconTint|app:drawableTint|app:itemIconTint|app:chipIconTint|name=\"(?:itemIconTint|chipIconTint|drawableTint|iconTint)\")[=>]\"?([^\"<]*)")
        val ok = Regex("\\?attr/dfastIcon|@color/dfast_icon_(nav|bottom_nav|chip|on_scrim)|\\?attr/(colorError|colorOnError|colorOnPrimary|colorOnPrimaryContainer|orbitPillText)|@color/icon_fg|\\?colorControlActivated")
        val badT = ArrayList<String>()
        for (d in res.listFiles()!!.filter { it.name.startsWith("layout") || it.name.startsWith("values") || it.name.startsWith("menu") }) for (f in d.listFiles()!!.filter { it.name.endsWith(".xml") }) {
            tintRe.findAll(f.readText()).forEach { m -> if (!ok.containsMatchIn(m.groupValues[2])) badT.add("${d.name}/${f.name}: ${m.value}") }
        }
        assertTrue("icon tints with their own colour: ${badT.take(40)}", badT.isEmpty())
        val code = File(res, "../java").walkTopDown().filter { it.extension == "kt" && it.name != "SitesActivity.kt" }
            .filter { Regex("setColorFilter\\((android\\.graphics\\.)?Color\\.WHITE\\)|setColorFilter\\(0xFFFFFFFF|setColorFilter\\(OrbitColors\\.ink").containsMatchIn(it.readText()) }.map { it.name }.toList()
        assertTrue("code-tinted icons with their own colour: $code", code.isEmpty())
    }

    /** The icon-colour setting is gone: no preference, overlay or strings may remain, and selected states keep their on-colour. */
    @Test fun settingIsGoneAndSelectedStatesStayLegible() {
        assertTrue(!File(res, "xml/general_preferences.xml").readText().contains("icon_color"))
        assertTrue(!File(res, "values/palettes.xml").readText().contains("IconPurple"))
        val nav = File(res, "color/dfast_icon_nav.xml").readText()
        assertTrue(nav.contains("state_checked=\"true\"") && nav.contains("?attr/orbitInk") && nav.contains("?attr/dfastIcon"))
        assertTrue(File(res, "color/dfast_icon_bottom_nav.xml").readText().contains("?attr/colorOnPrimaryContainer"))
        assertTrue(File(res, "color/dfast_icon_chip.xml").readText().contains("?attr/colorOnSecondaryContainer"))
        assertTrue(File(res, "layout/activity_main.xml").readText().contains("app:itemIconTint=\"@color/dfast_icon_nav\""))
    }
}
