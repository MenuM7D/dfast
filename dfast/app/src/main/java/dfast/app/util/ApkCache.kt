package dfast.app.util

import java.io.File
import java.security.MessageDigest

/**
 * Pure rules for the downloaded update APK (no Android classes, unit tested):
 * reuse a finished file, resume a partial one, delete stale DFast APKs.
 * Only files named like our release assets ("DFast-*.apk" and "DFast-*.apk.part") are ever touched,
 * because the same folder also holds the APKs of other packages.
 */
object ApkCache {
    sealed class Plan {
        object UseCached : Plan()
        data class Resume(val from: Long) : Plan()
        object Fresh : Plan()
    }

    /** [expected] is the size the release lists for the asset (0 = unknown). */
    fun plan(finishedLen: Long, partLen: Long, expected: Long): Plan = when {
        expected > 0 && finishedLen == expected -> Plan.UseCached
        expected > 0 && partLen in 1 until expected -> Plan.Resume(partLen)
        else -> Plan.Fresh
    }

    /** "bytes START-END/TOTAL" must start where we resumed and (when known) describe a file of the expected size. */
    fun contentRangeOk(header: String?, from: Long, expected: Long): Boolean {
        val m = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+)").matchEntire(header?.trim().orEmpty()) ?: return false
        val (s, _, t) = m.destructured
        return s.toLong() == from && (expected <= 0 || t.toLong() == expected)
    }

    fun isOurs(name: String) = name.startsWith("DFast-") && (name.endsWith(".apk") || name.endsWith(".apk.part"))

    /** Our APK files in the folder that are not the one for this release (older or newer versions, other ABIs, leftovers). */
    fun stale(names: List<String>, keep: String): List<String> =
        names.filter { isOurs(it) && it != keep && it != "$keep.part" }

    /** Expected SHA-256 of [assetName] from a "hash  name" checksum list, or null. */
    fun expectedSha(sums: String, assetName: String): String? =
        sums.lineSequence().map { it.trim().split(Regex("\\s+"), limit = 2) }
            .firstOrNull { it.size == 2 && it[1].trimStart('*') == assetName && it[0].length == 64 }?.get(0)?.lowercase()

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(65536); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
