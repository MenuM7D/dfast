package dfast.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChipIconTintTest {
    @Test fun everyChipWithAnIconHasTheSameTint() {
        val d = File("src/main/res").let { if (it.exists()) it else File("app/src/main/res") }
        val bad = mutableListOf<String>()
        d.listFiles { f -> f.name.startsWith("layout") }!!.flatMap { it.listFiles()!!.toList() }.filter { it.extension == "xml" }.forEach { f ->
            Regex("<com\\.google\\.android\\.material\\.chip\\.Chip\\b[^>]*>").findAll(f.readText()).forEach { m ->
                if (m.value.contains("app:chipIcon=") && !m.value.contains("app:chipIconTint=\"@color/dfast_icon_chip\"")) bad.add(f.name)
            }
        }
        assertTrue("chips without the one icon tint: $bad", bad.isEmpty())
    }

    @Test fun noIconDrawableUsesTheAccentColour() {
        val d = File("src/main/res/drawable").let { if (it.exists()) it else File("app/src/main/res/drawable") }
        val bad = d.listFiles()!!.filter { it.extension == "xml" && it.readText().contains("strokeColor=\"?android:colorAccent\"") }.map { it.name }
        assertTrue("icons with their own purple: $bad", bad.isEmpty())
    }
}
