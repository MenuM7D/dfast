package dfast.app.util

/**
 * How far ahead of the speaker the player's PCM is, worked out while the song plays (pure maths, unit tested).
 *
 * AudioTap sees each PCM block when the player hands it to the audio output, long before it is heard (the output buffer is
 * hundreds of ms by default). Right after every flush (new song, seek) the audible position equals the player's position,
 * so: lead = (position at flush + PCM fed since flush) - (position now). That is measured per song and per output device,
 * and follows a route change at the next flush. It does NOT include the delay inside a Bluetooth link (the phone cannot
 * measure it), which is why a route estimate and a small user adjustment sit on top.
 */
class BeatSync {
    private var gen = -1
    private var p0 = 0.0
    private var valid = false
    private var smooth = -1.0
    private var samples = 0
    /** Measured lead is trusted only after this many samples of the same flush. */
    val trusted get() = valid && samples >= WARM

    fun observe(flushGen: Int, flushWallMs: Long, nowMs: Long, fedMs: Double, playerPosMs: Long, playing: Boolean, speed: Float) {
        if (flushGen != gen) {
            gen = flushGen; samples = 0; smooth = -1.0
            // only a baseline taken right after the flush is exact (the position has not moved yet)
            valid = nowMs - flushWallMs <= BASELINE_WINDOW_MS && kotlin.math.abs(speed - 1f) < 0.001f && playerPosMs >= 0
            p0 = playerPosMs.toDouble()
            return
        }
        if (!valid || !playing || kotlin.math.abs(speed - 1f) >= 0.001f) return
        val lead = p0 + fedMs - playerPosMs
        if (lead < MIN_LEAD || lead > MAX_LEAD) return        // not plausible: ignore this sample
        smooth = if (smooth < 0) lead else smooth + (lead - smooth) * 0.04
        samples++
    }

    /** Lead in ms: the measured one when trusted, else [FALLBACK_MS]. */
    fun leadMs(): Double = if (trusted) smooth else FALLBACK_MS

    companion object {
        const val FALLBACK_MS = 350.0
        const val BASELINE_WINDOW_MS = 250L
        const val MIN_LEAD = 20.0
        const val MAX_LEAD = 1500.0
        const val WARM = 30
        /** Estimated extra delay of a Bluetooth link on top of what the phone can measure. */
        const val BLUETOOTH_EXTRA_MS = 150.0
        /** When the screen should show an onset: the moment its sound is heard. */
        fun fireAtMs(baseWallMs: Long, leadMs: Double, extraMs: Double, userMs: Double): Double = baseWallMs + leadMs + extraMs + userMs
    }
}
