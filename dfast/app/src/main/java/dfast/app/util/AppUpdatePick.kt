package dfast.app.util

import dfast.app.database.models.GithubRelease

/**
 * Which release the app offers (pure, unit tested). Same rules as before, moved out of UpdateUtil:
 * which release the installed build is offered (see pick).
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

    fun isBetaBuild(versionName: String) = versionName.contains("beta", true)

    /**
     * The channel follows the INSTALLED build, not a setting (the old "update_beta" value is never read):
     * an official build is only ever offered an official release; a beta build is offered the newest of beta or official
     * (the official of a line ranks above its betas). Drafts and skipped versions are never offered. Null when up to date.
     */
    fun pick(all: List<GithubRelease>, currentVersionName: String, skipped: List<String>): GithubRelease? {
        val cur = versionNumber(currentVersionName)
        val pool = all.filter { !it.draft && it.tag_name !in skipped && (isBetaBuild(currentVersionName) || !isBeta(it)) }
        val best = pool.maxByOrNull { versionNumber(it.tag_name.removePrefix("v")) } ?: return null
        return if (versionNumber(best.tag_name.removePrefix("v")) > cur) best else null
    }
}
