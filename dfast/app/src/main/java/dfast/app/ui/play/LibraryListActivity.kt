package dfast.app.ui.play

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import dfast.app.MainActivity
import dfast.app.R
import dfast.app.database.DBManager
import dfast.app.database.enums.DownloadType
import dfast.app.util.MediaRef
import dfast.app.util.OrbitColors
import dfast.app.util.PlayStore
import dfast.app.util.ThemeUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Recents, Favorites, Saved offline and Playlists (plus one playlist opened). One class, four screens. */
open class LibraryListActivity : AppCompatActivity() {
    open val mode: String = "recents"
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var headerHolder: LinearLayout
    private var filter = 0          // favorites: 0 all, 1 songs, 2 videos
    private var offlineTab = 0      // offline: 0 videos, 1 songs, 2 downloading
    private var openedPlaylist: String? = null

    private fun dp(n: Int) = PlayUi.dp(this, n)

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtil.updateTheme(this)
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.bg_orbit) }
        headerHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(headerHolder)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { addView(body) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars()); v.setPadding(0, b.top, 0, b.bottom); insets
        }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (openedPlaylist != null) { openedPlaylist = null; render() } else finish() }
        })
    }

    override fun onResume() { super.onResume(); render() }

    private fun open(r: MediaRef, queue: List<MediaRef>) = startActivity(PlayerActivity.intent(this, r, queue))

    private fun render() {
        headerHolder.removeAllViews(); body.removeAllViews()
        when (mode) {
            "recents" -> recents()
            "favorites" -> favorites()
            "offline" -> offline()
            else -> if (openedPlaylist == null) playlists() else playlistDetail(openedPlaylist!!)
        }
    }

    private fun recents() {
        val list = PlayStore.recents(this)
        val clear = TextView(this).apply { text = getString(R.string.o_clear); setTextColor(OrbitColors.inkSoft(this@LibraryListActivity)); setPadding(dp(8), dp(8), dp(8), dp(8)); setOnClickListener { PlayStore.clearRecents(this@LibraryListActivity); render() } }
        headerHolder.addView(PlayUi.header(this, getString(R.string.o_recents), clear))
        if (list.isEmpty()) { body.addView(PlayUi.empty(this, getString(R.string.o_recents_empty))); return }
        body.addView(sectionTitle(getString(R.string.o_continue)))
        val strip = LinearLayout(this).apply { setPadding(dp(14), 0, dp(14), 0) }
        list.take(4).forEach { r -> strip.addView(PlayUi.stripCard(this, r) { open(r, list) }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) }) }
        body.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(strip) })
        if (list.size > 4) {
            body.addView(sectionTitle(getString(R.string.o_earlier)))
            list.drop(4).forEach { r -> body.addView(PlayUi.row(this, r) { open(r, list) }) }
        }
    }

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t; textSize = 14f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(OrbitColors.ink(this@LibraryListActivity)); setPadding(dp(16), dp(12), dp(16), dp(6))
    }

    private fun favorites() {
        headerHolder.addView(PlayUi.header(this, getString(R.string.play_favorites)))
        val all = PlayStore.favorites(this)
        val pills = LinearLayout(this).apply { setPadding(dp(14), dp(4), dp(14), dp(6)) }
        listOf(getString(R.string.o_all), getString(R.string.o_songs), getString(R.string.o_videos)).forEachIndexed { i, n ->
            pills.addView(PlayUi.pill(this, n, filter == i) { filter = i; render() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
        headerHolder.addView(pills)
        val list = when (filter) { 1 -> all.filter { it.audio }; 2 -> all.filter { !it.audio }; else -> all }
        if (list.isEmpty()) { body.addView(PlayUi.empty(this, getString(R.string.o_fav_empty))); return }
        list.forEach { r ->
            val heart = android.widget.ImageButton(this).apply {
                setImageResource(R.drawable.ic_heart_filled); setBackgroundColor(android.graphics.Color.TRANSPARENT); contentDescription = getString(R.string.o_remove)
                setOnClickListener { PlayStore.toggleFavorite(this@LibraryListActivity, r); render() }
            }
            body.addView(PlayUi.row(this, r, heart) { open(r, list) })
        }
        val play = LinearLayout(this).apply { setPadding(dp(14), dp(10), dp(14), dp(24)) }
        play.addView(PlayUi.pill(this, getString(R.string.o_play_all), true) { open(list.first(), list) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        play.addView(PlayUi.pill(this, getString(R.string.play_shuffle), false) { val s = list.shuffled(); open(s.first(), s) }, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(play)
    }

    private fun offline() {
        headerHolder.addView(PlayUi.header(this, getString(R.string.o_saved_offline)))
        val pills = LinearLayout(this).apply { setPadding(dp(14), dp(4), dp(14), dp(6)) }
        listOf(getString(R.string.o_videos), getString(R.string.o_songs), getString(R.string.o_downloading)).forEachIndexed { i, n ->
            pills.addView(PlayUi.pill(this, n, offlineTab == i) { offlineTab = i; render() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
        headerHolder.addView(pills)
        if (offlineTab == 2) {
            body.addView(PlayUi.empty(this, getString(R.string.o_queue_hint)))
            body.addView(PlayUi.pill(this, getString(R.string.o_open_queue), true) {
                startActivity(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra("destination", "Queue").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }, LinearLayout.LayoutParams(-2, -2).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL })
            return
        }
        lifecycleScope.launch {
            val refs = withContext(Dispatchers.IO) {
                DBManager.getInstance(this@LibraryListActivity).historyDao.getAllHistoryList()
                    .filter { h -> h.downloadPath.any { p -> p.isNotBlank() && (p.startsWith("content://") || File(p).exists()) } }
                    .sortedByDescending { it.time }
                    .map { MediaRef(it.url, it.title, it.author, it.thumb, it.duration, it.type == DownloadType.audio) }
            }
            val list = refs.filter { if (offlineTab == 0) !it.audio else it.audio }
            body.removeAllViews()
            if (list.isEmpty()) { body.addView(PlayUi.empty(this@LibraryListActivity, getString(R.string.o_offline_empty))); return@launch }
            body.addView(PlayUi.grid(this@LibraryListActivity, list.map { r -> PlayUi.poster(this@LibraryListActivity, r, getString(R.string.o_offline_badge)) { open(r, list) } }))
        }
    }

    private fun playlists() {
        headerHolder.addView(PlayUi.header(this, getString(R.string.play_playlists), PlayUi.pill(this, getString(R.string.o_new), true) { newPlaylistDialog() }))
        val names = PlayStore.playlistNames(this)
        if (names.isEmpty()) { body.addView(PlayUi.empty(this, getString(R.string.o_pl_empty))); return }
        val cells = names.map { n ->
            val items = PlayStore.playlist(this, n)
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val mos = LinearLayout(this@LibraryListActivity).apply { orientation = LinearLayout.VERTICAL }
                val four = (items + List(4) { MediaRef("", "") }).take(4)
                for (r in four.chunked(2)) mos.addView(LinearLayout(this@LibraryListActivity).apply {
                    r.forEach { x -> addView(PlayUi.thumb(this@LibraryListActivity, x, 10, 10, 0).apply { layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f) }) }
                })
                addView(mos, LinearLayout.LayoutParams(-1, -2).also { })
                addView(TextView(this@LibraryListActivity).apply { text = n; textSize = 13f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(OrbitColors.ink(this@LibraryListActivity)); setPadding(0, dp(6), 0, 0) })
                addView(TextView(this@LibraryListActivity).apply { text = "${items.size} items"; textSize = 11f; setTextColor(OrbitColors.inkSoft(this@LibraryListActivity)) })
                setOnClickListener { openedPlaylist = n; render() }
                setOnLongClickListener {
                    AlertDialog.Builder(this@LibraryListActivity).setTitle(n).setPositiveButton(getString(R.string.play_delete_playlist)) { _, _ -> PlayStore.deletePlaylist(this@LibraryListActivity, n); render() }.setNegativeButton(android.R.string.cancel, null).show(); true
                }
            }
        }
        body.addView(PlayUi.grid(this, cells))
    }

    private fun playlistDetail(name: String) {
        headerHolder.addView(PlayUi.header(this, name))
        val list = PlayStore.playlist(this, name)
        if (list.isEmpty()) { body.addView(PlayUi.empty(this, getString(R.string.o_pl_inner_empty))); return }
        list.forEach { r ->
            val rm = android.widget.ImageButton(this).apply {
                setImageResource(R.drawable.ic_mini_close); setBackgroundColor(android.graphics.Color.TRANSPARENT); contentDescription = getString(R.string.o_remove)
                setOnClickListener { PlayStore.removeFromPlaylist(this@LibraryListActivity, name, r.url); render() }
            }
            body.addView(PlayUi.row(this, r, rm) { open(r, list) })
        }
        val play = LinearLayout(this).apply { setPadding(dp(14), dp(10), dp(14), dp(24)) }
        play.addView(PlayUi.pill(this, getString(R.string.o_play_all), true) { open(list.first(), list) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        play.addView(PlayUi.pill(this, getString(R.string.play_shuffle), false) { val s = list.shuffled(); open(s.first(), s) }, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(play)
    }

    private fun newPlaylistDialog() {
        val et = android.widget.EditText(this)
        AlertDialog.Builder(this).setTitle(getString(R.string.play_new_playlist)).setView(et).setPositiveButton(android.R.string.ok) { _, _ ->
            val n = et.text.toString().trim()
            if (n.isEmpty()) Toast.makeText(this, getString(R.string.o_name_empty), Toast.LENGTH_SHORT).show() else { PlayStore.createPlaylist(this, n); render() }
        }.setNegativeButton(android.R.string.cancel, null).show()
    }
}

class RecentsActivity : LibraryListActivity() { override val mode = "recents" }
class FavoritesActivity : LibraryListActivity() { override val mode = "favorites" }
class OfflineActivity : LibraryListActivity() { override val mode = "offline" }
class PlaylistsActivity : LibraryListActivity() { override val mode = "playlists" }
