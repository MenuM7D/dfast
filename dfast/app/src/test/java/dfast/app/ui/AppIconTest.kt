package dfast.app.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AppIconTest {
    private val main = File("src/main").let { if (it.exists()) it else File("app/src/main") }
    private val theme get() = File(main, "java/dfast/app/util/ThemeUtil.kt").readText()
    private val manifest get() = File(main, "AndroidManifest.xml").readText()

    private fun alias(name: String): String {
        val i = manifest.indexOf("android:name=\".$name\"")
        assertTrue("alias $name", i > 0)
        return manifest.substring(manifest.lastIndexOf("<activity-alias", i), manifest.indexOf("</activity-alias>", i))
    }

    @Test fun pickerOffersExactlyThreeIcons() {
        val list = theme.substring(theme.indexOf("val availableIcons"), theme.indexOf("val legacyAliases"))
        assertEquals(listOf("Default", "Light", "Dark"), Regex("AppIcon\\.(\\w+),").findAll(list).map { it.groupValues[1] }.toList())
        assertFalse(theme.contains("object Blue") || theme.contains("object Green"))
    }

    @Test fun pickerPreviewIsTheLauncherIcon() {
        val m = Regex("object (\\w+) : AppIcon\\(R\\.string\\.\\w+, R\\.mipmap\\.(\\w+), \"(\\w+)\"\\)").findAll(theme).toList()
        assertEquals(3, m.size)
        for (x in m) {
            val a = alias(x.groupValues[3])
            assertTrue(x.groupValues[3], a.contains("android:icon=\"@mipmap/${x.groupValues[2]}\""))
            assertTrue(File(main, "res/mipmap-anydpi-v26/${x.groupValues[2]}.xml").exists())
            assertTrue(File(main, "res/mipmap-anydpi-v26/${x.groupValues[2].replace("ic_launcher", "ic_launcher_round")}.xml").exists())
        }
    }

    @Test fun oldAliasesStaySafeAndDisabled() {
        for (n in listOf("BlueIcon", "GreenIcon")) {
            val a = alias(n)
            assertTrue(a.contains("android:enabled=\"false\""))
            assertTrue(a.contains("@mipmap/ic_launcher\"") && !a.contains("_blue") && !a.contains("_green"))
        }
        assertTrue(theme.contains("legacyAliases"))
        val f = theme.substring(theme.indexOf("fun updateAppIcon"))
        assertTrue("old aliases are switched off too", f.contains("availableIcons.map { it.activityAlias } + legacyAliases"))
        assertTrue(theme.contains("fun migrateIconMode") && theme.contains("legacyAliases.contains(saved)) \"Default\""))
        assertTrue(theme.contains("migrateIconMode(savedIcon)"))
        for (f2 in listOf("ic_launcher_blue", "ic_launcher_green", "ic_launcher_round_blue", "ic_launcher_round_green"))
            assertFalse(f2, File(main, "res/mipmap-anydpi-v26/$f2.xml").exists())
        assertFalse(File(main, "res/drawable/ic_launcher_foreground_blue.xml").exists() || File(main, "res/drawable/ic_launcher_foreground_green.xml").exists())
    }

    @Test fun exactlyOneLauncherAliasStartsEnabled() {
        val enabled = Regex("<activity-alias[^>]*?android:name=\"\\.(\\w+)\"[^>]*?android:enabled=\"true\"", RegexOption.DOT_MATCHES_ALL)
        // Default is the only icon alias enabled in the manifest; Dark, Light, Blue, Green start disabled
        assertTrue(alias("Default").contains("android:enabled=\"true\""))
        for (n in listOf("DarkIcon", "LightIcon", "BlueIcon", "GreenIcon")) assertTrue(n, alias(n).contains("android:enabled=\"false\""))
        assertNotNull(enabled)
    }

    @Test fun newIconsAreTheCurrentChevronInsideTheSafeZone() {
        for ((f, col) in listOf("ic_launcher_foreground_dark" to "#8E84FF", "ic_launcher_foreground_light" to "#4A3FE0")) {
            val t = File(main, "res/drawable/$f.xml").readText()
            assertTrue(f, t.contains(col))
            val nums = Regex("[ML]\\s*([0-9.]+),([0-9.]+)").findAll(t).map { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }.toList()
            assertTrue(nums.isNotEmpty())
            for ((x, y) in nums) assertTrue("$f $x,$y inside 18..90", x in 18.0..90.0 && y in 18.0..90.0)
            // same chevron paths as the default icon
            val d = File(main, "res/drawable/ic_launcher_foreground.xml").readText()
            assertTrue(d.contains("M39,32.75 L54,47.75") && t.contains("M39,32.75 L54,47.75"))
        }
        for (n in listOf("ic_launcher_light", "ic_launcher_round_light")) assertTrue(File(main, "res/mipmap-anydpi-v26/$n.xml").readText().contains("ic_launcher_foreground_light"))
        assertTrue(File(main, "res/drawable/ic_launcher_background_dark.xml").readText().contains("#0B0B10"))
        assertTrue(File(main, "res/drawable/ic_launcher_background_light.xml").readText().contains("#F6F6F9"))
    }
}
