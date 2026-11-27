package dfast.app.util

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Beat (onset) detection on live PCM, the standard way music tools do it. Pure maths, no Android, unit tested.
 *
 * 1. 2048-sample Hann windows, a new one every 512 samples, FFT.
 * 2. Log-compressed magnitudes (loudness-independent: a loud master and a quiet one look alike).
 * 3. Spectral flux per band (30-150, 150-600 and 600-5000 Hz): only the RISE since the previous frame, compared with the
 *    maximum of the neighbouring bins (SuperFlux), so a sustained bass note does not count, a new kick does.
 * 4. Onset strength = weighted sum of the band fluxes (kick band counts most).
 * 5. Adaptive threshold from the last 1.5 s: mean + 1.1 x spread + a small floor. A peak above it, at least 150 ms after the last one, is an onset.
 *
 * Feed it mono samples in any chunk size. [feed] returns the onsets found; each carries the stream time at which the sound
 * started (about 30 ms after the start of its analysis frame: measured with synthetic kicks) and its strength 0..1+ (how far
 * above the threshold, relative to the recent peak). Strength below [MIN_STRENGTH] is noise or a faint note.
 */
class OnsetDetector(private val sampleRate: Int) {
    class Onset(val timeS: Double, val strength: Float)

    private val win = FloatArray(N) { (0.5 - 0.5 * cos(2.0 * PI * it / (N - 1))).toFloat() }
    private val ring = FloatArray(N)
    private val re = FloatArray(N)
    private val im = FloatArray(N)
    private val cosT = FloatArray(N / 2) { cos(2.0 * PI * it / N).toFloat() }
    private val sinT = FloatArray(N / 2) { (-sin(2.0 * PI * it / N)).toFloat() }
    /** Total samples fed since creation. */
    var total = 0L; private set

    private val bandLo = IntArray(BANDS.size); private val bandHi = IntArray(BANDS.size) // [lo, hi) bin range
    private val binMax: Int
    private val prev: FloatArray
    private val cur: FloatArray
    private var havePrev = false

    private val fps = sampleRate.toDouble() / HOP
    private val histLen = (1.5 * fps).toInt()
    private val minHist = (1.0 * fps).toInt()
    private val os = DoubleArray(histLen + 4)   // onset strength by frame index modulo size
    private var frame = -1L
    private var lastOnsetFrame = -1_000_000L

    init {
        var mx = 0
        for ((b, band) in BANDS.withIndex()) {
            var lo = -1; var hi = 0
            for (k in 0..N / 2) { val f = k.toDouble() * sampleRate / N; if (f >= band.lo && f < band.hi) { if (lo < 0) lo = k; hi = k + 1 } }
            if (lo < 0) { lo = 0; hi = 0 }
            bandLo[b] = lo; bandHi[b] = hi; mx = max(mx, hi)
        }
        binMax = mx; prev = FloatArray(mx + 1); cur = FloatArray(mx + 1)
    }

    fun feed(x: FloatArray, n: Int): List<Onset> {
        var out: ArrayList<Onset>? = null
        for (i in 0 until n) {
            ring[(total % N).toInt()] = x[i]; total++
            if (total >= N && (total - N) % HOP == 0L) {
                val o = analyse()
                val on = pickAt(o); if (on != null) { if (out == null) out = ArrayList(2); out.add(on) }
            }
        }
        return out ?: emptyList()
    }

    private fun analyse(): Double {
        val start = (total % N).toInt()
        for (i in 0 until N) { re[i] = ring[(start + i) % N] * win[i]; im[i] = 0f }
        fft()
        for (k in 0..binMax - 1) cur[k] = log10(1.0 + 1000.0 * sqrt((re[k] * re[k] + im[k] * im[k]).toDouble())).toFloat()
        if (!havePrev) { System.arraycopy(cur, 0, prev, 0, cur.size); havePrev = true }
        var o = 0.0
        for ((b, band) in BANDS.withIndex()) {
            val lo = bandLo[b]; val hi = bandHi[b]; if (hi <= lo) continue
            var s = 0.0
            for (j in lo until hi) {
                var pm = prev[j]
                if (j - 1 >= lo && prev[j - 1] > pm) pm = prev[j - 1]
                if (j + 1 < hi && prev[j + 1] > pm) pm = prev[j + 1]
                val d = cur[j] - pm; if (d > 0f) s += d
            }
            o += band.weight * (s / (hi - lo))
        }
        System.arraycopy(cur, 0, prev, 0, cur.size)
        return o
    }

    /** New onset-strength value for frame [frame]+1; decides frame [frame] (a peak needs its right neighbour). */
    private fun pickAt(oNew: Double): Onset? {
        frame++
        val j = frame
        os[(j % os.size).toInt()] = oNew
        val i = j - 1
        if (i < 2) return null
        fun v(f: Long) = os[(f % os.size).toInt()]
        val hs = max(0L, i - histLen); val cnt = (i - hs).toInt()
        if (cnt < minHist) return null
        var sum = 0.0; var mxv = 0.0
        for (f in hs until i) { val a = v(f); sum += a; if (a > mxv) mxv = a }
        val mean = sum / cnt
        var vs = 0.0; for (f in hs until i) { val d = v(f) - mean; vs += d * d }
        val thr = mean + 1.1 * sqrt(vs / cnt) + 0.004
        val oi = v(i)
        if (oi > thr && oi >= v(i - 1) && oi > v(j) && (i - lastOnsetFrame) * HOP.toDouble() / sampleRate > 0.15) {
            lastOnsetFrame = i
            return Onset(i.toDouble() * HOP / sampleRate + ONSET_OFFSET_S, ((oi - thr) / max(mxv, 1e-6)).toFloat())
        }
        return null
    }

    /** In-place radix-2 FFT of re/im (length N). */
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

    private class Band(val lo: Double, val hi: Double, val weight: Double)

    companion object {
        const val N = 2048
        const val HOP = 512
        /** Sound start versus analysis frame start, measured with synthetic kicks (about 30 ms). */
        const val ONSET_OFFSET_S = 0.030
        /** Below this an onset is faint (noise floor or a soft note): the screen ignores it. */
        const val MIN_STRENGTH = 0.2f
        private val BANDS = arrayOf(Band(30.0, 150.0, 0.55), Band(150.0, 600.0, 0.28), Band(600.0, 5000.0, 0.17))
    }
}
