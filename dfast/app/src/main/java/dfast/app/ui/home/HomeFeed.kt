package dfast.app.ui.home

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import dfast.app.R
import dfast.app.database.models.ResultItem
import dfast.app.ui.play.PlayerActivity
import dfast.app.util.MediaRef
import dfast.app.util.OrbitColors
import dfast.app.util.PlayStore
import dfast.app.util.extractors.newpipe.NewPipeUtil
import com.squareup.picasso.Picasso
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** HB home: topic chips + shelves (Trending, Music for you, Recently watched). */
object HomeFeed {
    private val cache = HashMap<String, List<ResultItem>>()

    fun clearCache() = cache.clear()
    private fun rankOf(key: String) = when (key) { "g_music" -> 0; "g_subs" -> 1; "g_rec" -> 1; "g_wl" -> 2; "g_like" -> 3; "g_his" -> 4; "trending" -> 5; "music" -> 6; else -> 7 }

    fun build(ctx: Context, owner: LifecycleOwner, onTopic: (String) -> Unit, mode: String = "yt"): LinearLayout {
        val d = ctx.resources.displayMetrics.density
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, (4 * d).toInt(), 0, (160 * d).toInt()) }

        // topic chips
        if (mode == "yt") {
        val chips = LinearLayout(ctx).apply { setPadding((14 * d).toInt(), (6 * d).toInt(), (14 * d).toInt(), (10 * d).toInt()) }
        listOf(
            R.string.o_topic_music to "music", R.string.o_topic_gaming to "gaming", R.string.o_topic_news to "news",
            R.string.o_topic_podcasts to "podcasts", R.string.o_topic_live to "live", R.string.o_topic_learning to "learn"
        ).forEach { (res, q) ->
            chips.addView(TextView(ctx).apply {
                text = ctx.getString(res); setTextColor(OrbitColors.ink(ctx)); textSize = 13f
                setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
                background = GradientDrawable().apply { cornerRadius = 1000f; setColor(OrbitColors.glass2(ctx)) }
                setOnClickListener { onTopic(ctx.getString(res)) }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (8 * d).toInt() })
        }
        root.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        }

        // subscribed channels row (Subscriptions tab)
        if (mode == "subs" && dfast.app.util.GoogleAccount.isSignedIn(ctx)) {
            val holder = LinearLayout(ctx).apply { tag = 0 }
            root.addView(holder)
            owner.lifecycleScope.launch {
                val chans = withContext(Dispatchers.IO) { runCatching { dfast.app.util.YtAccountApi.listSubscriptions(ctx) }.getOrDefault(emptyList()) }
                if (chans.isEmpty()) return@launch
                val row = LinearLayout(ctx).apply { setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt()) }
                chans.take(40).forEach { c ->
                    val img = ImageView(ctx).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(OrbitColors.glass2(ctx)) }
                        clipToOutline = true
                        outlineProvider = object : android.view.ViewOutlineProvider() {
                            override fun getOutline(v: View, o: android.graphics.Outline) { o.setOval(0, 0, v.width, v.height) }
                        }
                        if (c.thumb.isNotEmpty()) runCatching { Picasso.get().load(c.thumb).resize(160, 0).onlyScaleDown().into(this) }
                        setOnClickListener { ctx.startActivity(dfast.app.ui.play.SearchResultsActivity.channelIntent(ctx, "https://www.youtube.com/channel/" + c.id, c.name)) }
                    }
                    row.addView(img, LinearLayout.LayoutParams((56 * d).toInt(), (56 * d).toInt()).apply { marginEnd = (10 * d).toInt() })
                }
                holder.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(row) })
            }
        }

        // shelves
        val recents = PlayStore.recents(ctx).map { ResultItem(0, it.url, it.title, it.author, it.duration, it.thumb, "youtube", "", emptyList(), "", null) }
        if (mode == "yt") shelf(ctx, owner, root, ctx.getString(R.string.o_shelf_trending), "trending") { trendingItems(ctx) }
        if (mode == "music") {
            if (dfast.app.util.GoogleAccount.shelvesOn(ctx))
                shelf(ctx, owner, root, ctx.getString(R.string.o_shelf_music), "g_music", 0) { dfast.app.util.YtAccountApi.musicHome(ctx) }
            else shelf(ctx, owner, root, ctx.getString(R.string.o_shelf_music), "music") { NewPipeUtil(ctx).search("new music hits").getOrNull() ?: arrayListOf() }
            shelf(ctx, owner, root, ctx.getString(R.string.o_shelf_mixes), "mixes") { NewPipeUtil(ctx).search("song mix playlist").getOrNull() ?: arrayListOf() }
            shelf(ctx, owner, root, ctx.getString(R.string.o_shelf_new_releases), "newrel") { NewPipeUtil(ctx).search("new songs this week").getOrNull() ?: arrayListOf() }
        }
        if (mode == "subs" && dfast.app.util.GoogleAccount.shelvesOn(ctx)) {
            val ck = dfast.app.util.GoogleAccount.shelfCookiesPath(ctx)
            feedList(ctx, owner, root, "g_subsfeed") {
                dfast.app.util.YtAccountApi.videos(ctx, "FEsubscriptions").ifEmpty {
                    if (ck == null) emptyList() else kotlinx.coroutines.runBlocking { dfast.app.util.extractors.ytdlp.YTDLPUtil(ctx, dfast.app.database.DBManager.getInstance(ctx).commandTemplateDao).getYoutubeSubscriptions(ck) }
                }
            }
        }
        if (mode == "yt") dfast.app.util.GoogleAccount.shelfCookiesPath(ctx)?.let { ck ->
            val yt = dfast.app.util.extractors.ytdlp.YTDLPUtil(ctx, dfast.app.database.DBManager.getInstance(ctx).commandTemplateDao)
            shelf(ctx, owner, root, ctx.getString(R.string.o_shelf_recommended), "g_rec", 1) { dfast.app.util.YtAccountApi.videos(ctx, "FEwhat_to_watch").ifEmpty { kotlinx.coroutines.runBlocking { yt.getYoutubeRecommendations(ck) } } }
            shelf(ctx, owner, root, ctx.getString(R.string.o_shelf_watch_later), "g_wl", 2) { dfast.app.util.YtAccountApi.videos(ctx, "VLWL").ifEmpty { kotlinx.coroutines.runBlocking { yt.getYoutubeWatchLater(ck) } } }
            shelf(ctx, owner, root, ctx.getString(R.string.o_shelf_liked), "g_like", 3) { dfast.app.util.YtAccountApi.videos(ctx, "VLLL").ifEmpty { kotlinx.coroutines.runBlocking { yt.getYoutubeLikedVideos(ck) } } }
            shelf(ctx, owner, root, ctx.getString(R.string.o_shelf_history), "g_his", 4) { dfast.app.util.YtAccountApi.videos(ctx, "FEhistory").ifEmpty { kotlinx.coroutines.runBlocking { yt.getYoutubeWatchHistory(ck) } } }
        }
        if (mode == "yt" && recents.isNotEmpty()) addShelf(ctx, owner, root, ctx.getString(R.string.o_shelf_recent), recents, 7)
        return root
    }

    private fun trendingItems(ctx: Context): List<ResultItem> {
        val a = runCatching { NewPipeUtil(ctx).getTrending() }.getOrDefault(emptyList())
        if (a.isNotEmpty()) return a
        val b = runCatching { dfast.app.util.YtAccountApi.videos(ctx, "FEwhat_to_watch", needAuth = false) }.getOrDefault(emptyList())
        if (b.isNotEmpty()) return b
        return runCatching { NewPipeUtil(ctx).search("trending").getOrNull() ?: arrayListOf() }.getOrDefault(emptyList())
    }

    /** Vertical feed of large video cards (Subscriptions tab). */
    private fun feedList(ctx: Context, owner: LifecycleOwner, root: LinearLayout, key: String, load: () -> List<ResultItem>) {
        val d = ctx.resources.displayMetrics.density
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; tag = 1 }
        root.addView(box)
        val status = TextView(ctx).apply { text = "…"; textSize = 20f; gravity = Gravity.CENTER; setTextColor(OrbitColors.inkSoft(ctx)); setPadding(0, (30 * d).toInt(), 0, 0) }
        box.addView(status)
        owner.lifecycleScope.launch {
            val items = cache[key]?.takeIf { it.isNotEmpty() } ?: withContext(Dispatchers.IO) { runCatching { load() }.getOrDefault(emptyList()) }
            box.removeView(status)
            if (items.isEmpty()) {
                box.addView(TextView(ctx).apply { text = ctx.getString(R.string.o_feed_empty); gravity = Gravity.CENTER; textSize = 13f; setTextColor(OrbitColors.inkSoft(ctx)); setPadding((24 * d).toInt(), (30 * d).toInt(), (24 * d).toInt(), 0) })
                return@launch
            }
            cache[key] = items
            val shown = items.take(40)
            val queue = shown.map { MediaRef(it.url, it.title, it.author, it.thumb, it.duration) }
            val w = ctx.resources.displayMetrics.widthPixels - (32 * d).toInt()
            shown.forEachIndexed { i, it ->
                val card = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (10 * d).toInt()) }
                val img = ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
                    background = GradientDrawable().apply { cornerRadius = 16 * d; setColor(OrbitColors.glass2(ctx)) }
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, 16 * d) }
                    }
                }
                if (it.thumb.isNotEmpty()) runCatching { Picasso.get().load(it.thumb).resize(720, 0).onlyScaleDown().into(img) }
                card.addView(img, LinearLayout.LayoutParams(w, w * 9 / 16))
                card.addView(TextView(ctx).apply { text = it.title; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; textSize = 14f; setTextColor(OrbitColors.ink(ctx)); setPadding(0, (8 * d).toInt(), 0, 0) })
                if (it.author.isNotEmpty()) card.addView(TextView(ctx).apply { text = it.author; maxLines = 1; textSize = 12f; setTextColor(OrbitColors.inkSoft(ctx)) })
                card.setOnClickListener { ctx.startActivity(PlayerActivity.intent(ctx, queue[i], queue.drop(i + 1), items[i])) }
                card.setOnLongClickListener { dfast.app.util.PlayQueue.menu(ctx, queue[i]); true }
                box.addView(card)
            }
        }
    }

    private fun shelf(ctx: Context, owner: LifecycleOwner, root: LinearLayout, title: String, key: String, pos: Int = -1, load: () -> List<ResultItem>) {
        cache[key]?.let { if (it.isNotEmpty()) { addShelf(ctx, owner, root, title, it, rank = rankOf(key)); return } }
        owner.lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { runCatching { load() }.getOrDefault(emptyList()) }
            if (items.isNotEmpty()) {
                cache[key] = items
                // keep Recently watched last
                addShelf(ctx, owner, root, title, items, rank = rankOf(key))
            }
        }
    }

    private fun addShelf(ctx: Context, owner: LifecycleOwner, root: LinearLayout, title: String, items: List<ResultItem>, rank: Int = 7) {
        val d = ctx.resources.displayMetrics.density
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(ctx).apply {
            text = title; setTextColor(OrbitColors.ink(ctx)); textSize = 17f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding((16 * d).toInt(), (14 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
        })
        val row = LinearLayout(ctx).apply { setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0) }
        val queue = items.take(20).map { MediaRef(it.url, it.title, it.author, it.thumb, it.duration) }
        items.take(20).forEachIndexed { i, it ->
            val card = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding((4 * d).toInt(), 0, (4 * d).toInt(), 0) }
            val img = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply { cornerRadius = 14 * d; setColor(OrbitColors.glass2(ctx)) }
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, 14 * d) }
                }
            }
            if (it.thumb.isNotEmpty()) runCatching { Picasso.get().load(it.thumb).resize(480, 0).onlyScaleDown().into(img) }
            card.addView(img, LinearLayout.LayoutParams((176 * d).toInt(), (99 * d).toInt()))
            card.addView(TextView(ctx).apply {
                text = it.title; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; textSize = 13f; setTextColor(OrbitColors.ink(ctx))
                setPadding(0, (6 * d).toInt(), 0, 0)
            }, LinearLayout.LayoutParams((176 * d).toInt(), -2))
            card.addView(TextView(ctx).apply {
                text = it.author; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; textSize = 11f; setTextColor(OrbitColors.inkSoft(ctx))
            }, LinearLayout.LayoutParams((176 * d).toInt(), -2))
            card.setOnClickListener { ctx.startActivity(PlayerActivity.intent(ctx, queue[i], queue.drop(i + 1), items[i])) }
            card.setOnLongClickListener { dfast.app.util.PlayQueue.menu(ctx, queue[i]); true }
            row.addView(card)
        }
        box.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        box.tag = rank
        var idx = root.childCount
        for (i in 1 until root.childCount) { if (((root.getChildAt(i).tag as? Int) ?: 99) > rank) { idx = i; break } }
        root.addView(box, idx)
    }
}
