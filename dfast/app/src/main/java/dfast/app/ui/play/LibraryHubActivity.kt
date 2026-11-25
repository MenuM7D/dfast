package dfast.app.ui.play

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dfast.app.R
import dfast.app.util.OrbitColors
import dfast.app.util.OrbitTabs
import dfast.app.util.PlayStore
import dfast.app.util.ThemeUtil

/** Library hub: four tiles and the last things played. */
class LibraryHubActivity : AppCompatActivity() {
    private fun dp(n: Int) = PlayUi.dp(this, n)
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtil.updateTheme(this)
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.bg_orbit) }
        root.addView(TextView(this).apply {
            text = getString(R.string.nav_library); textSize = 24f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(OrbitColors.ink(this@LibraryHubActivity))
            setPadding(dp(18), dp(14), dp(18), dp(4))
        })
        root.addView(OrbitTabs.build(this, 2))
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(24)) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars()); v.setPadding(0, b.top, 0, b.bottom); insets
        }
    }

    override fun onResume() { super.onResume(); render() }

    private fun tile(icon: Int, name: String, count: Int, target: Class<*>): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = PlayUi.glass(this@LibraryHubActivity, 24); setPadding(dp(16), dp(16), dp(16), dp(16))
        addView(android.widget.ImageView(this@LibraryHubActivity).apply { setImageResource(icon) }, LinearLayout.LayoutParams(dp(28), dp(28)))
        addView(TextView(this@LibraryHubActivity).apply { text = name; textSize = 15f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(OrbitColors.ink(this@LibraryHubActivity)); setPadding(0, dp(10), 0, 0) })
        addView(TextView(this@LibraryHubActivity).apply { text = count.toString(); textSize = 12f; setTextColor(OrbitColors.inkSoft(this@LibraryHubActivity)) })
        setOnClickListener { startActivity(Intent(this@LibraryHubActivity, target)) }
    }

    private fun render() {
        body.removeAllViews()
        val recents = PlayStore.recents(this)
        val tiles = listOf(
            tile(R.drawable.ic_pl_recent, getString(R.string.o_recents), recents.size, RecentsActivity::class.java),
            tile(R.drawable.ic_heart_filled, getString(R.string.play_favorites), PlayStore.favorites(this).size, FavoritesActivity::class.java),
            tile(R.drawable.ic_pl_offline, getString(R.string.o_saved_offline), -1, OfflineActivity::class.java),
            tile(R.drawable.ic_pl_playlist, getString(R.string.play_playlists), PlayStore.playlistNames(this).size, PlaylistsActivity::class.java)
        )
        // the offline tile has no cheap count, so show it without a number
        (tiles[2].getChildAt(2) as TextView).text = getString(R.string.o_videos_songs)
        body.addView(PlayUi.grid(this, tiles))
        body.addView(TextView(this).apply {
            text = getString(R.string.o_recently_played); textSize = 14f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(OrbitColors.ink(this@LibraryHubActivity)); setPadding(dp(16), dp(8), dp(16), dp(6))
        })
        if (recents.isEmpty()) body.addView(PlayUi.empty(this, getString(R.string.o_nothing_played)))
        recents.take(5).forEach { r -> body.addView(PlayUi.row(this, r) { startActivity(PlayerActivity.intent(this, r, recents)) }) }
    }
}
