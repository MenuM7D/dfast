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

    fun build(ctx: Context, owner: LifecycleOwner, onTopic: (String) -> Unit, signedIn: Boolean = false): LinearLayout {
        val d = ctx.resources.displayMetrics.density
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, (4 * d).toInt(), 0, (160 * d).toInt()) }

        // topic chips
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

        // shelves
        val recents = PlayStore.recents(ctx).map { ResultItem(0, it.url, it.title, it.author, it.duration, it.thumb, "youtube", "", emptyList(), "", null) }
        shelf(ctx, owner, root, ctx.getString(R.string.o_shelf_trending), "trending") { NewPipeUtil(ctx).getTrending() }
        shelf(ctx, owner, root, ctx.getString(R.string.o_shelf_music), "music") { NewPipeUtil(ctx).search("new music hits").getOrNull() ?: arrayListOf() }
        if (recents.isNotEmpty()) addShelf(ctx, owner, root, ctx.getString(R.string.o_shelf_recent), recents)
        return root
    }

    private fun shelf(ctx: Context, owner: LifecycleOwner, root: LinearLayout, title: String, key: String, load: () -> List<ResultItem>) {
        cache[key]?.let { if (it.isNotEmpty()) { addShelf(ctx, owner, root, title, it); return } }
        owner.lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { runCatching { load() }.getOrDefault(emptyList()) }
            if (items.isNotEmpty()) {
                cache[key] = items
                // keep Recently watched last
                addShelf(ctx, owner, root, title, items, at = minOf(root.childCount, if (key == "trending") 1 else 2))
            }
        }
    }

    private fun addShelf(ctx: Context, owner: LifecycleOwner, root: LinearLayout, title: String, items: List<ResultItem>, at: Int = -1) {
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
            row.addView(card)
        }
        box.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        if (at >= 0) root.addView(box, at) else root.addView(box)
    }
}
