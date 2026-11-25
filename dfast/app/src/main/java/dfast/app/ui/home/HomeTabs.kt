package dfast.app.ui.home

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.LifecycleOwner
import androidx.preference.PreferenceManager
import androidx.core.content.edit
import dfast.app.R
import dfast.app.util.GoogleAccount
import dfast.app.util.OrbitColors

/** Home top tabs: Search, YouTube, Music, Subscriptions (signed in only), More. */
object HomeTabs {
    private var selected = "search"

    private val platforms = listOf(
        "YouTube" to true, "Instagram" to true, "TikTok" to true, "Facebook" to true, "X / Twitter" to true,
        "Snapchat" to true, "Pinterest" to true, "Threads" to true, "SoundCloud" to true, "Reddit" to true,
        "Dailymotion" to true, "Bluesky" to true, "Kwai" to true, "WhatsApp Status" to true
    )

    fun build(ctx: Context, owner: LifecycleOwner, onTopic: (String) -> Unit): LinearLayout {
        val d = ctx.resources.displayMetrics.density
        val signed = GoogleAccount.isSignedIn(ctx)
        val tabs = arrayListOf("search" to ctx.getString(R.string.o_tab_search), "yt" to "YouTube", "music" to ctx.getString(R.string.o_tab_music))
        if (signed) tabs.add("subs" to ctx.getString(R.string.o_tab_subs))
        tabs.add("more" to ctx.getString(R.string.o_tab_more))
        if (tabs.none { it.first == selected }) selected = "search"

        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val content = FrameLayout(ctx)
        val strip = LinearLayout(ctx).apply { setPadding((12 * d).toInt(), (4 * d).toInt(), (12 * d).toInt(), (8 * d).toInt()) }
        val pills = HashMap<String, TextView>()

        fun paint() {
            pills.forEach { (k, tv) ->
                val on = k == selected
                tv.setTextColor(if (on) 0xFF14102E.toInt() else OrbitColors.ink(ctx))
                tv.background = GradientDrawable().apply { cornerRadius = 1000f; setColor(if (on) 0xFF9D86FF.toInt() else OrbitColors.glass2(ctx)) }
            }
        }
        fun show() {
            content.removeAllViews()
            content.addView(when (selected) {
                "search" -> searchPage(ctx)
                "yt" -> HomeFeed.build(ctx, owner, onTopic, "yt")
                "music" -> HomeFeed.build(ctx, owner, onTopic, "music")
                "subs" -> HomeFeed.build(ctx, owner, onTopic, "subs")
                else -> morePage(ctx)
            })
            paint()
        }
        tabs.forEach { (k, label) ->
            val tv = TextView(ctx).apply {
                text = label; textSize = 13f; setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
                setOnClickListener { selected = k; show() }
            }
            pills[k] = tv
            strip.addView(tv, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (8 * d).toInt() })
        }
        root.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(strip) })
        root.addView(content, LinearLayout.LayoutParams(-1, -2))
        show()
        return root
    }

    /** Search hero: wordmark, tagline and three depth layers of site tiles with a slow, cheap parallax drift. */
    private class HeroLayout(ctx: Context) : FrameLayout(ctx) {
        val layers = ArrayList<View>()
        var glow: View? = null
        private var anim: android.animation.ValueAnimator? = null
        private val amps = floatArrayOf(3f, 5f, 8f)

        private fun allowed(): Boolean {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            return !pm.isPowerSaveMode && android.animation.ValueAnimator.areAnimatorsEnabled() && isShown && windowVisibility == VISIBLE
        }
        fun refresh() {
            if (allowed()) {
                if (anim == null) {
                    val d = resources.displayMetrics.density
                    anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 14000; repeatCount = android.animation.ValueAnimator.INFINITE; repeatMode = android.animation.ValueAnimator.REVERSE
                        interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                        addUpdateListener {
                            val f = it.animatedValue as Float - 0.5f
                            layers.forEachIndexed { i, v -> v.translationY = f * 2f * amps[i % 3] * d }
                            glow?.alpha = 0.75f + 0.25f * (f + 0.5f)
                        }
                        start()
                    }
                }
            } else { anim?.cancel(); anim = null }
        }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); refresh() }
        override fun onDetachedFromWindow() { anim?.cancel(); anim = null; super.onDetachedFromWindow() }
        override fun onWindowVisibilityChanged(v: Int) { super.onWindowVisibilityChanged(v); refresh() }
        override fun onVisibilityChanged(changedView: View, v: Int) { super.onVisibilityChanged(changedView, v); refresh() }
    }

    private fun searchPage(ctx: Context): View {
        val d = ctx.resources.displayMetrics.density
        val box = HeroLayout(ctx)
        val w = ctx.resources.displayMetrics.widthPixels
        box.minimumHeight = (600 * d).toInt()
        val glow = View(ctx).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, 0x00000000)).apply { gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = 190 * d; colors = intArrayOf(0x596E5AFF, 0x00000000); setGradientCenter(0.5f, 0.5f) } }
        box.addView(glow, FrameLayout.LayoutParams(-1, (260 * d).toInt()).apply { topMargin = (10 * d).toInt() })
        box.glow = glow
        box.addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_wordmark_h2); adjustViewBounds = true; contentDescription = ctx.getString(R.string.app_name) },
            FrameLayout.LayoutParams((300 * d).toInt().coerceAtMost(w - (40 * d).toInt()), -2, Gravity.CENTER_HORIZONTAL).apply { topMargin = (60 * d).toInt() })
        box.addView(TextView(ctx).apply { text = ctx.getString(R.string.welcome_title); textSize = 17f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(OrbitColors.ink(ctx)); gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(-1, -2).apply { topMargin = (172 * d).toInt() })
        box.addView(TextView(ctx).apply { text = ctx.getString(R.string.search_hint); textSize = 12f; setTextColor(OrbitColors.inkSoft(ctx)); gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(-1, -2).apply { topMargin = (200 * d).toInt() })
        val sites = dfast.app.util.SitesStore.all(ctx)
        // back, mid, front layers: dx = tile centre offset from the exact screen centre (dp), y in dp, size in dp, alpha. Symmetric pyramid 5/3/2.
        data class T(val dx: Int, val y: Int, val sz: Int, val a: Float, val rot: Float)
        val layersDef = listOf(
            listOf(T(-112, 294, 42, .32f, -8f), T(-56, 286, 44, .36f, 6f), T(0, 296, 46, .4f, -4f), T(56, 286, 44, .36f, -6f), T(112, 294, 42, .32f, 8f)),
            listOf(T(-72, 344, 62, .7f, -6f), T(0, 352, 68, .85f, 0f), T(72, 344, 62, .7f, 6f)),
            listOf(T(-48, 426, 82, .95f, -5f), T(48, 426, 82, .95f, 5f)))
        var n = 0
        layersDef.forEach { defs ->
            val layer = FrameLayout(ctx)
            defs.forEach { t ->
                val st = if (sites.isNotEmpty()) sites[n % sites.size] else null
                val tile = FrameLayout(ctx).apply {
                    alpha = t.a; rotation = t.rot; elevation = 6 * d
                    background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf((st?.color ?: 0xFF4C6EF5.toInt()) and 0x00FFFFFF or 0x66000000, 0xFF0B0A14.toInt())).apply { cornerRadius = 22 * d; setStroke((1 * d).toInt().coerceAtLeast(1), 0x14FFFFFF) }
                    outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                }
                if (st != null) {
                    tile.addView(ImageView(ctx).apply { setImageResource(dfast.app.util.SitesStore.iconRes(ctx, st)); setColorFilter(0x99FFFFFF.toInt()) },
                        FrameLayout.LayoutParams((t.sz * 0.45f * d).toInt(), (t.sz * 0.45f * d).toInt(), Gravity.CENTER))
                    tile.setOnClickListener { ctx.startActivity(dfast.app.ui.play.SiteBrowserActivity.intent(ctx, st.name, st.url)) }
                }
                layer.addView(tile, FrameLayout.LayoutParams((t.sz * d).toInt(), (t.sz * d).toInt(), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { if (t.dx >= 0) leftMargin = (t.dx * d * 2).toInt() else rightMargin = (-t.dx * d * 2).toInt(); topMargin = (t.y * d).toInt() })
                n++
            }
            box.addView(layer, FrameLayout.LayoutParams(-1, -1))
            box.layers.add(layer)
        }
        return box
    }

    private fun morePage(ctx: Context): View {
        val d = ctx.resources.displayMetrics.density
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding((14 * d).toInt(), 0, (14 * d).toInt(), (120 * d).toInt()) }
        col.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.o_sites); textSize = 15f; dfast.app.util.OIcons.start(this, R.drawable.ic_o_chevron, OrbitColors.inkSoft(ctx), 18, 8); setTextColor(OrbitColors.ink(ctx)); setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
            background = GradientDrawable().apply { cornerRadius = 16 * d; setColor(OrbitColors.glass2(ctx)) }
            setOnClickListener { ctx.startActivity(android.content.Intent(ctx, dfast.app.ui.play.SitesActivity::class.java)) }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (10 * d).toInt() })
        col.addView(TextView(ctx).apply { text = ctx.getString(R.string.o_platforms_hint); textSize = 12f; setTextColor(OrbitColors.inkSoft(ctx)); setPadding(0, 0, 0, (8 * d).toInt()) })
        platforms.forEach { (name, def) ->
            val row = LinearLayout(ctx).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
                background = GradientDrawable().apply { cornerRadius = 16 * d; setColor(OrbitColors.glass2(ctx)) }
            }
            val t = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            t.addView(TextView(ctx).apply { text = name; textSize = 15f; setTextColor(OrbitColors.ink(ctx)); setTypeface(typeface, android.graphics.Typeface.BOLD) })
            t.addView(TextView(ctx).apply { text = ctx.getString(R.string.o_how_to_start) + " ›"; textSize = 12f; setTextColor(OrbitColors.inkSoft(ctx)) })
            row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(SwitchCompat(ctx).apply {
                isChecked = prefs.getBoolean("platform_$name", def)
                setOnCheckedChangeListener { _, on -> prefs.edit { putBoolean("platform_$name", on) } }
            })
            t.setOnClickListener {
                AlertDialog.Builder(ctx).setTitle(name)
                    .setMessage(ctx.getString(R.string.o_howto_1, name) + "\n\n" + ctx.getString(R.string.o_howto_2) + "\n\n" + ctx.getString(R.string.o_howto_3))
                    .setPositiveButton(android.R.string.ok, null).show()
            }
            col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (8 * d).toInt() })
        }
        return col
    }
}
