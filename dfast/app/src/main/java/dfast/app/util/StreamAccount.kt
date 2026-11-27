package dfast.app.util

import android.content.Context
import androidx.preference.PreferenceManager
import java.util.concurrent.ConcurrentHashMap

/**
 * The signed-in YouTube account for playback of age-restricted videos only.
 * Used by the player's yt-dlp runs AFTER a run without it came back empty (never for normal videos, never for downloads).
 * Switch "Use account for age-restricted videos" (default on); it only has an effect while signed in.
 */
object StreamAccount {
    const val KEY = "stream_use_account"
    fun enabled(c: Context) = PreferenceManager.getDefaultSharedPreferences(c).getBoolean(KEY, true) && GoogleAccount.isSignedIn(c)
    fun path(c: Context): String? = if (enabled(c)) GoogleAccount.sessionFile(c).absolutePath else null

    // videos that already needed the account in this session: their next runs start with it, no failed run first
    private val needed = ConcurrentHashMap.newKeySet<String>()
    fun markNeeded(url: String) { needed.add(url) }
    fun isNeeded(url: String) = needed.contains(url)
}
