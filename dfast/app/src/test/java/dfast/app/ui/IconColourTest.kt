package dfast.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class IconColourTest {
    private val res = File("src/main/res").let { if (it.exists()) it else File("app/src/main/res") }
    @Test fun settingIsInAppearanceWithWhiteDefault() {
        val x = File(res, "xml/general_preferences.xml").readText()
        assertTrue(x.contains("app:key=\"icon_color\"") && x.contains("app:defaultValue=\"white\""))
        val java = File(res, "../java/dfast/app/ui/more/settings/SettingsPages.kt").readText()
        assertTrue(java.contains("\"orbit_palette\", \"icon_color\""))
    }
    @Test fun oneIconColourAttributeDrivesTheDrawables() {
        val bad = File(res, "drawable").listFiles()!!.filter { it.name.startsWith("ti_") && it.readText().contains("strokeColor=\"#FFFFFFFF\"") }.map { it.name }
        assertTrue("ti_ icons with their own white: $bad", bad.isEmpty())
    }
    @Test fun stringsInEveryLocale() {
        val dirs = listOf("", "-ar", "-aeb", "-apc", "-arq", "-ars", "-arz", "-egy", "-de", "-es", "-fa", "-fr", "-hi", "-in", "-it", "-ja", "-ko", "-nl", "-pl", "-pt-rBR", "-pt", "-ru", "-tr-rTR", "-uk", "-ur", "-vi", "-zh-rCN", "-zh-rTW")
        for (d in dirs) {
            val s = File(res, "values$d/strings.xml").readText()
            for (k in listOf("o_icon_color", "o_icon_white", "o_icon_purple")) assertTrue("$k missing in values$d", s.contains("name=\"$k\""))
        }
    }
    @Test fun purpleHasContrastInBothModes() {
        fun lum(h: String): Double { val c = (0..2).map { Integer.parseInt(h.substring(1 + it * 2, 3 + it * 2), 16) / 255.0 }.map { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }; return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2] }
        fun cr(a: String, b: String): Double { val x = lum(a); val y = lum(b); return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05) }
        assertTrue(cr("#4A3FE0", "#F5F6FA") >= 4.5)
        assertTrue(cr("#B3A8FF", "#17115F") >= 4.5 && cr("#B3A8FF", "#000000") >= 4.5)
    }
    /** beta.12 regression: values-night/BaseTheme had no dfastIcon, so in dark mode every icon stroke resolved to nothing. */
    @Test fun everyBaseThemeVariantDefinesTheIconAttribute() {
        val files = res.listFiles()!!.filter { it.name.startsWith("values") }.map { File(it, "themes.xml") }.filter { it.exists() && it.readText().contains("<style name=\"BaseTheme\"") }
        assertTrue("expected default and night BaseTheme", files.size >= 2 && files.any { it.parentFile.name == "values-night" })
        for (f in files) {
            val base = f.readText().substringAfter("<style name=\"BaseTheme\"").substringBefore("</style>")
            assertTrue("dfastIcon missing from BaseTheme in ${f.parentFile.name}", base.contains("name=\"dfastIcon\""))
        }
        assertTrue(File(res, "values/palettes.xml").readText().substringAfter("<style name=\"IconPurple\"").substringBefore("</style>").contains("dfastIcon"))
    }
}
