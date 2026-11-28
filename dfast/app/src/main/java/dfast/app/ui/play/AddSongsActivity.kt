package dfast.app.ui.play

import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import dfast.app.R
import dfast.app.database.DBManager
import dfast.app.database.enums.DownloadType
import dfast.app.services.PlaybackService
import dfast.app.util.MediaRef
import dfast.app.util.OrbitColors
import dfast.app.util.PlayQueue
import dfast.app.util.PlayStore
import dfast.app.util.ThemeUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.File

/** Add songs to the play queue: Search, History, Favorites and Offline tabs, with Undo. Needs testing on a device. */
class AddSongsActivity : AppCompatActivity() {
    private lateinit var tabs: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var undoBar: TextView
    private var tab = 0
    private var lastAdded = 0
    private var query = ""
    private val addedAt = ArrayList<Int>()

    private fun dp(n: Int) = PlayUi.dp(this, n)

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtil.updateTheme(this)
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.bg_orbit) }
        root.addView(PlayUi.header(this, getString(R.string.o_add_songs)))
        tabs = LinearLayout(this).apply { setPadding(dp(14), dp(4), dp(14), dp(6)) }
        root.addView(tabs)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        undoBar = TextView(this).apply {
            textSize = 14f; gravity = Gravity.CENTER; setTextColor(0xFF14141C.toInt()); visibility = android.view.View.GONE
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 1000f; setColor(0xFF9D86FF.toInt()) }
            setOnClickListener { undoLast() }
        }
        root.addView(undoBar, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(6), dp(16), dp(12)) })
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars()); v.setPadding(0, b.top, 0, b.bottom); insets
        }
        render()
    }

    private fun render() {
        tabs.removeAllViews(); body.removeAllViews()
        listOf(R.string.o_tab_search, R.string.o_tab_history, R.string.o_tab_favorites, R.string.o_tab_offline).forEachIndexed { i, r ->
            tabs.addView(PlayUi.pill(this, getString(r), tab == i) { tab = i; render() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
        when (tab) {
            0 -> searchTab()
            1 -> list(PlayStore.recents(this).map { it to null })
            2 -> list(PlayStore.favorites(this).map { it to null })
            else -> offlineTab()
        }
    }

    private fun addBtn(ref: MediaRef, path: String?) = android.widget.ImageView(this).apply {
        setImageResource(R.drawable.ic_o_add); setColorFilter(OrbitColors.icon(context)); setPadding(dp(10), dp(10), dp(10), dp(10))
        setOnClickListener {
            val p = PlaybackService.player
            val before = p?.mediaItemCount ?: 0
            if (path != null) PlayQueue.addLocal(this@AddSongsActivity, ref, path) else PlayQueue.add(this@AddSongsActivity, ref, false)
            setImageResource(R.drawable.ic_o_check)
            if (before > 0) { addedAt.add(before); undoBar.text = getString(R.string.o_added_short) + "   ·   " + getString(R.string.o_undo); undoBar.visibility = android.view.View.VISIBLE }
        }
    }

    private fun undoLast() {
        val p = PlaybackService.player ?: return
        val idx = addedAt.removeLastOrNull() ?: return
        if (idx < p.mediaItemCount) p.removeMediaItem(idx)
        // later additions shift down by one
        for (k in addedAt.indices) if (addedAt[k] > idx) addedAt[k] = addedAt[k] - 1
        if (addedAt.isEmpty()) undoBar.visibility = android.view.View.GONE
        render()
    }

    private fun list(items: List<Pair<MediaRef, String?>>) {
        if (items.isEmpty()) { body.addView(PlayUi.empty(this, getString(R.string.o_no_items))); return }
        items.forEach { (r, path) -> body.addView(PlayUi.row(this, r, addBtn(r, path)) { PlayQueue.menu(this, r) }) }
    }

    private fun offlineTab() {
        lifecycleScope.launch {
            val privDir = File(filesDir, "offline").absolutePath
            val items = withContext(Dispatchers.IO) {
                DBManager.getInstance(this@AddSongsActivity).historyDao.getAllHistoryList().sortedByDescending { it.time }.mapNotNull { h ->
                    val p = h.downloadPath.firstOrNull { it.startsWith(privDir) && File(it).exists() } ?: return@mapNotNull null
                    MediaRef(h.url, h.title, h.author, h.thumb, h.duration, h.type == DownloadType.audio) to p
                }
            }
            if (tab != 3) return@launch
            body.removeAllViews(); list(items)
        }
    }

    private fun searchTab() {
        val box = EditText(this).apply {
            hint = getString(R.string.o_search_songs); setText(query); maxLines = 1; inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH; setTextColor(OrbitColors.ink(context)); setHintTextColor(OrbitColors.inkSoft(context))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 1000f; setColor(OrbitColors.glass2(context)) }
        }
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.setOnEditorActionListener { _, a, _ ->
            if (a == EditorInfo.IME_ACTION_SEARCH) { query = box.text.toString().trim(); runSearch(results); true } else false
        }
        body.addView(box, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(14), dp(4), dp(14), dp(8)) })
        body.addView(results)
        if (query.isNotEmpty()) runSearch(results)
    }

    private fun runSearch(out: LinearLayout) {
        if (query.isEmpty()) return
        out.removeAllViews()
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching {
                    dfast.app.util.extractors.newpipe.NewPipeUtil(this@AddSongsActivity)
                    val svc = NewPipe.getService(ServiceList.YouTube.serviceId)
                    SearchInfo.getInfo(svc, svc.searchQHFactory.fromQuery(query, listOf(org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory.ALL), "")).relatedItems.filterIsInstance<StreamInfoItem>().take(25).map {
                        val id = it.url.substringAfter("v=", "").substringBefore("&")
                        val th = if (id.isNotEmpty()) "https://i.ytimg.com/vi/$id/hqdefault.jpg" else it.thumbnails.lastOrNull()?.url.orEmpty()
                        MediaRef(it.url, it.name, it.uploaderName.removeSuffix(" - Topic"), th)
                    }
                }.getOrDefault(emptyList())
            }
            if (tab != 0) return@launch
            if (found.isEmpty()) out.addView(PlayUi.empty(this@AddSongsActivity, getString(R.string.o_no_items)))
            found.forEach { r -> out.addView(PlayUi.row(this@AddSongsActivity, r, addBtn(r, null)) { PlayQueue.menu(this@AddSongsActivity, r) }) }
        }
    }
}
