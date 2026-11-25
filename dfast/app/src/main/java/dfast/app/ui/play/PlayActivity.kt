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

        val tabs = LinearLayout(this).apply { setPadding(dp(16), dp(4), dp(16), dp(8)) }
        listOf(getString(R.string.audio), getString(R.string.video), getString(R.string.play_favorites)).forEachIndexed { i, name ->
            val tv = TextView(this).apply {
                text = name; textSize = 13f; gravity = Gravity.CENTER
                setPadding(dp(16), dp(8), dp(16), dp(8)); setOnClickListener { select(i) }
            }
            tabViews.add(tv)
            tabs.addView(tv, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
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
            else -> all.filter { f.contains(it.item.id.toString()) }
        }
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        list.adapter = Adapter()
    }

    private fun playQueue(shuffle: Boolean = false, start: Int = 0) {
        val audio = shown.filter { it.item.type == DownloadType.audio }
        if (audio.isEmpty()) { Toast.makeText(this, R.string.play_no_audio, Toast.LENGTH_SHORT).show(); return }
        PlaybackService.playQueue(this, audio.map { PlaybackService.Companion.QueueItem(it.uri, it.item.title, it.item.author, it.item.thumb) },
            if (shuffle) kotlin.random.Random.nextInt(audio.size) else start, shuffle)
    }

    private fun open(pos: Int) {
        val e = shown[pos]
        if (e.item.type == DownloadType.video) {
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
        }
    }
}
