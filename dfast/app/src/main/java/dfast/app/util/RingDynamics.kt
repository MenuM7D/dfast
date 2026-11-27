package dfast.app.util

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Song-relative motion for the audio screen's ring. Pure maths (no Android), so it is unit tested.
 *
 * Input: the three band levels from AudioTap (0..1), the frame time and whether live audio data is arriving.
 * Every band is compared with the song's own slow average (6 s) and its own slow peak (15 s), so a beat that stands out
 * in a calm song counts, and a steady loud passage does not keep the ring at its maximum.
 * The overall size is then scaled by the song's character (slow average of its energy): a bass-heavy track
 * reaches the full size, a calm one stays small. Floors keep a quiet ring alive; ceilings keep it inside 0..1.
 */
class RingDynamics {
    private val avg = FloatArray(3)
    private val peak = FloatArray(3)
    private var song = 0f
    private var sinceBeat = 1_000_000L

    /** 0..1 size of the wing lobes (follows the bass). */
    var lobe = FLOOR; private set
    /** 0..1 soft ripple on the outline (follows mid and high). Never below [RIPPLE_FLOOR], so the outline always has a little life. */
    var ripple = RIPPLE_FLOOR; private set
    /** Motion setting: 1 = auto (song-relative), below 1 calmer, above 1 stronger. Floors and the 0..1 ceiling still hold. */
    var intensity = 1f
    private var base = FLOOR
    private var rbase = RIPPLE_FLOOR
    /** 0..1, jumps on a strong beat and fades in about 0.3 s. Drives the zoom punch and the colour blobs. Calm songs stay near 0. */
    var punch = 0f; private set
    /** 0..1 how energetic this song is overall (slow). */
    var energy = 0f; private set
    /** true on the frame a beat was found. */
    var beat = false; private set

    private val waveAge = floatArrayOf(9f, 9f, 9f)
    private val waveAmp = FloatArray(3)
    /** 0..1 how far shock wave [i] (0..2) has travelled out of the cover; 1 or more = not visible. */
    fun waveProgress(i: Int) = waveAge[i] / WAVE_SECONDS
    /** 0..1 strength of shock wave [i]. */
    fun waveStrength(i: Int) = waveAmp[i]

    private fun k(dt: Float, tau: Float) = 1f - exp(-dt / tau)

    fun step(dtMs: Long, bass: Float, mid: Float, high: Float, live: Boolean, t: Float) {
        val dt = min(max(dtMs, 0L), 64L) / 1000f
        sinceBeat += dtMs.coerceAtLeast(0L)
        punch *= exp(-dt / 0.16f)
        beat = false
        for (i in 0 until 3) waveAge[i] += dt
        // slow breathing (period about 5 s) is always on: a quiet or paused song is never a still picture
        val breath = 0.035f * sin(t * 1.25f)
        if (!live) {
            base += (FLOOR - base) * k(dt, 0.4f)
            rbase += (RIPPLE_FLOOR - rbase) * k(dt, 0.4f)
            energy += (0f - energy) * k(dt, 1.0f)
            lobe = (base + breath).coerceIn(0f, 1f); ripple = rbase
            return
        }
        val x = floatArrayOf(bass.coerceIn(0f, 1f), mid.coerceIn(0f, 1f), high.coerceIn(0f, 1f))
        val absn = FloatArray(3); val rel = FloatArray(3)
        for (i in 0 until 3) {
            val v = x[i]
            avg[i] += (v - avg[i]) * k(dt, 6f)
            peak[i] = max(v, peak[i] - (peak[i] - avg[i]) * k(dt, 15f))
            absn[i] = min(1f, v / 0.6f)
            rel[i] = ((v - avg[i]) / max(peak[i] - avg[i], 0.02f)).coerceIn(0f, 1f)
        }
        song += (0.6f * avg[0] + 0.3f * avg[1] + 0.1f * avg[2] - song) * k(dt, 8f)
        energy = min(1f, song / 0.45f)
        val gain = 0.4f + 0.6f * energy
        // the 0.75 power lifts small responses, so quiet bass and mid are still visible
        fun lvl(i: Int) = Math.pow(min(1f, (0.4f * absn[i] + 0.6f * rel[i]) * gain).toDouble(), 0.75).toFloat()
        val lt = FLOOR + (1f - FLOOR) * (lvl(0) * intensity).coerceIn(0f, 1f)
        val rt = RIPPLE_FLOOR + (1f - RIPPLE_FLOOR) * ((0.5f * lvl(1) + 0.5f * lvl(2)) * intensity).coerceIn(0f, 1f)
        base += (lt - base) * k(dt, if (lt > base) 0.05f else 0.2f)
        rbase += (rt - rbase) * k(dt, if (rt > rbase) 0.05f else 0.2f)
        lobe = (base + breath).coerceIn(0f, 1f); ripple = rbase.coerceIn(0f, 1f)
    }

    /**
     * A beat found by [OnsetDetector] and released by the sync delay: [strength] 0..1 (how far the onset stood out in this song).
     * Starts the kick, the colour pulse and one shock wave. Calm songs kick gently (scaled by the song's slow energy).
     */
    fun onset(strength: Float) {
        val character = min(1f, song / 0.25f)
        val p = min(1f, (0.3f + 0.8f * strength.coerceIn(0f, 1f)) * (0.45f + 0.55f * character) * min(intensity, 1.3f))
        punch = max(punch, p); beat = true
        var oldest = 0; for (i in 1 until 3) if (waveAge[i] > waveAge[oldest]) oldest = i
        waveAge[oldest] = 0f; waveAmp[oldest] = p
    }

    companion object {
        const val FLOOR = 0.08f; const val RIPPLE_FLOOR = 0.14f; const val WAVE_SECONDS = 0.7f
        /** Pref value ("auto", "calm", "strong") to the intensity factor; anything unknown is auto. */
        fun intensityFor(mode: String?) = when (mode) { "calm" -> 0.6f; "strong" -> 1.3f; else -> 1f }
    }
}
