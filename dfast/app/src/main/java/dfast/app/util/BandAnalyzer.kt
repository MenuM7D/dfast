package dfast.app.util

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * 24 log-spaced band levels (50 Hz to 9 kHz) of the PCM, one frame every [HOP] samples (pure maths, unit tested).
 * Each band is divided by its own slow running maximum (about 10 s), so quiet and loud songs both fill the bars,
 * and a bass-heavy song does not drown the highs. Levels are 0..1; the screen adds the fast-rise / slow-fall smoothing.
 */
class BandAnalyzer(private val sampleRate: Int) {
    class Frame(val timeS: Double, val levels: FloatArray)

    private val win = FloatArray(N) { (0.5 - 0.5 * cos(2.0 * PI * it / (N - 1))).toFloat() }
    private val ring = FloatArray(N)
    private val re = FloatArray(N)
    private val im = FloatArray(N)
    private val cosT = FloatArray(N / 2) { cos(2.0 * PI * it / N).toFloat() }
    private val sinT = FloatArray(N / 2) { (-sin(2.0 * PI * it / N)).toFloat() }
    private val lo = IntArray(BANDS)
    private val hi = IntArray(BANDS)
    private val agc = FloatArray(BANDS) { 1e-3f }
    var total = 0L; private set

    init {
        val ratio = (F_MAX / F_MIN).pow(1.0 / BANDS)
        for (b in 0 until BANDS) {
            val f0 = F_MIN * ratio.pow(b.toDouble()); val f1 = f0 * ratio
            var l = (f0 * N / sampleRate).toInt(); var h = (f1 * N / sampleRate).toInt()
            l = l.coerceIn(1, N / 2 - 1); h = max(h, l + 1).coerceAtMost(N / 2)
            lo[b] = l; hi[b] = h
        }
    }

    fun feed(x: FloatArray, n: Int): List<Frame> {
        var out: ArrayList<Frame>? = null
        for (i in 0 until n) {
            ring[(total % N).toInt()] = x[i]; total++
            if (total >= N && (total - N) % HOP == 0L) {
                if (out == null) out = ArrayList(2)
                out.add(analyse())
            }
        }
        return out ?: emptyList()
    }

    private fun analyse(): Frame {
        val start = (total % N).toInt()
        for (i in 0 until N) { re[i] = ring[(start + i) % N] * win[i]; im[i] = 0f }
        fft()
        val lv = FloatArray(BANDS)
        for (b in 0 until BANDS) {
            var s = 0f
            for (k in lo[b] until hi[b]) s += kotlin.math.sqrt(re[k] * re[k] + im[k] * im[k])
            val raw = log10(1f + 300f * s / (hi[b] - lo[b]) / (N / 4f)).toFloat()
            agc[b] = max(agc[b] * AGC_DECAY, raw)
            lv[b] = ((raw / max(agc[b], 1e-3f) - 0.5f) / 0.5f).coerceIn(0f, 1f).let { it.pow(1.2f) }
        }
        return Frame((total - N / 2).toDouble() / sampleRate, lv)
    }

    private fun fft() {
        var j = 0
        for (i in 1 until N) {
            var bit = N shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val tr = re[i]; re[i] = re[j]; re[j] = tr; val ti = im[i]; im[i] = im[j]; im[j] = ti }
        }
        var len = 2
        while (len <= N) {
            val half = len / 2; val step = N / len
            var i = 0
            while (i < N) {
                for (k in 0 until half) {
                    val wr = cosT[k * step]; val wi = sinT[k * step]
                    val a = i + k; val b = a + half
                    val xr = re[b] * wr - im[b] * wi; val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr; im[b] = im[a] - xi; re[a] += xr; im[a] += xi
                }
                i += len
            }
            len = len shl 1
        }
    }

    companion object {
        const val BANDS = 24
        const val N = 2048
        const val HOP = 1024
        private const val F_MIN = 50.0
        private const val F_MAX = 9000.0
        private const val AGC_DECAY = 0.9955f   // about 10 s at 47 frames a second
    }
}
