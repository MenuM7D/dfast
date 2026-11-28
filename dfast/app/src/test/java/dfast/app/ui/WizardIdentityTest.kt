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
        val wizard = r("layout/observe_source_wizard.xml")
        val code = File(java, "dfast/app/ui/downloadcard/ObserveSourcesBottomSheetDialog.kt").readText()
        val ids = Regex("R\\.id\\.(\\w+)").findAll(code).map { it.groupValues[1] }.toSet()
        val missing = ids.filter { !wizard.contains("@+id/$it") }
        assertTrue("wizard lacks ids used by code: $missing", missing.isEmpty())
    }

    @Test fun wizardWrapsTextAndUsesStartEnd() {
        val w = r("layout/observe_source_wizard.xml")
        assertFalse(w.contains("singleLine=\"true\""))
        assertFalse(Regex("maxLines=\"1\"").containsMatchIn(w))
        assertFalse(Regex("layout_margin(Left|Right)|paddingLeft|paddingRight|gravity=\"(left|right)\"").containsMatchIn(w))
        assertTrue(w.contains("@drawable/ic_dfast_wordmark"))
        assertFalse(w.any { it.code in 0x2600..0x27BF || it.isSurrogate() })
    }

    @Test fun classicSheetIsGoneAndWizardIsTheOnlyLayout() {
        val k = File(java, "dfast/app/ui/downloadcard/ObserveSourcesBottomSheetDialog.kt").readText()
        assertTrue(k.contains("R.layout.observe_source_wizard"))
        assertFalse(k.contains("classic_source_sheet"))
        assertFalse(File(res, "layout/observe_sources_bottom_sheet.xml").exists())
        assertFalse(r("xml/processing_preferences.xml").contains("classic_source_sheet"))
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

    @Test fun noTextViewEllipsizesOrCapsLinesInLayoutsOrCode() {
        val bad = ArrayList<String>()
        val tagRe = Regex("<(TextView|com\\.google\\.android\\.material\\.textview\\.MaterialTextView|Button|com\\.google\\.android\\.material\\.button\\.MaterialButton)\\s[^<>]*>", RegexOption.DOT_MATCHES_ALL)
        File(res, ".").listFiles { f -> f.isDirectory && f.name.startsWith("layout") }!!.forEach { d ->
            d.listFiles()!!.forEach { f ->
                tagRe.findAll(f.readText()).forEach { m ->
                    val t = m.value
                    if (!t.contains("autoSizeTextType") && Regex("android:(ellipsize|singleLine|lines)=|android:maxLines=\"\\d\"").containsMatchIn(t)) bad += f.name
                }
            }
        }
        java.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            f.readLines().forEachIndexed { i, l ->
                if (l.contains("TruncateAt") && !l.contains("snack")) bad += "${f.name}:${i + 1}"
            }
        }
        assertTrue("clipped or ellipsized text left in $bad", bad.isEmpty())
    }
}
