package dfast.app.util

import dfast.app.database.models.GithubRelease
import org.junit.Assert.*
import org.junit.Test
import java.util.Date

class AppUpdatePickTest {
    private fun r(tag: String, pre: Boolean = false, draft: Boolean = false) = GithubRelease("u", tag, "", Date(), listOf<dfast.app.database.models.GithubReleaseAsset>(), pre, draft)
    // GitHub lists newest first: a later beta of the next version comes before the official release
    private val list = listOf(r("v1.0.2-beta.1", true), r("v1.0.1"), r("v1.0.1-beta.16", true), r("v1.0.0"))

    // the channel follows the installed build; the old stored "update_beta" value is not read (there is no such parameter)
    @Test fun officialBuildIsNotOfferedTheNextBeta() { assertNull(AppUpdatePick.pick(list, "1.0.1", listOf<String>())) }
    @Test fun officialBuildOnOlderOfficialGetsOnlyOfficial() { assertEquals("v1.0.1", AppUpdatePick.pick(list, "1.0.0", listOf<String>())?.tag_name) }
    @Test fun prereleaseFlagAloneKeepsItFromOfficialBuilds() { assertNull(AppUpdatePick.pick(listOf(r("v1.0.3", pre = true), r("v1.0.1")), "1.0.1", listOf<String>())) }
    @Test fun draftsAreNeverOffered() { assertNull(AppUpdatePick.pick(listOf(r("v1.0.9", draft = true), r("v1.0.1")), "1.0.1.5-beta", listOf<String>())) }
    @Test fun betaBuildGetsTheNewerBeta() {
        val l = listOf(r("v1.0.1-beta.17", true), r("v1.0.1-beta.16", true), r("v1.0.0"))
        assertEquals("v1.0.1-beta.17", AppUpdatePick.pick(l, "1.0.1.16-beta", listOf<String>())?.tag_name)
    }
    @Test fun betaBuildGetsTheOfficialOfItsLineWhenItAppears() {
        assertEquals("v1.0.1", AppUpdatePick.pick(listOf(r("v1.0.1"), r("v1.0.1-beta.16", true)), "1.0.1.16-beta", listOf<String>())?.tag_name)
    }
    @Test fun betaBuildOnTheOldLineIsOfferedTheNewLineBeta() {
        assertEquals("v1.0.2-beta.1", AppUpdatePick.pick(list, "1.0.1.16-beta", listOf<String>())?.tag_name)
    }
    @Test fun betaBuildNewerThanEverythingGetsNothing() { assertNull(AppUpdatePick.pick(list, "1.0.2.1-beta", listOf<String>())) }
    @Test fun betaBuildOnAnOldOfficialLineIsNotOfferedAnOlderOfficial() { assertNull(AppUpdatePick.pick(listOf(r("v1.0.1"), r("v1.0.0")), "1.0.2.1-beta", listOf<String>())) }
    @Test fun skippedVersionIsNotOffered() { assertNull(AppUpdatePick.pick(list, "1.0.0", listOf("v1.0.1"))) }
    @Test fun skippedBetaFallsBackToTheNextBestForABetaBuild() {
        assertEquals("v1.0.1", AppUpdatePick.pick(list, "1.0.1.5-beta", listOf("v1.0.2-beta.1"))?.tag_name)
    }
    @Test fun onlyBetasMeansNothingForOfficialBuilds() { assertNull(AppUpdatePick.pick(listOf(r("v1.0.2-beta.1", true)), "1.0.1", listOf<String>())) }
    @Test fun listOf<String>() { assertNull(AppUpdatePick.pick(listOf<String>(), "1.0.1", listOf<String>())) }
    @Test fun versionOrdering() {
        assertTrue(AppUpdatePick.versionNumber("1.0.2") > AppUpdatePick.versionNumber("1.0.2.5-beta"))
        assertTrue(AppUpdatePick.versionNumber("1.0.2.1-beta") > AppUpdatePick.versionNumber("1.0.1"))
    }
}
