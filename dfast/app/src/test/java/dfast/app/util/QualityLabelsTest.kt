package dfast.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class QualityLabelsTest {
    @Test fun oddHeightsMapToStandard() {
        assertEquals(720, QualityLabels.standard(712))
        assertEquals(1080, QualityLabels.standard(1068))
    }
    @Test fun ascendingDedupes() {
        assertEquals(listOf(360, 720, 1080), QualityLabels.ascending(listOf(1080, 720, 360, 720)))
    }
}
