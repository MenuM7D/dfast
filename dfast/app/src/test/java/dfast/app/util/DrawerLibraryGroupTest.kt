package dfast.app.util

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NavigationView suspends its list rebuild while a menu item click is handled, so the Library group
 * must change its rows outside the click call. Guards the fix for "arrow flips but group stays shut".
 */
class DrawerLibraryGroupTest {
    private val src = File("src/main/java/dfast/app/MainActivity.kt").readText()

    private fun applyBody(): String {
        val i = src.indexOf("fun applyLibGroup")
        assertTrue("applyLibGroup missing", i > 0)
        return src.substring(i, src.indexOf("applyLibGroup(libGroupOpen)", i))
    }

    @Test
    fun rowsAreChangedAfterTheClickFinishes() {
        val body = applyBody()
        assertTrue("menu change must be posted", body.contains("post {") && body.contains("LIB_GROUP_POST"))
        val beforePost = body.substring(0, body.indexOf("post {"))
        assertTrue("no menu change before the post", !beforePost.contains("isVisible") && !beforePost.contains("setIcon"))
    }

    @Test
    fun everyLibraryRowIsInTheMenuAndHiddenByDefault() {
        val menu = File("src/main/res/menu/drawer_menu.xml").readText()
        listOf("libraryHubActivity", "offlineActivity", "favoritesActivity", "playlistsActivity", "recentsActivity").forEach {
            assertTrue("$it must start hidden", Regex("@\\+id/$it\"[^>]*visible=\"false\"").containsMatchIn(menu))
            assertTrue("$it must be toggled", src.contains("R.id.$it"))
        }
    }
}
