package dfast.app.util

import dfast.app.database.models.GithubRelease
import org.junit.Assert.*
import org.junit.Test
import java.util.Date

class AppUpdatePickTest {
    private fun r(tag: String, pre: Boolean = false, draft: Boolean = false) = GithubRelease("u", tag, "", Date(), emptyList(), pre, draft)
    // GitHub lists newest first: a later beta of the next version comes before the official release
    private val list = listOf(r("v1.0.2-beta.1", true), r("v1.0.1"), r("v1.0.1-beta.16", true), r("v1.0.0"))

    // the old stored "update_beta" value is not read anymore: there is no beta parameter, so it cannot matter
    @Test fun officialOnOfficialIsNotOfferedTheNextBeta() { assertNull(AppUpdatePick.pick(list, "1.0.1", emptyList())) }
    @Test fun olderOfficialIsOfferedOnlyOfficial() { assertEquals("v1.0.1", AppUpdatePick.pick(list, "1.0.0", emptyList())?.tag_name) }
    @Test fun prereleaseFlagAloneKeepsItOut() { assertNull(AppUpdatePick.pick(listOf(r("v1.0.3", pre = true), r("v1.0.1")), "1.0.1", emptyList())) }
    @Test fun draftsAreNeverOffered() { assertNull(AppUpdatePick.pick(listOf(r("v1.0.9", draft = true), r("v1.0.1")), "1.0.1", emptyList())) }
    @Test fun aBetaInstallIsOfferedTheOfficialOfItsLine() {
        assertEquals("v1.0.1", AppUpdatePick.pick(listOf(r("v1.0.1"), r("v1.0.1-beta.16", true)), "1.0.1.16-beta", emptyList())?.tag_name)
    }
    @Test fun aNewerBetaInstallIsNotOfferedAnOlderOfficial() { assertNull(AppUpdatePick.pick(list, "1.0.2.1-beta", emptyList())) }
    @Test fun skippedVersionIsNotOffered() { assertNull(AppUpdatePick.pick(list, "1.0.0", listOf("v1.0.1"))) }
    @Test fun onlyBetasMeansNothing() { assertNull(AppUpdatePick.pick(listOf(r("v1.0.2-beta.1", true)), "1.0.1", emptyList())) }
    @Test fun emptyList() { assertNull(AppUpdatePick.pick(emptyList(), "1.0.1", emptyList())) }
    @Test fun officialVersionOrdering() {
        assertTrue(AppUpdatePick.versionNumber("1.0.2") > AppUpdatePick.versionNumber("1.0.2.5-beta"))
        assertTrue(AppUpdatePick.versionNumber("1.0.2.1-beta") > AppUpdatePick.versionNumber("1.0.1"))
    }
}
