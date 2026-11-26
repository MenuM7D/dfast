package dfast.app.util

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.WeakHashMap

/**
 * Gives the play bubble to the other DFast screens (library, recents, favorites, offline, playlists, sites, add songs, settings),
 * so it stays visible while moving around the app. MainActivity and the search screen create their own; the player screen
 * is the player itself and has none.
 */
object MiniPlayerHost : Application.ActivityLifecycleCallbacks {
    private val hosts = WeakHashMap<Activity, MiniPlayer>()
    private val names = setOf(
        "dfast.app.ui.play.LibraryHubActivity", "dfast.app.ui.play.RecentsActivity", "dfast.app.ui.play.FavoritesActivity",
        "dfast.app.ui.play.OfflineActivity", "dfast.app.ui.play.PlaylistsActivity", "dfast.app.ui.play.SitesActivity",
        "dfast.app.ui.play.AddSongsActivity", "dfast.app.ui.more.settings.SettingsActivity"
    )

    override fun onActivityStarted(activity: Activity) {
        if (activity.javaClass.name !in names || hosts.containsKey(activity)) return
        runCatching { hosts[activity] = MiniPlayer(activity) }
    }

    override fun onActivityDestroyed(activity: Activity) {
        hosts.remove(activity)?.let { runCatching { it.release() } }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
}
