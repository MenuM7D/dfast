package dfast.app.receiver

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the old Home screen popping up behind the download sheet: every in-app launch of the stock
 * share sheet must carry the "inapp" flag, and the sheet must honour it on configuration changes.
 */
class ShareActivityGuardTest {
    private fun src(rel: String): String {
        val f = listOf(File("src/main/java/dfast/app/$rel"), File("app/src/main/java/dfast/app/$rel"), File("dfast/app/src/main/java/dfast/app/$rel")).first { it.exists() }
        return f.readText()
    }

    @Test fun sheetIgnoresRotationWhenOpenedInApp() {
        val s = src("receiver/ShareActivity.kt")
        val i = s.indexOf("override fun onConfigurationChanged")
        assertTrue(i > 0)
        val body = s.substring(i, i + 400)
        assertTrue(body.contains("\"inapp\""))
    }

    @Test fun inAppLaunchesCarryTheFlag() {
        for (f in listOf("ui/play/PlayerActivity.kt", "ui/play/SearchResultsActivity.kt", "ui/play/SiteBrowserActivity.kt", "ui/home/HomeFeed.kt")) {
            for (line in src(f).lines()) {
                if (line.contains("ShareActivity::class.java")) assertTrue("$f launches ShareActivity without the inapp flag: ${line.trim()}", line.contains("\"inapp\""))
            }
        }
    }
}
