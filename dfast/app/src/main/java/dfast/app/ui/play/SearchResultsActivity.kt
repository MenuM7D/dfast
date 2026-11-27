package dfast.app.ui.play

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.squareup.picasso.Picasso
import dfast.app.R
import dfast.app.database.models.ResultItem
import dfast.app.receiver.ShareActivity
import dfast.app.util.GoogleAccount
import dfast.app.util.MediaRef
import dfast.app.util.OrbitColors
import dfast.app.util.ThemeUtil
import dfast.app.util.YtAccountApi
import dfast.app.util.extractors.newpipe.NewPipeUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * Search results with chips: All, Playlists, Channels, Google and the platform chips.
 * Platform chips show Google results for "site:<platform> <query>" inside the app.
 * Also opens one channel (videos list) when started with a channel url.
 */
class SearchResultsActivity : AppCompatActivity() {
    private lateinit var queryBox: EditText
    private lateinit var chipRow: LinearLayout
    private lateinit var chipScroll: HorizontalScrollView
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var web: WebView
    private lateinit var webHolder: FrameLayout
    private lateinit var banner: LinearLayout
    private lateinit var bannerText: TextView
    private lateinit var dlFab: TextView
    private var query = ""
    private var chip = "all"
    private var channelUrl: String? = null
    private var job = 0
    private var playlistUrl: String? = null
    private var selecting = false
    private val selected = LinkedHashSet<String>()
    private var lastItems: List<InfoItem> = emptyList()
    /** ids of videos already downloaded or saved on this phone (a check mark shows on their rows) */
    private var downloadedIds: Set<String> = emptySet()
    private var lastStreams: List<StreamInfoItem> = emptyList()
    private var selLabel: TextView? = null
    private var mini: dfast.app.util.MiniPlayer? = null

