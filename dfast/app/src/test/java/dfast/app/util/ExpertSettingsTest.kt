package dfast.app.util

import dfast.app.ui.more.settings.ExpertSettings
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Simple/Expert filter must only hide real rows, never the developer switch, and every language needs its texts. */
class ExpertSettingsTest {
    private val res = File("src/main/res")

    private fun allXmlKeys(): Set<String> {
        val keys = HashSet<String>()
        File(res, "xml").listFiles { f -> f.name.endsWith(".xml") }!!.forEach { f ->
            Regex("key=\"([^\"]+)\"").findAll(f.readText()).forEach { keys += it.groupValues[1] }
        }
        return keys
    }

    @Test
    fun everyExpertKeyIsARealRow() {
        val xml = allXmlKeys()
        val unknown = ExpertSettings.keys.filter { it !in xml }
        assertTrue("Expert keys with no row in any settings file: $unknown", unknown.isEmpty())
    }

    @Test
    fun developerSwitchStaysVisible() {
        assertTrue("developer_mode must never be hidden", "developer_mode" !in ExpertSettings.keys)
        assertTrue("developer_category must never be hidden", "developer_category" !in ExpertSettings.keys)
        assertTrue("the expert switch itself must never be hidden", ExpertSettings.KEY !in ExpertSettings.keys)
    }

    @Test
    fun everyLanguageHasTheExpertTexts() {
        val missing = ArrayList<String>()
        res.listFiles { f -> f.isDirectory && f.name.startsWith("values") }!!.forEach { d ->
            val f = File(d, "strings.xml")
            if (f.exists() && f.readText().contains("name=\"o_downloads\"")) {
                val t = f.readText()
                if (!t.contains("name=\"o_expert_mode\"") || !t.contains("name=\"o_expert_mode_summary\"")) missing += d.name
            }
        }
        assertTrue("Languages missing the Expert mode texts: $missing", missing.isEmpty())
    }
}
