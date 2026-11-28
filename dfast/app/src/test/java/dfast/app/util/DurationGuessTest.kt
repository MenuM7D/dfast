package dfast.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DurationGuessTest {
    @Test fun lengthFromRealStream() {
        assertEquals(224L, DurationGuess.fromStream(3_584_000L, 128.0))
        assertEquals(0L, DurationGuess.fromStream(0L, 128.0))
        assertEquals(0L, DurationGuess.fromStream(3_584_000L, 0.0))
    }
    @Test fun qualitySheetUsesIt() {
        val f = File("src/main/java").let { if (it.exists()) it else File("app/src/main/java") }
        val s = File(f, "dfast/app/ui/downloadcard/DownloadBottomSheetDialog.kt").readText()
        assertTrue(s.contains("DurationGuess.fromStream"))
        val h = File(f, "dfast/app/ui/home/HomeFragment.kt").readText()
        assertTrue("combine re-emits one PagingData twice", !h.contains("combine(resultViewModel.paginatedItems") && h.contains("InAppSheet.active.flatMapLatest"))
    }
}
