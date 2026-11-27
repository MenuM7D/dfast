package dfast.app.util

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Behavioral guard for the stray Home card: the row the in-app sheet makes must really go away. */
class InAppSheetTest {
    private class FakeStore(var rows: MutableList<String> = mutableListOf(), var failDelete: Boolean = false) : InAppRowStore {
        override var pendingMarker = false
        override suspend fun deleteAllRows() { if (failDelete) throw IllegalStateException("db down"); rows.clear() }
    }

    @Before fun reset() { InAppSheet.resetForTest() }

    @Test fun rowIsRemovedWhenSheetCloses() = runBlocking {
        val st = FakeStore()
        InAppSheet.begin()
        assertTrue(InAppSheet.active.value)
        st.rows.add("song"); InAppSheet.rowCreated(st)
        InAppSheet.end(st)
        assertTrue(st.rows.isEmpty()); assertFalse(st.pendingMarker); assertFalse(InAppSheet.active.value)
    }

    @Test fun existingHomeSearchRowIsNotTouched() = runBlocking {
        val st = FakeStore(mutableListOf("search result"))
        InAppSheet.begin()          // sheet opened for a link Home already had: no row created, no marker
        InAppSheet.end(st)
        assertEquals(listOf("search result"), st.rows); assertFalse(InAppSheet.active.value)
    }

    @Test fun rowLeftByAKilledProcessIsRemovedOnNextHomeStart() = runBlocking {
        val st = FakeStore()
        InAppSheet.begin(); st.rows.add("song"); InAppSheet.rowCreated(st)
        // process killed here: end() never runs, memory is lost, the marker is stored
        InAppSheet.resetForTest()
        assertTrue(InAppSheet.recover(st))
        assertTrue(st.rows.isEmpty()); assertFalse(st.pendingMarker)
        assertFalse("nothing left to recover", InAppSheet.recover(st))
    }

    @Test fun recoverDoesNothingWhileASheetIsOpen() = runBlocking {
        val st = FakeStore(); InAppSheet.begin(); st.rows.add("song"); InAppSheet.rowCreated(st)
        assertFalse(InAppSheet.recover(st)); assertEquals(1, st.rows.size)
    }

    @Test fun failedDeleteKeepsMarkerSoItRetriesAndHomeIsReleased() = runBlocking {
        val st = FakeStore(failDelete = true); InAppSheet.begin(); st.rows.add("song"); InAppSheet.rowCreated(st)
        try { InAppSheet.end(st) } catch (_: IllegalStateException) {}
        assertFalse("Home must not stay blocked", InAppSheet.active.value)
        assertTrue(st.pendingMarker)
        st.failDelete = false
        assertTrue(InAppSheet.recover(st)); assertTrue(st.rows.isEmpty())
    }

    private fun src(rel: String): String =
        listOf(File("src/main/java/dfast/app/$rel"), File("app/src/main/java/dfast/app/$rel"), File("dfast/app/src/main/java/dfast/app/$rel")).first { it.exists() }.readText()

    @Test fun wiringIsInPlace() {
        val share = src("receiver/ShareActivity.kt")
        val body = share.substring(share.indexOf("override fun onDestroy()"), share.indexOf("override fun onResume()"))
        assertTrue(body.contains("InAppSheet.end("))
        assertFalse("cleanup must not go through the ViewModel scope", body.contains("resultViewModel.deleteAll"))
        assertTrue("cleanup must start before super.onDestroy()", body.indexOf("InAppSheet.end(") < body.indexOf("super.onDestroy()"))
        assertTrue(share.contains("InAppSheet.begin()") && share.contains("InAppSheet.rowCreated("))
        val home = src("ui/home/HomeFragment.kt")
        assertTrue(home.contains("InAppSheet.active") && home.contains("InAppSheet.recover("))
    }

    private fun allMainSources(): List<File> {
        val root = listOf(File("src/main/java/dfast/app"), File("app/src/main/java/dfast/app"), File("dfast/app/src/main/java/dfast/app")).first { it.exists() }
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test fun everyInAppLaunchOfTheShareSheetCarriesTheFlag() {
        for (f in allMainSources()) {
            if (f.name == "ShareActivity.kt") continue
            val lines = f.readLines()
            lines.forEachIndexed { i, line ->
                if (line.contains("ShareActivity::class")) {
                    val window = lines.subList(i, minOf(lines.size, i + 4)).joinToString("\n")
                    assertTrue("${f.name}:${i + 1} launches ShareActivity without the inapp flag", window.contains("\"inapp\""))
                }
            }
        }
    }

    @Test fun dismissCallbacksDoNotRelyOnTheViewModelScopeForDeletes() {
        // the two dismiss paths that delete download rows must also clean up through the process-level helper
        val multi = allMainSources().first { it.name == "DownloadMultipleBottomSheetDialog.kt" }.readText()
        assertTrue(multi.contains("SheetCleanup.deleteProcessingDownloads"))
        val dup = allMainSources().first { it.name == "DownloadsAlreadyExistDialog.kt" }.readText()
        assertTrue(dup.contains("SheetCleanup.deleteDuplicateStatusDownloads"))
        val share = allMainSources().first { it.name == "ShareActivity.kt" }.readText()
        val d = share.substring(share.indexOf("override fun onDestroy()"), share.indexOf("override fun onResume()"))
        assertFalse(d.contains("viewModelScope"))
    }

    @Test fun musicRowsOpenTheSheetOnAudioAndSearchRefsCarryAnExplicitType() {
        val feed = allMainSources().first { it.name == "HomeFeed.kt" }.readText()
        val line = feed.lines().first { it.contains("ShareActivity::class") }
        assertTrue(line.contains("\"TYPE\", \"audio\""))
        assertTrue(line.contains("\"pre_title\"") && line.contains("\"pre_thumb\""))
        val search = allMainSources().first { it.name == "SearchResultsActivity.kt" }.readText()
        assertTrue(search.contains("putExtra(\"TYPE\", if (ref.audio) \"audio\" else \"video\")"))
    }
}
