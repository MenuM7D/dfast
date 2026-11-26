package dfast.app.util

import android.os.SystemClock
import android.util.Log

/**
 * Start-up timing of a play request, so we can SEE where the seconds go on a real phone:
 * tap -> resolve start -> resolve end (cache / newpipe / ytdlp) -> player attached -> buffering -> ready -> playing.
 * Only durations and the extractor name are kept (no URLs, titles or personal data).
 * Logcat tag: DFastStartup. The last 20 runs can be read in the player screen (long-press the title).
 */
object StartupTrace {
    private const val TAG = "DFastStartup"
    private var t0 = 0L
    private var kind = ""
    private val phases = ArrayList<String>()
    private val done = ArrayDeque<String>()

    @Synchronized fun begin(what: String) {
        t0 = SystemClock.elapsedRealtime(); kind = what; phases.clear()
        Log.d(TAG, "$what begin")
    }

    @Synchronized fun mark(phase: String, extra: String = "") {
        if (t0 == 0L) return
        val ms = SystemClock.elapsedRealtime() - t0
        phases.add(if (extra.isEmpty()) "$phase ${ms}ms" else "$phase($extra) ${ms}ms")
        Log.d(TAG, "$kind $phase $extra ${ms}ms")
    }

    /** Finishes the run when the sound or picture is really going. */
    @Synchronized fun end(phase: String = "playing") {
        if (t0 == 0L) return
        mark(phase)
        done.addFirst("$kind: " + phases.joinToString(" > "))
        while (done.size > 20) done.removeLast()
        t0 = 0L
    }

    @Synchronized fun active() = t0 != 0L

    @Synchronized fun lines(): List<String> = done.toList()
}
