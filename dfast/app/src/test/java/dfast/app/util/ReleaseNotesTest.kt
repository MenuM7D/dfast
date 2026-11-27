package dfast.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNotesTest {
    private val body = "Signed beta.\n<!-- whatsnew:en -->\n- English\n<!-- /whatsnew -->\n<!-- whatsnew:ar -->\n- عربي\n<!-- /whatsnew -->\n<!-- whatsnew:pt-BR -->\n- Brasil\n<!-- /whatsnew -->\n<!-- whatsnew:pt -->\n- Portugal\n<!-- /whatsnew -->\nFull Changelog"

    @Test fun exactTagThenLanguageThenEnglish() {
        assertEquals("- Brasil", ReleaseNotes.pick(body, "pt-BR"))
        assertEquals("- Portugal", ReleaseNotes.pick(body, "pt-PT"))
        assertEquals("- English", ReleaseNotes.pick(body, "ja"))
    }
    @Test fun arabicDialectsUseFusha() { assertEquals("- عربي", ReleaseNotes.pick(body, "arz")); assertEquals("- عربي", ReleaseNotes.pick(body, "ar-EG")) }
    @Test fun bodyWithoutBlocksIsShownAsIs() { assertEquals("plain", ReleaseNotes.pick("plain", "en")) }
}
