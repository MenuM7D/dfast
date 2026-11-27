package dfast.app.util

import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress

class ApkDownloaderTest {
    private val data = ByteArray(300_000) { (it * 31 + it / 7).toByte() }
    private val sha = java.security.MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    private lateinit var server: HttpServer
    private val ranges = mutableListOf<String?>()
    private var honourRange = true
    private var wrongTotal = false
    private lateinit var dir: File
    private val client = OkHttpClient()
    private val verify = { f: File -> ApkCache.sha256(f) == sha }
    private fun url() = "http://127.0.0.1:${server.address.port}/a"

    @Before fun up() {
        dir = java.nio.file.Files.createTempDirectory("apk").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // /a redirects to /b like github.com -> the CDN; the range must survive the redirect
        server.createContext("/a") { ex -> ex.responseHeaders.add("Location", "/b"); ex.sendResponseHeaders(302, -1); ex.close() }
        server.createContext("/b") { ex ->
            val r = ex.requestHeaders.getFirst("Range"); ranges.add(r)
            val m = if (r != null && honourRange) Regex("bytes=(\\d+)-").find(r) else null
            if (m != null) {
                val s = m.groupValues[1].toInt()
                if (s >= data.size) { ex.sendResponseHeaders(416, -1); ex.close(); return@createContext }
                ex.responseHeaders.add("Content-Range", "bytes $s-${data.size - 1}/${if (wrongTotal) data.size + 5 else data.size}")
                ex.sendResponseHeaders(206, (data.size - s).toLong()); ex.responseBody.write(data, s, data.size - s)
            } else { ex.sendResponseHeaders(200, data.size.toLong()); ex.responseBody.write(data) }
            ex.close()
        }
        server.start()
    }
    @After fun down() { server.stop(0); dir.deleteRecursively() }

    private fun run() = ApkDownloader.download(client, url(), dir, "DFast-x.apk", data.size.toLong(), verify)

    @Test fun freshDownloadMatchesChecksum() {
        val f = run(); assertEquals(sha, ApkCache.sha256(f)); assertFalse(File(dir, "DFast-x.apk.part").exists()); assertNull(ranges.single())
    }
    @Test fun partialFileResumesWithRangeThroughTheRedirect() {
        File(dir, "DFast-x.apk.part").writeBytes(data.copyOf(123_456))
        val f = run(); assertEquals(sha, ApkCache.sha256(f)); assertEquals("bytes=123456-", ranges.single())
    }
    @Test fun serverIgnoringRangeRestartsFromZero() {
        honourRange = false; File(dir, "DFast-x.apk.part").writeBytes(data.copyOf(50_000))
        val f = run(); assertEquals(sha, ApkCache.sha256(f))
    }
    @Test fun contentRangeForAnotherFileSizeRestarts() {
        wrongTotal = true; File(dir, "DFast-x.apk.part").writeBytes(data.copyOf(50_000))
        val f = run(); assertEquals(sha, ApkCache.sha256(f)); assertEquals(2, ranges.size)
    }
    @Test fun corruptedPartFailsVerificationThenIsDeletedAndNextTryRestarts() {
        val bad = data.copyOf(80_000); bad[10] = (bad[10] + 1).toByte(); File(dir, "DFast-x.apk.part").writeBytes(bad)
        try { run(); fail("should fail verification") } catch (e: ApkDownloader.Failure) {}
        assertFalse(File(dir, "DFast-x.apk.part").exists()); assertFalse(File(dir, "DFast-x.apk").exists())
        assertEquals(sha, ApkCache.sha256(run()))
    }
    @Test fun partAtOrPastTheEndRestarts() {
        File(dir, "DFast-x.apk.part").writeBytes(ByteArray(data.size + 10))
        assertEquals(sha, ApkCache.sha256(run()))
    }
    @Test fun cancelledDownloadKeepsThePartForResume() {
        var n = 0
        try { ApkDownloader.download(client, url(), dir, "DFast-x.apk", data.size.toLong(), verify, alive = { ++n < 5 }); fail() } catch (e: java.util.concurrent.CancellationException) {}
        assertTrue(File(dir, "DFast-x.apk.part").length() > 0); assertEquals(sha, ApkCache.sha256(run()))
    }
    @Test fun contentRangeParsing() {
        assertTrue(ApkCache.contentRangeOk("bytes 100-299/300", 100, 300)); assertFalse(ApkCache.contentRangeOk("bytes 0-299/300", 100, 300))
        assertFalse(ApkCache.contentRangeOk("bytes 100-299/999", 100, 300)); assertFalse(ApkCache.contentRangeOk(null, 100, 300)); assertTrue(ApkCache.contentRangeOk("bytes 100-299/300", 100, 0))
    }
}
