package dfast.app.util

import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class ApkDownloaderTest {
    private val data = ByteArray(300_000) { (it * 31 + it / 7).toByte() }
    private val sha = java.security.MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    private lateinit var server: java.net.ServerSocket
    private var thread: Thread? = null
    private val ranges = mutableListOf<String?>()
    private var honourRange = true
    private var wrongTotal = false
    private lateinit var dir: File
    private val client = OkHttpClient()
    private val verify = { f: File -> ApkCache.sha256(f) == sha }
    private fun url() = "http://127.0.0.1:${server.localPort}/a"

    @Before fun up() {
        dir = java.nio.file.Files.createTempDirectory("apk").toFile()
        server = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        thread = Thread {
            while (!server.isClosed) {
                try { server.accept().use { handle(it) } } catch (e: Exception) { if (server.isClosed) break }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    // minimal HTTP/1.1 server on plain sockets: /a redirects to /b (like github.com -> the CDN), /b serves the bytes
    private fun handle(sock: java.net.Socket) {
        val inp = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val path = (inp.readLine() ?: return).split(" ")[1]
        var r: String? = null
        while (true) { val l = inp.readLine() ?: break; if (l.isEmpty()) break; if (l.startsWith("Range:", true)) r = l.substringAfter(":").trim() }
        val out = sock.getOutputStream()
        fun head(status: String, extra: String, len: Int) = out.write(("HTTP/1.1 $status\r\n$extra" + "Content-Length: $len\r\nConnection: close\r\n\r\n").toByteArray())
        if (path == "/a") { head("302 Found", "Location: /b\r\n", 0); out.flush(); return }
        synchronized(ranges) { ranges.add(r) }
        val m = if (r != null && honourRange) Regex("bytes=(\\d+)-").find(r) else null
        if (m != null) {
            val st = m.groupValues[1].toInt()
            if (st >= data.size) { head("416 Range Not Satisfiable", "", 0); out.flush(); return }
            head("206 Partial Content", "Content-Range: bytes $st-${data.size - 1}/${if (wrongTotal) data.size + 5 else data.size}\r\n", data.size - st)
            out.write(data, st, data.size - st)
        } else { head("200 OK", "", data.size); out.write(data) }
        out.flush()
    }
    @After fun down() { server.close(); dir.deleteRecursively() }

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