    private fun dp(n: Int) = PlayUi.dp(this, n)
    private val platformSites = listOf(
        Triple("TikTok", "tiktok.com", "https://www.tiktok.com/login"),
        Triple("Facebook", "facebook.com", "https://m.facebook.com/login"),
        Triple("Instagram", "instagram.com", "https://www.instagram.com/accounts/login/")
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtil.updateTheme(this)
        super.onCreate(savedInstanceState)
        query = intent.getStringExtra("query") ?: ""
        channelUrl = intent.getStringExtra("channel_url")
        playlistUrl = intent.getStringExtra("playlist_url")
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.bg_orbit); isFocusableInTouchMode = true }

        // search bar
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(14), 0)
            background = GradientDrawable().apply { cornerRadius = dp(26).toFloat(); setColor(OrbitColors.glass2(this@SearchResultsActivity)) }
        }
        bar.addView(dfast.app.util.OIcons.view(this, R.drawable.ic_o_arrow_back, 22, OrbitColors.ink(this), 8) { finish() })
        queryBox = EditText(this).apply {
            setText(if (channelUrl != null || playlistUrl != null) (intent.getStringExtra("channel_name") ?: "") else query)
            setTextColor(OrbitColors.ink(context)); setHintTextColor(OrbitColors.inkSoft(context))
            background = null; maxLines = 1; isSingleLine = true; textSize = 15f
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            isEnabled = channelUrl == null && playlistUrl == null
            setOnEditorActionListener { _, _, _ ->
                val q = text.toString().trim()
                // every submit closes the keyboard and drops the cursor (it used to stay open on the 2nd search)
                runCatching {
                    (getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(windowToken, 0)
                    clearFocus(); root.requestFocus() // root takes the focus so the field does not grab it back
                }
                if (q.isNotEmpty()) {
                    query = q; load()
                    val app = applicationContext
                    lifecycleScope.launch(Dispatchers.IO) { runCatching { dfast.app.database.DBManager.getInstance(app).searchHistoryDao.insert(dfast.app.database.models.SearchHistoryItem(0, q)) } }
                }
                true
            }
        }
        bar.addView(queryBox, LinearLayout.LayoutParams(0, dp(48), 1f))
        bar.addView(dfast.app.util.OIcons.view(this, R.drawable.ic_o_close, 18, OrbitColors.inkSoft(this), 8) { queryBox.setText(""); queryBox.requestFocus() }.apply {
            visibility = if (channelUrl == null && playlistUrl == null) View.VISIBLE else View.GONE
        })
        root.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(8), dp(12), dp(4)) })

        // chips
        chipRow = LinearLayout(this).apply { setPadding(dp(12), dp(6), dp(12), dp(6)) }
        chipScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chipRow) }
        root.addView(chipScroll)

        // sign-in banner (platform chips)
        banner = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE; setPadding(dp(14), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(OrbitColors.glass2(this@SearchResultsActivity)) }
        }
        bannerText = TextView(this).apply { textSize = 12f; setTextColor(OrbitColors.ink(context)) }
        banner.addView(bannerText, LinearLayout.LayoutParams(0, -2, 1f))
        banner.addView(TextView(this).apply {
            text = getString(R.string.o_sign_in_short); textSize = 12f; setTextColor(0xFF14102E.toInt()); setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = GradientDrawable().apply { cornerRadius = 1000f; setColor(0xFF9D86FF.toInt()) }
            setOnClickListener { platformSites.firstOrNull { it.first.lowercase() == chip }?.let { web.loadUrl(it.third) } }
        })
        root.addView(banner, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(2), dp(12), dp(4)) })

        // content: list or web
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(24)) }
        scroll = ScrollView(this).apply { addView(list) }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    val u = r.url.toString()
                    return !(u.startsWith("http://") || u.startsWith("https://"))
                }
            }
        }
        dlFab = TextView(this).apply {
            text = getString(R.string.o_download_video); textSize = 14f
            setTextColor(0xFF14102E.toInt()); setTypeface(typeface, android.graphics.Typeface.BOLD)
            dfast.app.util.OIcons.start(this, R.drawable.ic_o_download, 0xFF14102E.toInt())
            setPadding(dp(20), dp(13), dp(20), dp(13))

            background = GradientDrawable().apply { cornerRadius = 1000f; setColor(0xFF9D86FF.toInt()) }
            elevation = dp(6).toFloat()
            setOnClickListener {
                val u = web.url ?: ""
                if (u.isEmpty() || u.contains("google.")) Toast.makeText(context, getString(R.string.o_open_result_first), Toast.LENGTH_LONG).show()
                else sendToDownload(u)
            }
        }
        webHolder = FrameLayout(this).apply {
            visibility = View.GONE
            addView(web, FrameLayout.LayoutParams(-1, -1))
            addView(dlFab, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(24) })
        }
        val content = FrameLayout(this).apply { addView(scroll, FrameLayout.LayoutParams(-1, -1)); addView(webHolder, FrameLayout.LayoutParams(-1, -1)) }
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        mini = dfast.app.util.MiniPlayer(this)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars()); v.setPadding(0, b.top, 0, b.bottom); insets
        }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webHolder.visibility == View.VISIBLE && web.canGoBack()) web.goBack() else finish()
            }
        })
        buildChips()
        load()
    }

    override fun onDestroy() { runCatching { web.destroy() }; mini?.release(); mini = null; super.onDestroy() }

    private fun sendToDownload(url: String, ref: MediaRef? = null) {
        runCatching {
            startActivity(Intent(this, ShareActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra("inapp", true).putExtra(Intent.EXTRA_TEXT, url)
                .apply { if (ref != null) { putExtra("pre_title", ref.title); putExtra("pre_author", ref.author); putExtra("pre_thumb", ref.thumb); putExtra("pre_duration", ref.duration); putExtra("TYPE", if (ref.audio) "audio" else "video") } })
        }
    }

    private fun buildChips() {
        chipRow.removeAllViews()
        if (channelUrl != null || playlistUrl != null) { chipScroll.visibility = View.GONE; return }
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val all = arrayListOf("all" to getString(R.string.o_chip_all), "videos" to getString(R.string.o_videos), "shorts" to getString(R.string.o_chip_shorts),
            "live" to getString(R.string.o_topic_live), "recent" to getString(R.string.o_chip_recent), "playlists" to getString(R.string.o_chip_playlists),
            "channels" to getString(R.string.o_chip_channels), "google" to "Google")
        platformSites.forEach { if (prefs.getBoolean("platform_${it.first}", true)) all.add(it.first.lowercase() to it.first) }
        all.forEach { (k, label) ->
            val on = k == chip
            chipRow.addView(TextView(this).apply {
                text = label; textSize = 13f; setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(if (on) 0xFF14102E.toInt() else OrbitColors.ink(context))
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = GradientDrawable().apply { cornerRadius = 1000f; setColor(if (on) 0xFF9D86FF.toInt() else OrbitColors.glass2(context)) }
                setOnClickListener { chip = k; buildChips(); load() }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
    }

    private fun isWebChip() = chip == "google" || platformSites.any { it.first.lowercase() == chip }

    private fun load() {
        if (channelUrl != null) { startChannel(); return }
        val my = ++job
        if (channelUrl == null && playlistUrl == null && isWebChip()) { showWeb(); return }
        webHolder.visibility = View.GONE; banner.visibility = View.GONE; scroll.visibility = View.VISIBLE
        list.removeAllViews()
        list.addView(TextView(this).apply { text = "…"; textSize = 22f; setTextColor(OrbitColors.inkSoft(context)); gravity = Gravity.CENTER; setPadding(0, dp(40), 0, 0) })
        lifecycleScope.launch {
            val items: List<InfoItem> = withContext(Dispatchers.IO) { runCatching { fetch() }.getOrDefault(emptyList()) }
            val subIds = if (GoogleAccount.isSignedIn(this@SearchResultsActivity) && chip != "playlists")
                withContext(Dispatchers.IO) { runCatching { YtAccountApi.listSubscriptions(this@SearchResultsActivity) }.getOrDefault(emptyList()) } else emptyList()
            downloadedIds = withContext(Dispatchers.IO) {
                runCatching { dfast.app.database.DBManager.getInstance(this@SearchResultsActivity).historyDao.getAllHistoryList().map { dfast.app.util.UrlIds.of(it.url) }.filter { it.isNotEmpty() }.toHashSet() }.getOrDefault(emptySet<String>())
            }
            if (my != job) return@launch
            render(items)
        }
    }

    private fun showWeb() {
        scroll.visibility = View.GONE; webHolder.visibility = View.VISIBLE
        val site = platformSites.firstOrNull { it.first.lowercase() == chip }
        val q = (if (site != null) "site:${site.second} " else "") + query
        val lang = java.util.Locale.getDefault().language
        web.loadUrl("https://www.google.com/search?q=" + java.net.URLEncoder.encode(q, "UTF-8") + "&tbm=vid&hl=" + lang)
        if (site != null) { banner.visibility = View.VISIBLE; bannerText.text = getString(R.string.o_site_signin, site.first) } else banner.visibility = View.GONE
    }

    private fun fetch(): List<InfoItem> {
        NewPipeUtil(this)
        val svc = NewPipe.getService(ServiceList.YouTube.serviceId)
        playlistUrl?.let { pu ->
            val anon = runCatching { org.schabi.newpipe.extractor.playlist.PlaylistInfo.getInfo(ServiceList.YouTube, pu).relatedItems.filterIsInstance<StreamInfoItem>() }.getOrDefault(emptyList())
            if (anon.isNotEmpty()) return anon
            // a playlist from the user's own library is often private: the signed-out extractor sees nothing, so ask YouTube with the signed-in account
            val id = pu.substringAfter("list=", "").substringBefore("&")
            if (id.isNotEmpty() && GoogleAccount.isSignedIn(this)) {
                return YtAccountApi.videos(this, "VL$id").map { r ->
                    StreamInfoItem(ServiceList.YouTube.serviceId, r.url, r.title, org.schabi.newpipe.extractor.stream.StreamType.VIDEO_STREAM).also {
                        it.uploaderName = r.author
                        it.duration = r.duration.split(":").mapNotNull { x -> x.toLongOrNull() }.fold(0L) { a, x -> a * 60 + x }
                    }
                }
            }
            return anon
        }
        val cu = channelUrl
        if (cu != null) {
            val info = ChannelInfo.getInfo(ServiceList.YouTube, cu)
            val tab = info.tabs.firstOrNull { it.contentFilters.firstOrNull() == "videos" } ?: info.tabs.firstOrNull() ?: return emptyList()
            return ChannelTabInfo.getInfo(ServiceList.YouTube, tab).relatedItems.filterIsInstance<StreamInfoItem>()
        }
        val kind = dfast.app.util.SearchFilter.kindOf(chip)
        val filter = when (chip) {
            "playlists" -> YoutubeSearchQueryHandlerFactory.PLAYLISTS
            "channels" -> YoutubeSearchQueryHandlerFactory.CHANNELS
            "videos", "recent" -> YoutubeSearchQueryHandlerFactory.VIDEOS
            else -> YoutubeSearchQueryHandlerFactory.ALL
        }
        val res = SearchInfo.getInfo(svc, svc.searchQHFactory.fromQuery(query + dfast.app.util.SearchFilter.querySuffix(kind), listOf(filter), ""))
        return res.relatedItems
    }

    private fun fmtDur(sec: Long): String {
        if (sec <= 0) return ""
        val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
    private fun compact(n: Long): String = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0).replace(".0M", "M")
        n >= 1_000 -> "%.1fK".format(n / 1_000.0).replace(".0K", "K")
        else -> n.toString()
    }
    private fun fixUrl(u: String?): String = when { u == null -> ""; u.startsWith("//") -> "https:$u"; else -> u }

    private fun render(items: List<InfoItem>) {
        list.removeAllViews()
        if (items.isEmpty()) {
            list.addView(TextView(this).apply { text = getString(R.string.o_no_results); textSize = 14f; setTextColor(OrbitColors.inkSoft(context)); gravity = Gravity.CENTER; setPadding(0, dp(40), 0, 0) })
            return
        }
        val kind = dfast.app.util.SearchFilter.kindOf(chip)
        val now = System.currentTimeMillis()
        val isLive = { it: StreamInfoItem -> it.streamType == org.schabi.newpipe.extractor.stream.StreamType.LIVE_STREAM || it.streamType == org.schabi.newpipe.extractor.stream.StreamType.AUDIO_LIVE_STREAM }
        val upMs = { it: StreamInfoItem -> runCatching { it.uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli() }.getOrNull() }
        var streams = items.filterIsInstance<StreamInfoItem>().filter {
            if (channelUrl != null || playlistUrl != null) true else dfast.app.util.SearchFilter.keep(kind, it.duration, isLive(it), upMs(it), now)
        }
        if (kind == dfast.app.util.SearchFilter.Kind.RECENT) streams = streams.sortedByDescending { upMs(it) ?: 0L }
        // the same song uploaded twice (re-uploads, "official"/"lyrics" copies) shows once; a channel or playlist list is shown as it is
        if (channelUrl == null && playlistUrl == null) streams = dfast.app.util.AutoplayPick.dedupe(streams) { it.name }
        lastItems = items; lastStreams = streams
        val queue = streams.map { MediaRef(it.url, it.name, it.uploaderName.removeSuffix(" - Topic"), thumbOf(it), fmtDur(it.duration)) }
        when (chip) {
            "playlists" -> if (playlistUrl == null) items.filterIsInstance<PlaylistInfoItem>().forEach { list.addView(playlistRow(it)) }
            "channels" -> items.filterIsInstance<ChannelInfoItem>().forEach { list.addView(channelRow(it)) }
            else -> {
                if (channelUrl == null && kind == dfast.app.util.SearchFilter.Kind.ALL) items.filterIsInstance<ChannelInfoItem>().firstOrNull()?.let { list.addView(channelCard(it)) }
                if (streams.isEmpty() && kind != dfast.app.util.SearchFilter.Kind.ALL) list.addView(TextView(this).apply { text = getString(R.string.o_no_results); textSize = 14f; setTextColor(OrbitColors.inkSoft(context)); gravity = Gravity.CENTER; setPadding(0, dp(40), 0, 0) })
                if (playlistUrl != null) list.addView(playlistBar())
                streams.forEachIndexed { i, s -> list.addView(videoRow(s, queue, i)) }
            }
        }
    }

    private fun thumbOf(s: StreamInfoItem): String {
        val id = s.url.substringAfter("v=", "").substringBefore("&")
        return if (id.isNotEmpty()) "https://i.ytimg.com/vi/$id/hqdefault.jpg" else fixUrl(s.thumbnails.lastOrNull()?.url)
    }

    private fun image(url: String, w: Int, h: Int, circle: Boolean): ImageView {
        val r = dp(10).toFloat()
        return ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { cornerRadius = if (circle) 1000f else r; setColor(OrbitColors.glass2(context)) }
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) { if (circle) o.setOval(0, 0, v.width, v.height) else o.setRoundRect(0, 0, v.width, v.height, r) }
            }
            if (url.isNotEmpty()) runCatching { Picasso.get().load(url).resize(w * 2, 0).onlyScaleDown().into(this) }
        }
    }

    private fun texts(title: String, sub: String, marked: Boolean = false): LinearLayout {
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(TextView(this).apply { text = title; textSize = 14f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(OrbitColors.ink(context)); setTypeface(typeface, android.graphics.Typeface.BOLD) })
        if (sub.isNotEmpty()) t.addView(TextView(this).apply { text = sub; textSize = 12f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(OrbitColors.inkSoft(context)); setPadding(0, dp(3), 0, 0)
            if (marked) {
                val d = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_o_check)?.mutate()
                d?.setBounds(0, 0, dp(13), dp(13)); d?.setTint(0xFF9D86FF.toInt())
                setCompoundDrawablesRelative(d, null, null, null); compoundDrawablePadding = dp(4)
            } })
        return t
    }

    /** Tap feedback: the arrow turns into a violet check for a moment, with a light haptic tick. */
    private fun dlButton(url: String, ref: MediaRef? = null): View {
        lateinit var b: android.widget.ImageView
        b = dfast.app.util.OIcons.view(this, R.drawable.ic_o_download, 22, OrbitColors.ink(this), 8) {
            b.setImageResource(R.drawable.ic_o_check); b.setColorFilter(0xFF9D86FF.toInt())
            b.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
            sendToDownload(url, ref)
            b.postDelayed({ b.setImageResource(R.drawable.ic_o_download); b.setColorFilter(OrbitColors.ink(this)) }, 2200)
        }
        return b
    }

    private fun headBtn(ref: MediaRef) = dfast.app.util.OIcons.view(this, R.drawable.ic_o_headphones, 22, OrbitColors.ink(this), 8) {
        dfast.app.util.PlayQueue.playNow(this@SearchResultsActivity, ref)
    }

    private fun moreBtn(ref: MediaRef, url: String) = dfast.app.util.OIcons.view(this, R.drawable.ic_o_more, 22, OrbitColors.inkSoft(this), 8) { rowMenu(ref, url) }

    private fun rowMenu(ref: MediaRef, url: String) = dfast.app.util.PlayQueue.menu(this, ref) { sendToDownload(url, ref) }

    private fun videoRow(s: StreamInfoItem, queue: List<MediaRef>, i: Int): View {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(7), dp(10), dp(7)) }
        val box = FrameLayout(this)
        box.addView(image(queue[i].thumb, 112, 63, false), FrameLayout.LayoutParams(dp(112), dp(63)))
        val d = fmtDur(s.duration)
        if (d.isNotEmpty()) box.addView(TextView(this).apply {
            text = d; textSize = 10f; setTextColor(0xFFFFFFFF.toInt()); setPadding(dp(5), dp(1), dp(5), dp(1))
            background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(0xB3000000.toInt()) }
        }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(4), dp(4)) })
        else if (isLiveItem(s)) box.addView(TextView(this).apply {
            text = getString(R.string.o_topic_live); textSize = 10f; setTextColor(0xFFFFFFFF.toInt()); setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(dp(5), dp(1), dp(5), dp(1))
            background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(0xFFE5384F.toInt()) }
        }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(4), dp(4)) })
        row.addView(box, LinearLayout.LayoutParams(dp(112), dp(63)))
        val sub = listOf(queue[i].author, if (s.viewCount > 0) compact(s.viewCount) else "").filter { it.isNotEmpty() }.joinToString(" · ")
        row.addView(texts(s.name, sub, dfast.app.util.UrlIds.of(s.url).let { it.isNotEmpty() && it in downloadedIds }), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
        if (playlistUrl != null && selecting) {
            val ck = dfast.app.util.OIcons.view(this, R.drawable.ic_o_checkbox, 24, 0, 6)
            fun paint() { ck.setColorFilter(if (selected.contains(s.url)) 0xFF9D86FF.toInt() else (OrbitColors.inkSoft(this) and 0x00FFFFFF) or 0x55000000) }
            paint(); row.addView(ck)
            row.setOnClickListener { if (!selected.add(s.url)) selected.remove(s.url); paint(); updateSel() }
            return row
        }
        row.addView(headBtn(queue[i]))
        row.addView(dlButton(s.url, queue[i]))
        row.addView(moreBtn(queue[i], s.url))
        row.setOnLongClickListener { rowMenu(queue[i], s.url); true }
        row.setOnClickListener {
            val ri = ResultItem(0, s.url, s.name, queue[i].author, queue[i].duration, queue[i].thumb, "youtube", "", emptyList(), "", null)
            startActivity(PlayerActivity.intent(this, queue[i], queue.drop(i + 1), ri))
        }
        dfast.app.util.Press.card(row)
        return row
    }

    private fun playlistRow(p: PlaylistInfoItem): View {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(7), dp(10), dp(7)) }
        val box = FrameLayout(this)
        box.addView(image(fixUrl(p.thumbnails.lastOrNull()?.url), 112, 63, false), FrameLayout.LayoutParams(dp(112), dp(63)))
        if (p.streamCount > 0) box.addView(TextView(this).apply {
            text = "${p.streamCount}"; textSize = 11f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
            setPadding(dp(4), 0, dp(4), 0); dfast.app.util.OIcons.start(this, R.drawable.ic_o_list, 0xFFFFFFFF.toInt(), 14, 2)
            background = GradientDrawable().apply { cornerRadii = floatArrayOf(0f, 0f, dp(10).toFloat(), dp(10).toFloat(), dp(10).toFloat(), dp(10).toFloat(), 0f, 0f); setColor(0xB3000000.toInt()) }
        }, FrameLayout.LayoutParams(dp(44), dp(63), Gravity.END))
        row.addView(box, LinearLayout.LayoutParams(dp(112), dp(63)))
        row.addView(texts(p.name, p.uploaderName ?: ""), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
        row.addView(dlButton(p.url))
        row.setOnClickListener { startActivity(playlistIntent(this, p.url, p.name)) }
        return row
    }

    private fun updateSel() { selLabel?.text = getString(R.string.o_download_selected) + " (" + selected.size + ")" }

    private fun playlistBar(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(4)) }
        val r1 = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row1 = LinearLayout(this)
        fun pill(t: String, icon: Int, on: Boolean, click: () -> Unit) = PlayUi.pill(this, t, on, click).apply {
            dfast.app.util.OIcons.start(this, icon, if (on) OrbitColors.pillText(context) else OrbitColors.ink(context), 16, 6)
        }
        row1.addView(pill(getString(R.string.o_download_all), R.drawable.ic_o_download, true) { playlistUrl?.let { sendToDownload(it) } }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        row1.addView(pill(getString(R.string.o_select), R.drawable.ic_o_checkbox, selecting) { selecting = !selecting; selected.clear(); render(lastItems) }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        row1.addView(pill(getString(R.string.o_save_offline_n), R.drawable.ic_o_offline, false) { offlineDialog(lastStreams) }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        row1.addView(pill(getString(R.string.play_add_to_playlist), R.drawable.ic_o_add, false) {
            dfast.app.util.PlaylistPicker.show(this, lastStreams.map { MediaRef(it.url, it.name, it.uploaderName.removeSuffix(" - Topic"), thumbOf(it), fmtDur(it.duration)) }, intent.getStringExtra("channel_name") ?: "")
        }, LinearLayout.LayoutParams(-2, -2))
        r1.addView(row1); col.addView(r1)
        if (selecting) {
            val r2 = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; setPadding(0, dp(8), 0, 0) }
            val row2 = LinearLayout(this)
            row2.addView(PlayUi.pill(this, getString(R.string.o_select_all), false) {
                if (selected.size == lastStreams.size) selected.clear() else lastStreams.forEach { selected.add(it.url) }; render(lastItems)
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            val dl = PlayUi.pill(this, getString(R.string.o_download_selected) + " (" + selected.size + ")", true) {
                if (selected.isEmpty()) return@pill
                runCatching { startActivity(Intent(this, ShareActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra("inapp", true).putExtra(Intent.EXTRA_TEXT, selected.joinToString("\n"))) }
            }
            selLabel = dl
            row2.addView(dl, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            row2.addView(PlayUi.pill(this, getString(R.string.o_save_offline_n), false) { offlineDialog(lastStreams.filter { selected.contains(it.url) }) })
            r2.addView(row2); col.addView(r2)
        }
        return col
    }

    /** Saves songs/videos to the app-private Offline storage, in their own folder named after the playlist. */
    private fun offlineDialog(items: List<StreamInfoItem>) {
        if (items.isEmpty()) { Toast.makeText(this, getString(R.string.o_no_items), Toast.LENGTH_SHORT).show(); return }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle(getString(R.string.o_save_offline_n) + " (" + items.size + ")")
            .setItems(arrayOf(getString(R.string.o_dl_video), getString(R.string.o_dl_audio))) { _, w ->
                val type = if (w == 0) dfast.app.database.enums.DownloadType.video else dfast.app.database.enums.DownloadType.audio
                val folder = (intent.getStringExtra("channel_name") ?: "playlist").replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "playlist" }
                val vm = androidx.lifecycle.ViewModelProvider(this)[dfast.app.database.viewmodel.DownloadViewModel::class.java]
                lifecycleScope.launch {
                    val dir = java.io.File(filesDir, "offline/" + (if (type == dfast.app.database.enums.DownloadType.audio) "audio" else "video") + "/" + folder).apply { mkdirs() }.absolutePath
                    val made = withContext(Dispatchers.IO) {
                        items.map { st ->
                            val ri = ResultItem(0, st.url, st.name, st.uploaderName.removeSuffix(" - Topic"), fmtDur(st.duration), thumbOf(st), "youtube", "", emptyList(), "", null)
                            vm.createDownloadItemFromResult(result = ri, givenType = type).also { it.downloadPath = dir }
                        }
                    }
                    vm.queueDownloads(made)
                    Toast.makeText(this@SearchResultsActivity, getString(R.string.o_added_dl), Toast.LENGTH_SHORT).show()
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun channelId(url: String) = url.substringAfter("/channel/", "").substringBefore("/").substringBefore("?")

    private fun subButton(url: String): TextView {
        val id = channelId(url)
        val b = TextView(this)
        fun paint() {
            val on = YtAccountApi.isSubscribed(id)
            b.text = getString(if (on) R.string.o_subscribed else R.string.o_subscribe)
            b.textSize = 12f; b.setTypeface(b.typeface, android.graphics.Typeface.BOLD)
            b.setPadding(dp(14), dp(7), dp(14), dp(7))
            b.setTextColor(if (on) 0xFF9D86FF.toInt() else 0xFF14102E.toInt())
            b.background = GradientDrawable().apply {
                cornerRadius = 1000f
                if (on) setStroke(dp(1), 0xFF9D86FF.toInt()) else setColor(0xFF9D86FF.toInt())
            }
        }
        paint()
        b.setOnClickListener {
            if (!GoogleAccount.isSignedIn(this)) { Toast.makeText(this, getString(R.string.o_drawer_sign_in), Toast.LENGTH_LONG).show(); return@setOnClickListener }
            if (id.isEmpty()) { Toast.makeText(this, getString(R.string.o_sub_failed), Toast.LENGTH_LONG).show(); return@setOnClickListener }
            val want = !YtAccountApi.isSubscribed(id)
            b.isEnabled = false
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { YtAccountApi.setSubscribed(this@SearchResultsActivity, id, want) }
                b.isEnabled = true
                if (!ok) Toast.makeText(this@SearchResultsActivity, getString(R.string.o_sub_failed), Toast.LENGTH_LONG).show()
                paint()
            }
        }
        return b
    }

    private fun chanSub(c: ChannelInfoItem): String {
        val h = c.url.substringAfter("/@", "").substringBefore("/")
        return listOf(if (h.isNotEmpty()) "@$h" else "",
            if (c.subscriberCount >= 0) getString(R.string.o_subscribers_fmt, compact(c.subscriberCount)) else "").filter { it.isNotEmpty() }.joinToString(" · ")
    }

    private fun channelRow(c: ChannelInfoItem): View {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(8)) }
        row.addView(image(fixUrl(c.thumbnails.lastOrNull()?.url), 52, 52, true), LinearLayout.LayoutParams(dp(52), dp(52)))
        row.addView(texts(c.name, chanSub(c)), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        row.addView(subButton(c.url))
        row.setOnClickListener { startActivity(channelIntent(this, c.url, c.name)) }
        return row
    }

    private fun channelCard(c: ChannelInfoItem): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(OrbitColors.glass2(context)) }
        }
        row.addView(image(fixUrl(c.thumbnails.lastOrNull()?.url), 56, 56, true), LinearLayout.LayoutParams(dp(56), dp(56)))
        val sub = listOf(if (c.subscriberCount >= 0) getString(R.string.o_subscribers_fmt, compact(c.subscriberCount)) else "",
            if (c.streamCount >= 0) getString(R.string.o_videos_fmt, c.streamCount.toString()) else "").filter { it.isNotEmpty() }.joinToString(" · ")
        row.addView(texts(c.name, sub), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        row.addView(subButton(c.url))
        row.setOnClickListener { startActivity(channelIntent(this, c.url, c.name)) }
        val w = FrameLayout(this).apply { setPadding(dp(12), dp(6), dp(12), dp(6)); addView(row) }
        return w
    }


    // ---------------------------------------------------------------------------------------------
    // Channel page (design B): glass header, pill tabs (Home, Videos, Shorts, Live, Playlists), real paging per tab.
    // Home is composed from the first page of the real tabs. Only tabs the channel really has are shown.
    // ---------------------------------------------------------------------------------------------
    private class ChanTab(val key: String, val handler: org.schabi.newpipe.extractor.linkhandler.ListLinkHandler?) {
        val streams = ArrayList<StreamInfoItem>()
        val queue = ArrayList<MediaRef>()
        val playlists = ArrayList<PlaylistInfoItem>()
        val seen = HashSet<String>()
        var next: org.schabi.newpipe.extractor.Page? = null
        var loaded = false
        var loading = false
        var failed = false
        var rendered = 0
        var rowOpen: LinearLayout? = null
        // sort (Videos / Live) and the Shows tab use YouTube's own browse response (util/ChannelBrowse)
        var sort = 0
        var gen = 0
        var sortChips: List<dfast.app.util.ChannelBrowse.Chip>? = null
        var sortProbed = false
        var nextTok: String? = null
        var ownFirst: dfast.app.util.ChannelBrowse.Parsed? = null
        val own get() = key == "shows" || sort > 0
        val isHome get() = key == "home"
        val homeSource get() = key == "videos" || key == "shorts" || key == "playlists"
    }
    private var chanInfo: ChannelInfo? = null
    private val chanTabs = ArrayList<ChanTab>()
    private var chanSel = 0
    private var chanPills: LinearLayout? = null
    private var chanBody: LinearLayout? = null

    private fun chanLabel(key: String) = getString(when (key) {
        "home" -> R.string.home
        "videos" -> R.string.o_videos
        "shorts" -> R.string.o_chip_shorts
        "livestreams" -> R.string.o_topic_live
        "albums" -> R.string.o_chip_releases
        "shows" -> R.string.o_chip_shows
        else -> R.string.o_chip_playlists
    })

    private fun statusLine(text: String, retry: (() -> Unit)? = null) = TextView(this).apply {
        this.text = text; textSize = 14f; setTextColor(OrbitColors.inkSoft(context)); gravity = Gravity.CENTER; setPadding(0, dp(32), 0, dp(32))
        if (retry != null) setOnClickListener { retry() }
    }

    private fun startChannel() {
        val my = ++job
        list.removeAllViews()
        list.addView(statusLine("…"))
        val url = channelUrl ?: return
        lifecycleScope.launch {
            downloadedIds = withContext(Dispatchers.IO) {
                runCatching { dfast.app.database.DBManager.getInstance(this@SearchResultsActivity).historyDao.getAllHistoryList().map { dfast.app.util.UrlIds.of(it.url) }.filter { it.isNotEmpty() }.toHashSet() }.getOrDefault(emptySet<String>())
            }
            val info = withContext(Dispatchers.IO) { runCatching { ChannelInfo.getInfo(ServiceList.YouTube, url) }.getOrNull() }
            if (my != job) return@launch
            if (info == null) {
                list.removeAllViews()
                list.addView(statusLine(getString(R.string.network_error)) { startChannel() })
                return@launch
            }
            chanInfo = info
            chanTabs.clear()
            chanTabs.add(ChanTab("home", null))
            for (k in listOf("videos", "shorts", "livestreams", "albums", "playlists")) {
                info.tabs.firstOrNull { it.contentFilters.firstOrNull() == k }?.let { chanTabs.add(ChanTab(k, it)) }
            }
            buildChannelFrame(info)
            selectChanTab(0)
            probeShows(info, my)
        }
    }

    private fun buildChannelFrame(info: ChannelInfo) {
        list.removeAllViews()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply { cornerRadius = dp(26).toFloat(); setColor(OrbitColors.glass2(this@SearchResultsActivity)) }
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(image(fixUrl(info.avatars.lastOrNull()?.url), 64, 64, true), LinearLayout.LayoutParams(dp(64), dp(64)))
        val handle = (info.url ?: channelUrl ?: "").substringAfter("/@", "").substringBefore("/")
        val sub = listOf(if (handle.isNotEmpty()) "@$handle" else "",
            if (info.subscriberCount >= 0) getString(R.string.o_subscribers_fmt, compact(info.subscriberCount)) else "").filter { it.isNotEmpty() }.joinToString(" \u00B7 ")
        top.addView(texts(info.name ?: (intent.getStringExtra("channel_name") ?: ""), sub), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
        card.addView(top, LinearLayout.LayoutParams(-1, -2))
        val sb = subButton(info.url ?: channelUrl ?: "")
        sb.gravity = Gravity.CENTER
        card.addView(sb, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(8), dp(12), dp(8)) })

        val pills = LinearLayout(this).apply { setPadding(dp(12), dp(4), dp(12), dp(8)) }
        chanPills = pills
        list.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(pills) })
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chanBody = body
        list.addView(body, LinearLayout.LayoutParams(-1, -2))
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> chanMaybeMore() }
    }

    private fun chanMaybeMore() {
        if (channelUrl == null) return
        val t = chanTabs.getOrNull(chanSel) ?: return
        if (t.isHome) return
        val child = scroll.getChildAt(0) ?: return
        if (child.bottom - (scroll.height + scroll.scrollY) < dp(500)) loadChanPage(t)
    }

    private fun paintPills() {
        val row = chanPills ?: return
        row.removeAllViews()
        chanTabs.forEachIndexed { i, t ->
            val on = i == chanSel
            row.addView(TextView(this).apply {
                text = chanLabel(t.key); textSize = 13f; setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(if (on) 0xFF14102E.toInt() else OrbitColors.ink(context))
                setPadding(dp(16), dp(9), dp(16), dp(9))
                background = GradientDrawable().apply { cornerRadius = 1000f; setColor(if (on) 0xFF9D86FF.toInt() else OrbitColors.glass2(context)) }
                setOnClickListener { selectChanTab(i) }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
    }

    private fun selectChanTab(i: Int) {
        chanSel = i
        paintPills()
        val body = chanBody ?: return
        body.removeAllViews()
        val t = chanTabs[i]
        chanTabs.forEach { it.rendered = 0; it.rowOpen = null }
        if (t.isHome) {
            chanTabs.filter { it.homeSource }.forEach { if (!it.loaded) loadChanPage(it) }
            renderChanHome()
        } else {
            if (t.key == "videos" || t.key == "livestreams") { t.sortChips?.let { body.addView(sortRow(t)) } ?: probeSort(t) }
            if (!t.loaded) loadChanPage(t)
            appendChanItems(t)
        }
        scroll.post { scroll.scrollTo(0, 0) }
    }

    /** One page of a tab (the first page, or the next one). Never runs twice at once for the same tab. */
    private fun loadChanPage(t: ChanTab) {
        val own = t.own
        val h = t.handler
        if (h == null && !own) return
        if (t.loading || (t.loaded && (if (own) t.nextTok == null else t.next == null))) return
        val page = t.next
        val my = job
        val myGen = t.gen
        t.loading = true; t.failed = false
        if (chanSel == chanTabs.indexOf(t)) appendChanItems(t)
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    if (own) {
                        val author = chanInfo?.name ?: ""
                        val tok = if (t.loaded) t.nextTok else (t.ownFirst?.let { f -> t.ownFirst = null; return@runCatching ownRes(f) } ?: t.sortChips?.getOrNull(t.sort)?.token)
                        val p = dfast.app.util.ChannelBrowse.more(requireNotNull(tok), author)
                        ownRes(requireNotNull(p))
                    } else if (h == null) throw IllegalStateException()
                    else if (!t.loaded) {
                        val ti = ChannelTabInfo.getInfo(ServiceList.YouTube, h)
                        Triple(ti.relatedItems.toList(), if (ti.hasNextPage()) ti.nextPage else null, null as String?)
                    } else {
                        val p = ChannelTabInfo.getMoreItems(ServiceList.YouTube, h, requireNotNull(page))
                        Triple(p.items.toList(), if (p.hasNextPage()) p.nextPage else null, null as String?)
                    }
                }
            }
            if (myGen != t.gen) return@launch
            t.loading = false
            if (my != job) return@launch
            val res = r.getOrNull()
            if (res == null) { t.failed = true } else {
                t.loaded = true; t.next = res.second; t.nextTok = res.third
                val author = chanInfo?.name ?: ""
                for (x in res.first) {
                    if (!t.seen.add(x.url)) continue
                    if (x is StreamInfoItem) {
                        t.streams.add(x)
                        t.queue.add(MediaRef(x.url, x.name, (x.uploaderName ?: "").removeSuffix(" - Topic").ifBlank { author }, thumbOf(x), fmtDur(x.duration)))
                    } else if (x is PlaylistInfoItem) t.playlists.add(x)
                }
            }
            val sel = chanTabs.getOrNull(chanSel)
            if (sel === t) { appendChanItems(t); scroll.post { chanMaybeMore() } }
            else if (sel != null && sel.isHome) renderChanHome()
        }
    }

    private fun ownRes(p: dfast.app.util.ChannelBrowse.Parsed): Triple<List<org.schabi.newpipe.extractor.InfoItem>, org.schabi.newpipe.extractor.Page?, String?> =
        Triple(p.streams + p.shows, null, p.next)

    /** Sort chips for Videos / Live. If YouTube does not give the three chips, nothing is shown and the tab stays as it was. */
    private fun probeSort(t: ChanTab) {
        if (t.sortProbed) return
        t.sortProbed = true
        val id = chanInfo?.id ?: return
        val my = job
        lifecycleScope.launch {
            val chips = withContext(Dispatchers.IO) {
                runCatching { dfast.app.util.ChannelBrowse.tab(id, if (t.key == "videos") dfast.app.util.ChannelBrowse.TAB_VIDEOS else dfast.app.util.ChannelBrowse.TAB_LIVE)?.let { dfast.app.util.ChannelBrowse.sortChips(it) } }.getOrNull()
            }
            if (my != job || chips == null) return@launch
            t.sortChips = chips
            val body = chanBody ?: return@launch
            if (chanTabs.getOrNull(chanSel) === t && (0 until body.childCount).none { body.getChildAt(it).tag == "sort" }) body.addView(sortRow(t), 0)
        }
    }

    private fun sortRow(t: ChanTab): View {
        val row = LinearLayout(this).apply { setPadding(dp(12), dp(2), dp(12), dp(6)) }
        val names = intArrayOf(R.string.o_sort_latest, R.string.o_sort_popular, R.string.o_sort_oldest)
        for (i in 0..2) row.addView(TextView(this).apply {
            val on = t.sort == i
            text = getString(names[i]); textSize = 12f
            setTextColor(if (on) 0xFF14102E.toInt() else OrbitColors.ink(context))
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = GradientDrawable().apply { cornerRadius = 1000f; setColor(if (on) 0xFF9D86FF.toInt() else OrbitColors.glass2(context)) }
            setOnClickListener { setChanSort(t, i) }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        row.tag = "sort"
        return row
    }

    private fun setChanSort(t: ChanTab, i: Int) {
        if (t.sort == i) return
        t.sort = i
        t.streams.clear(); t.queue.clear(); t.playlists.clear(); t.seen.clear()
        t.next = null; t.nextTok = null; t.loaded = false; t.loading = false; t.failed = false; t.rendered = 0; t.rowOpen = null
        t.gen++
        val body = chanBody ?: return
        body.removeAllViews()
        body.addView(sortRow(t))
        loadChanPage(t)
        appendChanItems(t)
    }

    /** Shows tab: shown only when the channel really has shows (first page already loaded here). */
    private fun probeShows(info: ChannelInfo, my: Int) {
        val id = info.id ?: return
        lifecycleScope.launch {
            val p = withContext(Dispatchers.IO) { runCatching { dfast.app.util.ChannelBrowse.tab(id, dfast.app.util.ChannelBrowse.TAB_SHOWS, info.name ?: "") }.getOrNull() }
            if (my != job || p == null || p.shows.isEmpty()) return@launch
            val t = ChanTab("shows", null)
            t.ownFirst = p
            chanTabs.add(chanTabs.indexOfFirst { it.key == "playlists" }.let { if (it >= 0) it + 1 else chanTabs.size }.coerceAtMost(chanTabs.size), t)
            paintPills()
        }
    }

    private fun isLiveItem(s: StreamInfoItem) =
        s.streamType == org.schabi.newpipe.extractor.stream.StreamType.LIVE_STREAM || s.streamType == org.schabi.newpipe.extractor.stream.StreamType.AUDIO_LIVE_STREAM

    private fun shortCard(t: ChanTab, i: Int, wDp: Int): View {
        val s = t.streams[i]
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val hDp = wDp * 16 / 9
        val u = fixUrl(s.thumbnails.lastOrNull()?.url).ifEmpty { thumbOf(s) }
        c.addView(image(u, wDp, hDp, false), LinearLayout.LayoutParams(dp(wDp), dp(hDp)))
        c.addView(texts(s.name, if (s.viewCount > 0) compact(s.viewCount) else ""), LinearLayout.LayoutParams(dp(wDp), -2).apply { topMargin = dp(6) })
        c.setOnClickListener {
            val ref = t.queue[i]
            val ri = ResultItem(0, s.url, s.name, ref.author, ref.duration, ref.thumb, "youtube", "", emptyList(), "", null)
            startActivity(PlayerActivity.intent(this, ref, t.queue.drop(i + 1), ri))
        }
        dfast.app.util.Press.card(c)
        return c
    }

    private fun shortsGridWidthDp(): Int = (((resources.displayMetrics.widthPixels / resources.displayMetrics.density) - 24 - 24 - 10) / 2).toInt()

    /** Adds only the items of this tab that are not on screen yet (so a long channel is not rebuilt on every page). */
    private fun appendChanItems(t: ChanTab) {
        val body = chanBody ?: return
        // drop the footer (loading / retry / empty), it is added again below
        for (k in body.childCount - 1 downTo 0) if (body.getChildAt(k).tag == "foot") body.removeViewAt(k)
        when (t.key) {
            "playlists", "albums", "shows" -> {
                for (i in t.rendered until t.playlists.size) body.addView(playlistRow(t.playlists[i]))
                t.rendered = t.playlists.size
            }
            "shorts" -> {
                val w = shortsGridWidthDp()
                for (i in t.rendered until t.streams.size) {
                    val r = t.rowOpen?.takeIf { it.childCount < 2 } ?: LinearLayout(this).apply { setPadding(dp(12), dp(6), dp(12), dp(6)) }.also { body.addView(it); t.rowOpen = it }
                    r.addView(shortCard(t, i, w), LinearLayout.LayoutParams(dp(w), -2).apply { if (r.childCount > 0) marginStart = dp(10) })
                }
                t.rendered = t.streams.size
            }
            else -> {
                for (i in t.rendered until t.streams.size) body.addView(videoRow(t.streams[i], t.queue, i))
                t.rendered = t.streams.size
            }
        }
        val empty = if (t.key == "playlists" || t.key == "albums" || t.key == "shows") t.playlists.isEmpty() else t.streams.isEmpty()
        val foot: View? = when {
            t.loading -> statusLine("…")
            t.failed -> statusLine(getString(R.string.network_error)) { t.failed = false; loadChanPage(t) }
            t.loaded && empty -> statusLine(getString(R.string.o_no_results))
            else -> null
        }
        foot?.let { it.tag = "foot"; body.addView(it) }
    }

    private fun chanSectionTitle(key: String): View {
        val tv = TextView(this).apply {
            text = chanLabel(key) + "  \u203A"; textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(OrbitColors.ink(context)); setPadding(dp(16), dp(14), dp(16), dp(6))
            setOnClickListener { val i = chanTabs.indexOfFirst { it.key == key }; if (i >= 0) selectChanTab(i) }
        }
        return tv
    }

    /** Home: the newest videos, a Shorts shelf and the first playlists, all from the real tabs. */
    private fun renderChanHome() {
        val body = chanBody ?: return
        body.removeAllViews()
        var any = false
        chanTabs.firstOrNull { it.key == "videos" }?.takeIf { it.streams.isNotEmpty() }?.let { t ->
            any = true; body.addView(chanSectionTitle("videos"))
            for (i in 0 until minOf(5, t.streams.size)) body.addView(videoRow(t.streams[i], t.queue, i))
        }
        chanTabs.firstOrNull { it.key == "shorts" }?.takeIf { it.streams.isNotEmpty() }?.let { t ->
            any = true; body.addView(chanSectionTitle("shorts"))
            val row = LinearLayout(this).apply { setPadding(dp(12), 0, dp(12), dp(6)) }
            for (i in 0 until minOf(8, t.streams.size)) row.addView(shortCard(t, i, 120), LinearLayout.LayoutParams(dp(120), -2).apply { marginEnd = dp(10) })
            body.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        }
        chanTabs.firstOrNull { it.key == "playlists" }?.takeIf { it.playlists.isNotEmpty() }?.let { t ->
            any = true; body.addView(chanSectionTitle("playlists"))
            for (i in 0 until minOf(3, t.playlists.size)) body.addView(playlistRow(t.playlists[i]))
        }
        val pending = chanTabs.any { it.homeSource && (it.loading || !it.loaded) && !it.failed }
        if (pending) body.addView(statusLine("…"))
        else if (!any) body.addView(statusLine(getString(R.string.o_no_results)))
    }

    companion object {
        fun intent(c: Context, q: String) = Intent(c, SearchResultsActivity::class.java).putExtra("query", q)
        fun playlistIntent(c: Context, url: String, name: String) =
            Intent(c, SearchResultsActivity::class.java).putExtra("playlist_url", url).putExtra("channel_name", name)
        fun channelIntent(c: Context, url: String, name: String) =
            Intent(c, SearchResultsActivity::class.java).putExtra("channel_url", url).putExtra("channel_name", name)
    }
}
