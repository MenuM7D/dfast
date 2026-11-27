package dfast.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Structural guards for the guided new-source flow and the DFast identity foundation. */
class WizardIdentityTest {
    private val res = File("src/main/res").let { if (it.exists()) it else File("app/src/main/res") }
    private val java = File(res, "../java")
    private fun r(p: String) = File(res, p).readText()

    @Test fun wizardKeepsEveryIdTheSheetCodeUses() {
        val classic = r("layout/observe_sources_bottom_sheet.xml")
        val wizard = r("layout/observe_source_wizard.xml")
        val ids = Regex("@\\+id/(\\w+)").findAll(classic).map { it.groupValues[1] }.toSet() - setOf("bottom_sheet_title", "bottom_sheet_subtitle")
        val missing = ids.filter { !wizard.contains("@+id/$it") }
        assertTrue("wizard lost ids: $missing", missing.isEmpty())
    }

    @Test fun wizardWrapsTextAndUsesStartEnd() {
        val w = r("layout/observe_source_wizard.xml")
        assertFalse(w.contains("singleLine=\"true\""))
        assertFalse(Regex("maxLines=\"1\"").containsMatchIn(w))
        assertFalse(Regex("layout_margin(Left|Right)|paddingLeft|paddingRight|gravity=\"(left|right)\"").containsMatchIn(w))
        assertTrue(w.contains("@drawable/ic_dfast_wordmark"))
        assertFalse(Regex("[\\u1F000-\\u1FAFF]").containsMatchIn(w))
    }

    @Test fun wizardIsDefaultAndClassicIsBehindDeveloperSwitch() {
        val k = File(java, "dfast/app/ui/downloadcard/ObserveSourcesBottomSheetDialog.kt").readText()
        assertTrue(k.contains("getBoolean(\"classic_source_sheet\", false)"))
        assertTrue(k.contains("R.layout.observe_sources_bottom_sheet"))
        assertTrue(File(res, "layout/observe_sources_bottom_sheet.xml").exists())
        val base = File(java, "dfast/app/ui/more/settings/BaseSettingsFragment.kt").readText()
        assertTrue(base.contains("\"classic_source_sheet\""))
        assertTrue(r("xml/processing_preferences.xml").contains("classic_source_sheet"))
    }

    @Test fun classicSwitchTextsExistInAllLanguages() {
        val dirs = res.listFiles { f -> f.isDirectory && f.name.startsWith("values") && File(f, "strings.xml").exists() && File(f, "strings.xml").readText().contains("name=\"o_expert_mode\"") }!!
        val missing = dirs.filter { d -> File(d, "strings.xml").readText().let { !it.contains("name=\"classic_source_sheet\"") || !it.contains("name=\"classic_source_sheet_summary\"") } }.map { it.name }
        assertTrue("missing in $missing", missing.isEmpty())
    }

    @Test fun noTealOrStockAccentsRemainInTheIdentity() {
        val colors = r("values/colors.xml") + r("values-night/colors.xml")
        for (c in listOf("#00796F", "#9FFCF0", "#12958B", "#C7F5EC", "#00C2B8", "#12B5A6")) assertFalse("teal $c", colors.contains(c, ignoreCase = true))
        val arrays = r("values/arrays.xml")
        val acc = arrays.substring(arrays.indexOf("<array name=\"accents_values\">"), arrays.indexOf("</array>", arrays.indexOf("<array name=\"accents_values\">")))
        for (k in listOf("blue", "red", "green", "purple", "yellow", "orange", "monochrome")) assertFalse("accent $k still listed", acc.contains(">$k<"))
        val theme = File(java, "dfast/app/util/ThemeUtil.kt").readText()
        assertTrue("retired accents must fall back to the DFast theme", theme.contains("else -> activity.setTheme(R.style.Theme_DFast)"))
    }

    @Test fun textFieldsUseTheDFastStyle() {
        val bad = ArrayList<String>()
        File(res, ".").listFiles { f -> f.isDirectory && f.name.startsWith("layout") }!!.forEach { d ->
            d.listFiles()!!.forEach { f -> if (f.readText().contains("TextInputLayout.FilledBox")) bad += f.name }
        }
        assertTrue("stock filled boxes left in $bad", bad.isEmpty())
        assertTrue(r("values/dfast_styles.xml").contains("Widget.DFast.TextInputLayout"))
    }
}
