package dfast.app.util

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

/**
 * Resumable download of a release asset (plain JVM, unit tested against a local server).
 * Evidence from the real GitHub asset URL (curl, v1.0.1 arm64 APK): 302 to the CDN, then "Range: bytes=N-" gives 206 with
 * "Content-Range: bytes N-(size-1)/size"; a range at or past the end gives 416; the CDN ignores If-Range
 * (a changed ETag still gives 206), so a stitched file is trusted only after its checksum matches.
 */
object ApkDownloader {
    class Failure(msg: String) : Exception(msg)

    /**
     * Downloads [url] into [dir]/[finalName] (through [finalName].part, resumed when a partial file exists).
     * [verify] gets the finished file (size is checked here). Wrong size or failed verification deletes the part and fails.
     * [onProgress] 0..100. [alive] is polled while copying (return false to stop; the .part stays for the next try).
     */
    fun download(
        client: OkHttpClient, url: String, dir: File, finalName: String, expected: Long,
        verify: (File) -> Boolean, alive: () -> Boolean = { true }, onProgress: (Long) -> Unit = {}
    ): File {
        val target = File(dir, finalName); val part = File(dir, "$finalName.part")
        var from = (ApkCache.plan(0, part.length(), expected) as? ApkCache.Plan.Resume)?.from ?: 0L
        if (from == 0L) part.delete()
        var resp = client.newCall(Request.Builder().url(url).apply { if (from > 0) header("Range", "bytes=$from-") }.build()).execute()
        if (from > 0) {
            val ok = resp.code == 206 && ApkCache.contentRangeOk(resp.header("Content-Range"), from, expected)
            if (!ok) { // server ignored the range, or answered for something else: start over
                resp.close(); part.delete(); from = 0
                resp = client.newCall(Request.Builder().url(url).build()).execute()
            }
        }
        resp.use { r ->
            if (!r.isSuccessful) throw Failure(r.body.string())
            val total = if (expected > 0) expected else from + r.body.contentLength()
            var done = from
            r.body.byteStream().use { input ->
                FileOutputStream(part, from > 0).use { out ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        if (!alive()) throw java.util.concurrent.CancellationException("Download cancelled by user")
                        out.write(buf, 0, n); done += n
                        if (total > 0) onProgress(((done * 100) / total).coerceIn(0, 100))
                    }
                }
            }
        }
        if (expected > 0 && part.length() != expected) throw Failure("Incomplete download") // part kept: resumes next time
        if (!verify(part)) { part.delete(); throw Failure("Downloaded file failed verification") }
        target.delete()
        if (!part.renameTo(target)) throw Failure("Could not save the update")
        return target
    }
}
