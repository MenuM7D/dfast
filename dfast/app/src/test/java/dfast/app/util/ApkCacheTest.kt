package dfast.app.util

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ApkCacheTest {
    @Test fun finishedFileOfTheListedSizeIsReused() { assertEquals(ApkCache.Plan.UseCached, ApkCache.plan(100, 0, 100)) }
    @Test fun wrongSizeFinishedFileIsNotReused() { assertEquals(ApkCache.Plan.Fresh, ApkCache.plan(99, 0, 100)); assertEquals(ApkCache.Plan.Fresh, ApkCache.plan(101, 0, 100)) }
    @Test fun partialFileResumes() { assertEquals(ApkCache.Plan.Resume(40), ApkCache.plan(0, 40, 100)) }
    @Test fun partAsBigAsTheFileOrBiggerRestarts() { assertEquals(ApkCache.Plan.Fresh, ApkCache.plan(0, 100, 100)); assertEquals(ApkCache.Plan.Fresh, ApkCache.plan(0, 120, 100)) }
    @Test fun unknownSizeNeverReuses() { assertEquals(ApkCache.Plan.Fresh, ApkCache.plan(100, 50, 0)) }
    @Test fun staleOnlyOurOtherApks() {
        val names = listOf("DFast-1.0.1-arm64-v8a-release-github.apk", "DFast-1.0.2-arm64-v8a-release-github.apk", "DFast-1.0.2-arm64-v8a-release-github.apk.part", "DFast-1.0.0-x86.apk.part", "ytdlp_2024.apk", "notes.txt")
        assertEquals(listOf("DFast-1.0.1-arm64-v8a-release-github.apk", "DFast-1.0.0-x86.apk.part"), ApkCache.stale(names, "DFast-1.0.2-arm64-v8a-release-github.apk"))
    }
    @Test fun shaFromChecksumList() {
        val h = "a".repeat(64); val sums = "$h  DFast-1.0.1-arm64-v8a-release-github.apk\n${"b".repeat(64)}  other.apk\n"
        assertEquals(h, ApkCache.expectedSha(sums, "DFast-1.0.1-arm64-v8a-release-github.apk"))
        assertNull(ApkCache.expectedSha(sums, "missing.apk")); assertNull(ApkCache.expectedSha("short  x.apk", "x.apk"))
    }
    @Test fun sha256OfAFile() {
        val f = File.createTempFile("apk", ".bin"); f.writeText("abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ApkCache.sha256(f)); f.delete()
    }
}
