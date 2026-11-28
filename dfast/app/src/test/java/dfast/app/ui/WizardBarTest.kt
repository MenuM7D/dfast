package dfast.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WizardBarTest {
    @Test fun barsArePaintedNotTinted() {
        val f = File("src/main/java").let { if (it.exists()) it else File("app/src/main/java") }
        val s = File(f, "dfast/app/ui/downloadcard/ObserveSourcesBottomSheetDialog.kt").readText()
        assertTrue(s.contains("b.background = android.graphics.drawable.GradientDrawable()"))
        assertTrue(!s.contains("b.backgroundTintList"))
    }
}
