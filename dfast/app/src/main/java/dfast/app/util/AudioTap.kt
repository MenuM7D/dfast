package dfast.app.util

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/** Reads the PCM the player is about to play and keeps three smoothed band levels (0..1) for the disc view. */
@OptIn(UnstableApi::class)
object AudioTap : TeeAudioProcessor.AudioBufferSink {
    @Volatile var bass = 0f
    @Volatile var mid = 0f
    @Volatile var high = 0f
    @Volatile var lastUpdate = 0L
    private var enc = C.ENCODING_PCM_16BIT
    private var channels = 2
    private var lp = 0f
    class Pending(val baseWallMs: Long, val strength: Float)
    /** Beats found in the PCM, waiting for the moment their sound is heard (DiscView releases them). */
    val onsets = java.util.concurrent.ConcurrentLinkedQueue<Pending>()
    @Volatile var flushGen = 0
    @Volatile var flushWallMs = 0L
    @Volatile var fedMs = 0.0
    private var bandAn: BandAnalyzer? = null
    private var fedSamples = 0L
    class BandPending(val baseWallMs: Long, val levels: FloatArray)
    /** Band frames waiting for the moment their sound is heard (DiscView releases them, like the beats before). */
    val bands = java.util.concurrent.ConcurrentLinkedQueue<BandPending>()
    private var rate = 44100
    private var mono = FloatArray(0)
    private var lp2 = 0f

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        enc = encoding; channels = max(1, channelCount); rate = sampleRateHz
        bandAn = if (encoding == C.ENCODING_PCM_16BIT && sampleRateHz in 8000..96000) BandAnalyzer(sampleRateHz) else null
        bands.clear(); onsets.clear(); fedSamples = 0L; fedMs = 0.0; flushWallMs = System.currentTimeMillis(); flushGen++
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (enc != C.ENCODING_PCM_16BIT) return
        val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val n = b.remaining() / 2 / channels
        if (n <= 0) return
        var eBass = 0f; var eMid = 0f; var eHigh = 0f
        var i = 0
        val step = 2
        while (i < n) {
            var s = 0f
            for (c in 0 until channels) s += b.getShort(b.position() + (i * channels + c) * 2) / 32768f
            s /= channels
            lp += 0.03f * (s - lp)          // low pass: bass
            lp2 += 0.25f * (s - lp2)         // wider low pass
            val mids = lp2 - lp
            val highs = s - lp2
            eBass += abs(lp); eMid += abs(mids); eHigh += abs(highs)
            i += step
        }
        val cnt = (n / step).coerceAtLeast(1)
        bass = smooth(bass, (eBass / cnt * 7f).coerceIn(0f, 1f))
        mid = smooth(mid, (eMid / cnt * 9f).coerceIn(0f, 1f))
        high = smooth(high, (eHigh / cnt * 14f).coerceIn(0f, 1f))
        lastUpdate = System.currentTimeMillis()
        fedSamples += n; fedMs = fedSamples * 1000.0 / rate
        feedBands(b, n)
    }

    private fun feedBands(b: ByteBuffer, n: Int) {
        val d = bandAn ?: return
        if (mono.size < n) mono = FloatArray(n)
        for (i in 0 until n) { var s = 0f; for (c in 0 until channels) s += b.getShort(b.position() + (i * channels + c) * 2) / 32768f; mono[i] = s / channels }
        val found = d.feed(mono, n)
        val nowMs = System.currentTimeMillis()
        val fedS = fedSamples.toDouble() / rate
        for (f in found) {
            if (bands.size > 160) bands.poll()
            bands.add(BandPending(nowMs - ((fedS - f.timeS) * 1000.0).toLong(), f.levels))
        }
    }

    private fun smooth(old: Float, new: Float) = if (new > old) new else old * 0.82f + new * 0.18f
}
