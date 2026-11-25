package dfast.app.ui.home

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
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

    private fun searchPage(ctx: Context): View {
        val d = ctx.resources.displayMetrics.density
        val box = FrameLayout(ctx)
        val colors = listOf(0xFFFF6B6B, 0xFF4C6EF5, 0xFF43E97B, 0xFFC13584, 0xFF38F9D7, 0xFF888888, 0xFFFFB347, 0xFF9D86FF)
        val pos = listOf(-10 to 10, 90 to 0, 200 to 30, 10 to 120, 140 to 130, 250 to 160, -10 to 230, 110 to 250, 220 to 290)
        pos.forEachIndexed { i, (x, y) ->
            val v = View(ctx).apply {
                background = GradientDrawable().apply { cornerRadius = 22 * d; setColor(colors[i % colors.size].toInt()) }
                alpha = 0.14f; rotation = listOf(-14f, 10f, -8f, 12f, -6f, 14f, -12f, 8f, -10f)[i]
            }
            box.addView(v, FrameLayout.LayoutParams((84 * d).toInt(), (84 * d).toInt()).apply { leftMargin = (x * d).toInt(); topMargin = (y * d).toInt() })
        }
        box.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.app_name); textSize = 46f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(OrbitColors.ink(ctx)); gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(-1, -2).apply { topMargin = (90 * d).toInt() })
        box.minimumHeight = (420 * d).toInt()
        return box
    }

    private fun morePage(ctx: Context): View {
        val d = ctx.resources.displayMetrics.density
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding((14 * d).toInt(), 0, (14 * d).toInt(), (120 * d).toInt()) }
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
