package dfast.app.util

import dfast.app.database.models.GithubRelease

/**
 * Which release the app offers (pure, unit tested). Same rules as before, moved out of UpdateUtil:
 * only official releases are offered: betas (tag with "beta" or a GitHub prerelease) and drafts are ignored.
 */
object AppUpdatePick {
    /** Sortable key from a tag ("v1.0.1-beta.3", "1.0.0") or a version name ("1.0.1.3-beta"); an official release sorts above its betas. */
    fun versionNumber(s: String): Long {
        val nums = Regex("\\d+").findAll(s).map { it.value.toLongOrNull() ?: 0L }.toList()
        val major = nums.getOrElse(0) { 0L }.coerceAtMost(999L)
        val minor = nums.getOrElse(1) { 0L }.coerceAtMost(999L)
        val patch = nums.getOrElse(2) { 0L }.coerceAtMost(999L)
        val isBeta = s.contains("beta", true)
        val build = if (isBeta) nums.getOrElse(3) { 0L }.coerceAtMost(9998L) else 9999L
        return (((major * 1000L + minor) * 1000L + patch) * 10000L) + build
    }

    fun isBeta(r: GithubRelease) = r.prerelease || r.tag_name.contains("beta", true)

    /**
     * Only official releases are ever offered, whatever the old "update_beta" setting says (the setting is no longer read).
     * Betas are installed by hand from GitHub. Returns null when up to date, skipped, or nothing official exists.
     */
    fun pick(all: List<GithubRelease>, currentVersionName: String, skipped: List<String>): GithubRelease? {
        val v = all.firstOrNull { !it.draft && !isBeta(it) } ?: return null
        val newer = versionNumber(currentVersionName) < versionNumber(v.tag_name.removePrefix("v"))
        return if (newer && v.tag_name !in skipped) v else null
    }
}
