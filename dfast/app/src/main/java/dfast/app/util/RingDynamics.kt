package dfast.app.util

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

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
    /** 0..1 small ripple on the outline (follows mid and high). */
    var ripple = FLOOR; private set
    /** 0..1, jumps on a strong beat and fades in about 0.3 s. Drives the zoom punch and the colour blobs. Calm songs stay near 0. */
    var punch = 0f; private set
    /** 0..1 how energetic this song is overall (slow). */
    var energy = 0f; private set
    /** true on the frame a strong beat was found. */
    var beat = false; private set

    private fun k(dt: Float, tau: Float) = 1f - exp(-dt / tau)

    fun step(dtMs: Long, bass: Float, mid: Float, high: Float, live: Boolean, t: Float) {
        val dt = min(max(dtMs, 0L), 64L) / 1000f
        sinceBeat += dtMs.coerceAtLeast(0L)
        punch *= exp(-dt / 0.16f)
        beat = false
        if (!live) {
            lobe += (0.06f + 0.03f * sin(t * 1.3f) - lobe) * k(dt, 0.4f)
            ripple += (FLOOR - ripple) * k(dt, 0.4f)
            energy += (0f - energy) * k(dt, 1.0f)
            return
        }
        val x = floatArrayOf(bass.coerceIn(0f, 1f), mid.coerceIn(0f, 1f), high.coerceIn(0f, 1f))
        val absn = FloatArray(3); val rel = FloatArray(3)
        for (i in 0 until 3) {
            val v = x[i]
            avg[i] += (v - avg[i]) * k(dt, 6f)
            peak[i] = max(v, peak[i] - (peak[i] - avg[i]) * k(dt, 15f))
            absn[i] = min(1f, v / 0.6f)
            rel[i] = ((v - avg[i]) / max(peak[i] - avg[i], 0.06f)).coerceIn(0f, 1f)
        }
        song += (0.6f * avg[0] + 0.3f * avg[1] + 0.1f * avg[2] - song) * k(dt, 8f)
        energy = min(1f, song / 0.45f)
        val gain = 0.4f + 0.6f * energy
        fun lvl(i: Int) = min(1f, (0.4f * absn[i] + 0.6f * rel[i]) * gain)
        val lt = max(FLOOR, lvl(0))
        val rt = max(FLOOR, 0.5f * lvl(1) + 0.5f * lvl(2))
        lobe += (lt - lobe) * k(dt, if (lt > lobe) 0.05f else 0.18f)
        ripple += (rt - ripple) * k(dt, if (rt > ripple) 0.05f else 0.18f)
        val strength = rel[0] * gain
        if (strength > 0.62f && x[0] > avg[0] * 1.25f + 0.04f && sinceBeat > 220) {
            sinceBeat = 0; punch = max(punch, strength); beat = true
        }
    }

    companion object { const val FLOOR = 0.05f }
}
