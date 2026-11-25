package dfast.app.ui.play

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dfast.app.R
import dfast.app.util.Extensions.loadThumbnail
import dfast.app.util.MediaRef
import dfast.app.util.OrbitColors

/** Small view builders shared by the Play and Library screens. */
object PlayUi {
    fun dp(c: Context, n: Int) = (n * c.resources.displayMetrics.density).toInt()

    fun glass(c: Context, radiusDp: Int = 22) = GradientDrawable().apply {
        cornerRadius = dp(c, radiusDp).toFloat(); setColor(OrbitColors.get(c, R.attr.orbitGlass, 0x26FFFFFF))
    }

    fun pill(c: Context, text: String, on: Boolean, click: () -> Unit) = TextView(c).apply {
        this.text = text; textSize = 13f; gravity = Gravity.CENTER
        setTextColor(if (on) OrbitColors.pillText(c) else OrbitColors.ink(c))
        setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 8))
        background = GradientDrawable().apply { cornerRadius = 1000f; setColor(if (on) OrbitColors.pill(c) else OrbitColors.glass2(c)) }
        setOnClickListener { click() }
        dfast.app.util.Press.pill(this)
    }

    fun header(a: Activity, title: String, right: View? = null): LinearLayout = LinearLayout(a).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(a, 12), dp(a, 8), dp(a, 16), dp(a, 4))
        addView(ImageButton(a).apply {
            setImageResource(R.drawable.ic_pl_back); setBackgroundColor(Color.TRANSPARENT); contentDescription = a.getString(R.string.o_back)
            setOnClickListener { a.finish() }; dfast.app.util.Press.icon(this)
        }, LinearLayout.LayoutParams(dp(a, 44), dp(a, 44)))
        addView(TextView(a).apply {
            text = title; textSize = 22f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(OrbitColors.ink(a))
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(a, 6) })
        if (right != null) addView(right)
    }

    fun thumb(c: Context, ref: MediaRef, w: Int, h: Int, radiusDp: Int = 14): ImageView = ImageView(c).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
        background = GradientDrawable().apply { cornerRadius = dp(c, radiusDp).toFloat(); setColor(OrbitColors.glass2(c)) }
        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        layoutParams = LinearLayout.LayoutParams(dp(c, w), dp(c, h))
        loadThumbnail(false, ref.thumb)
    }

    /** A list row: thumbnail, title, author, optional trailing view. */
    fun row(c: Context, ref: MediaRef, trailing: View? = null, onClick: () -> Unit): LinearLayout = LinearLayout(c).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(c, 8), dp(c, 8), dp(c, 8), dp(c, 8))
        background = glass(c)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(c, 12), dp(c, 4), dp(c, 12), dp(c, 4)) }
        addView(thumb(c, ref, 64, 64))
        addView(LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(c).apply { text = ref.title.ifBlank { ref.url }; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; textSize = 14f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(OrbitColors.ink(c)) })
            addView(TextView(c).apply { text = ref.author; maxLines = 1; textSize = 12f; setTextColor(OrbitColors.inkSoft(c)) })
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(c, 12); marginEnd = dp(c, 8) })
        if (trailing != null) addView(trailing)
        setOnClickListener { onClick() }
        dfast.app.util.Press.card(this, 16f)
        if (ref.url.startsWith("http")) setOnLongClickListener { dfast.app.util.PlayQueue.menu(c, ref); true }
    }

    /** Poster card for 2-column grids. */
    fun poster(c: Context, ref: MediaRef, badge: String? = null, onClick: () -> Unit): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = glass(c)
        val frame = android.widget.FrameLayout(c)
        val img = thumb(c, ref, 10, 10, 18).apply { layoutParams = android.widget.FrameLayout.LayoutParams(-1, dp(c, 92)) }
        frame.addView(img)
        if (badge != null) frame.addView(TextView(c).apply {
            text = badge; textSize = 9f; setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(c, 8), dp(c, 2), dp(c, 8), dp(c, 2))
            background = GradientDrawable().apply { cornerRadius = 1000f; setColor(0xFF12B5A6.toInt()) }
        }, android.widget.FrameLayout.LayoutParams(-2, -2).apply { setMargins(dp(c, 6), dp(c, 6), 0, 0) })
        addView(frame)
        addView(TextView(c).apply {
            text = ref.title.ifBlank { ref.url }; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; textSize = 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(OrbitColors.ink(c)); setPadding(dp(c, 8), dp(c, 6), dp(c, 8), dp(c, 8))
        })
        setOnClickListener { onClick() }
    }

    fun grid(c: Context, cells: List<View>, columns: Int = 2): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(c, 12), dp(c, 4), dp(c, 12), dp(c, 24))
        cells.chunked(columns).forEach { chunk ->
            addView(LinearLayout(c).apply {
                chunk.forEach { v -> addView(v, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(c, 4), dp(c, 4), dp(c, 4), dp(c, 4)) }) }
                repeat(columns - chunk.size) { addView(View(c), LinearLayout.LayoutParams(0, 1, 1f)) }
            })
        }
    }

    fun empty(c: Context, text: String) = TextView(c).apply {
        this.text = text; gravity = Gravity.CENTER; setTextColor(OrbitColors.inkSoft(c)); setPadding(dp(c, 24), dp(c, 60), dp(c, 24), dp(c, 24))
    }

    fun stripCard(c: Context, ref: MediaRef, onClick: () -> Unit): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        addView(thumb(c, ref, 124, 72, 16))
        addView(TextView(c).apply { text = ref.title; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; textSize = 11f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(OrbitColors.ink(c)); setPadding(0, dp(c, 4), 0, 0) },
            LinearLayout.LayoutParams(dp(c, 124), -2))
        setOnClickListener { onClick() }
    }
}
