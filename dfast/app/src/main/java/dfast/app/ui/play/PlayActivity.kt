package dfast.app.ui.play

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dfast.app.R
import dfast.app.database.DBManager
import dfast.app.database.enums.DownloadType
import dfast.app.database.models.HistoryItem
import dfast.app.services.PlaybackService
import dfast.app.util.Extensions.loadThumbnail
import dfast.app.util.OrbitColors
import dfast.app.util.ThemeUtil
import dfast.app.util.VideoPreviewDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Play: everything downloaded, split into Audio, Video and Favorites. */
class PlayActivity : AppCompatActivity() {
    private data class Entry(val item: HistoryItem, val uri: String)

    private var all: List<Entry> = emptyList()
    private var shown: List<Entry> = emptyList()
    private var tab = 0
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private val tabViews = mutableListOf<TextView>()
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun favs(): Set<String> = prefs.getStringSet("play_favorites", emptySet()) ?: emptySet()

    private var openList: String? = null
    private fun playlists(): org.json.JSONObject = try { org.json.JSONObject(prefs.getString("play_playlists", "{}") ?: "{}") } catch (e: Exception) { org.json.JSONObject() }
    private fun savePlaylists(o: org.json.JSONObject) = prefs.edit { putString("play_playlists", o.toString()) }
    private fun recent(): List<String> = (prefs.getString("play_recent", "") ?: "").split(",").filter { it.isNotBlank() }
    private fun markRecent(id: Long) {
        val l = recent().toMutableList(); l.remove(id.toString()); l.add(0, id.toString())
        prefs.edit { putString("play_recent", l.take(40).joinToString(",")) }
    }
    private fun idsOf(name: String): List<String> {
        val a = playlists().optJSONArray(name) ?: return emptyList()
        return (0 until a.length()).map { a.optString(it) }
    }
    private fun addToPlaylist(item: HistoryItem) {
        val pl = playlists()
        val names = pl.keys().asSequence().toList()
        val labels = (names + getString(R.string.play_new_playlist)).toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle(R.string.play_add_to_playlist).setItems(labels) { _, which ->
            fun add(n: String) {
                val a = pl.optJSONArray(n) ?: org.json.JSONArray().also { pl.put(n, it) }
                val ids = (0 until a.length()).map { a.optString(it) }
                if (!ids.contains(item.id.toString())) a.put(item.id.toString())
                savePlaylists(pl)
                Toast.makeText(this, n, Toast.LENGTH_SHORT).show()
            }
            if (which < names.size) add(names[which]) else {
                val et = android.widget.EditText(this)
                androidx.appcompat.app.AlertDialog.Builder(this).setTitle(R.string.play_new_playlist).setView(et)
                    .setPositiveButton(android.R.string.ok) { _, _ -> val n = et.text.toString().trim(); if (n.isNotEmpty()) add(n) }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
        }.show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtil.updateTheme(this)
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.bg_orbit) }

        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(6)) }
        head.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_mini_close); setColorFilter(OrbitColors.ink(this@PlayActivity))
            setBackgroundColor(Color.TRANSPARENT); contentDescription = "Close"; setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        head.addView(TextView(this).apply {
            text = getString(R.string.play_title); textSize = 22f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(OrbitColors.ink(this@PlayActivity))
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        head.addView(pillButton(getString(R.string.play_shuffle), true) { playQueue(shuffle = true) })
        root.addView(head)

        val tabs = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val tabsRow = LinearLayout(this).apply { setPadding(dp(16), dp(4), dp(16), dp(8)) }
        listOf(getString(R.string.audio), getString(R.string.video), getString(R.string.play_favorites), getString(R.string.play_recent), getString(R.string.play_playlists)).forEachIndexed { i, name ->
            val tv = TextView(this).apply {
                text = name; textSize = 13f; gravity = Gravity.CENTER
                setPadding(dp(16), dp(8), dp(16), dp(8)); setOnClickListener { select(i) }
            }
            tabViews.add(tv)
            tabsRow.addView(tv, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
        tabs.addView(tabsRow)
        root.addView(tabs)

        list = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@PlayActivity); clipToPadding = false; setPadding(dp(10), 0, dp(10), dp(24)) }
        empty = TextView(this).apply {
            text = getString(R.string.play_empty); gravity = Gravity.CENTER; setTextColor(OrbitColors.inkSoft(this@PlayActivity)); visibility = View.GONE
        }
        val frame = android.widget.FrameLayout(this)
        frame.addView(list, android.widget.FrameLayout.LayoutParams(-1, -1))
        frame.addView(empty, android.widget.FrameLayout.LayoutParams(-1, -1))
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars()); v.setPadding(0, b.top, 0, b.bottom); insets
        }
        select(0)
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) {
                DBManager.getInstance(this@PlayActivity).historyDao.getAllHistoryList().mapNotNull { h ->
                    val path = h.downloadPath.firstOrNull { p -> p.isNotBlank() && (p.startsWith("content://") || File(p).exists()) } ?: return@mapNotNull null
                    Entry(h, if (path.startsWith("content://")) path else Uri.fromFile(File(path)).toString())
                }.sortedByDescending { it.item.time }
            }
            all = entries
            select(tab)
        }
    }

    private fun pillButton(text: String, filled: Boolean, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 13f; gravity = Gravity.CENTER
        setTextColor(if (filled) OrbitColors.pillText(this@PlayActivity) else OrbitColors.ink(this@PlayActivity))
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = GradientDrawable().apply { cornerRadius = 1000f; setColor(if (filled) OrbitColors.pill(this@PlayActivity) else OrbitColors.glass2(this@PlayActivity)) }
        setOnClickListener { onClick() }
    }

    private fun select(i: Int) {
        if (i != tab || i != 4) openList = null
        tab = i
        tabViews.forEachIndexed { idx, tv ->
            val on = idx == i
            tv.setTextColor(if (on) OrbitColors.pillText(this) else OrbitColors.ink(this))
            tv.background = GradientDrawable().apply { cornerRadius = 1000f; setColor(if (on) OrbitColors.pill(this@PlayActivity) else OrbitColors.glass2(this@PlayActivity)) }
        }
        val f = favs()
        shown = when (i) {
            0 -> all.filter { it.item.type == DownloadType.audio }
            1 -> all.filter { it.item.type == DownloadType.video }
            2 -> all.filter { f.contains(it.item.id.toString()) }
            3 -> recent().mapNotNull { r -> all.firstOrNull { it.item.id.toString() == r } }
            else -> {
                val o = openList
                if (o == null) emptyList() else idsOf(o).mapNotNull { r -> all.firstOrNull { it.item.id.toString() == r } }
            }
        }
        if (i == 4 && openList == null) {
            val names = playlists().keys().asSequence().toList()
            empty.visibility = if (names.isEmpty()) View.VISIBLE else View.GONE
            list.adapter = PlaylistAdapter(names)
            return
        }
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        list.adapter = Adapter()
    }

    private fun playQueue(shuffle: Boolean = false, start: Int = 0) {
        val audio = shown.filter { it.item.type == DownloadType.audio }
        audio.getOrNull(start)?.let { markRecent(it.item.id) }
        if (audio.isEmpty()) { Toast.makeText(this, R.string.play_no_audio, Toast.LENGTH_SHORT).show(); return }
        PlaybackService.playQueue(this, audio.map { PlaybackService.Companion.QueueItem(it.uri, it.item.title, it.item.author, it.item.thumb) },
            if (shuffle) kotlin.random.Random.nextInt(audio.size) else start, shuffle)
    }

    private fun open(pos: Int) {
        val e = shown[pos]
        if (e.item.type == DownloadType.video) {
            markRecent(e.item.id)
            VideoPreviewDialog.show(this, Uri.parse(e.uri), e.item.title)
        } else {
            val audio = shown.filter { it.item.type == DownloadType.audio }
            playQueue(false, audio.indexOf(e).coerceAtLeast(0))
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(val row: LinearLayout, val thumb: ImageView, val title: TextView, val author: TextView, val fav: ImageButton) : RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val row = LinearLayout(parent.context).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(8), dp(6), dp(8))
                background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(OrbitColors.get(context, R.attr.orbitGlass, 0x26FFFFFF)) }
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(0, dp(4), 0, dp(4)) }
            }
            val thumb = ImageView(parent.context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(OrbitColors.glass2(context)) }
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            }
            row.addView(thumb, LinearLayout.LayoutParams(dp(52), dp(52)))
            val col = LinearLayout(parent.context).apply { orientation = LinearLayout.VERTICAL }
            val title = TextView(parent.context).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; textSize = 14f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(OrbitColors.ink(context)) }
            val author = TextView(parent.context).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; textSize = 12f; setTextColor(OrbitColors.inkSoft(context)) }
            col.addView(title); col.addView(author)
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12); marginEnd = dp(8) })
            val fav = ImageButton(parent.context).apply { setBackgroundColor(Color.TRANSPARENT); contentDescription = "Favorite" }
            row.addView(fav, LinearLayout.LayoutParams(dp(44), dp(44)))
            return VH(row, thumb, title, author, fav)
        }

        override fun getItemCount() = shown.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val e = shown[position]
            h.title.text = e.item.title.ifBlank { e.item.url }
            h.author.text = e.item.author
            h.thumb.loadThumbnail(false, e.item.thumb)
            fun paint() {
                val on = favs().contains(e.item.id.toString())
                h.fav.setImageResource(if (on) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline)
                h.fav.setColorFilter(OrbitColors.ink(h.row.context))
            }
            paint()
            h.fav.setOnClickListener {
                val id = e.item.id.toString()
                val set = favs().toMutableSet()
                if (!set.add(id)) set.remove(id)
                prefs.edit { putStringSet("play_favorites", set) }
                if (tab == 2) select(2) else paint()
            }
            h.row.setOnClickListener { open(h.bindingAdapterPosition) }
            h.row.setOnLongClickListener {
                val inList = tab == 4 && openList != null
                val opts = if (inList) arrayOf(getString(R.string.play_remove_from_playlist)) else arrayOf(getString(R.string.play_add_to_playlist))
                androidx.appcompat.app.AlertDialog.Builder(h.row.context).setTitle(e.item.title).setItems(opts) { _, _ ->
                    if (inList) {
                        val pl = playlists(); val n = openList!!
                        val ids = idsOf(n).filter { it != e.item.id.toString() }
                        pl.put(n, org.json.JSONArray(ids)); savePlaylists(pl); select(4)
                    } else addToPlaylist(e.item)
                }.show()
                true
            }
        }
    }

    private inner class PlaylistAdapter(val names: List<String>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val tv = TextView(parent.context).apply {
                textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(OrbitColors.ink(context))
                setPadding(dp(20), dp(18), dp(20), dp(18))
                background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(OrbitColors.get(context, R.attr.orbitGlass, 0x26FFFFFF)) }
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(0, dp(4), 0, dp(4)) }
            }
            return object : RecyclerView.ViewHolder(tv) {}
        }
        override fun getItemCount() = names.size
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val n = names[position]
            (h.itemView as TextView).text = n + "  (" + idsOf(n).size + ")"
            h.itemView.setOnClickListener { openList = n; select(4) }
            h.itemView.setOnLongClickListener {
                androidx.appcompat.app.AlertDialog.Builder(this@PlayActivity).setTitle(n)
                    .setPositiveButton(R.string.play_delete_playlist) { _, _ -> val pl = playlists(); pl.remove(n); savePlaylists(pl); select(4) }
                    .setNegativeButton(android.R.string.cancel, null).show()
                true
            }
        }
    }

    private fun select4Open() {
        val o = openList ?: return
        shown = idsOf(o).mapNotNull { r -> all.firstOrNull { it.item.id.toString() == r } }
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        list.adapter = Adapter()
    }

    override fun onBackPressed() {
        if (tab == 4 && openList != null) { openList = null; select(4) } else super.onBackPressed()
    }
}
