package dfast.app.util

import android.content.Context
import android.widget.ImageView
import android.widget.TextView

/** Vector icons (Material Symbols paths) used instead of emoji or letters. */
object OIcons {
    /** A neutral (white or theme-ink) tint follows the icon colour setting; a deliberate colour (red delete, accent) is kept. */
    fun tintFor(c: Context, tint: Int): Int {
        if (!OrbitColors.purpleIcons) return tint
        val rgb = tint and 0xFFFFFF
        return if (rgb == 0xFFFFFF || rgb == (OrbitColors.ink(c) and 0xFFFFFF)) OrbitColors.icon(c) else tint
    }
    fun view(c: Context, res: Int, sizeDp: Int = 24, tint: Int, padDp: Int = 8, onClick: (() -> Unit)? = null): ImageView = ImageView(c).apply {
        val d = c.resources.displayMetrics.density
        setImageResource(res); setColorFilter(tintFor(c, tint)); scaleType = ImageView.ScaleType.FIT_CENTER
        val p = (padDp * d).toInt(); setPadding(p, p, p, p)
        minimumWidth = ((sizeDp + padDp * 2) * d).toInt(); minimumHeight = minimumWidth
        if (onClick != null) { setOnClickListener { onClick() }; Press.icon(this) }
    }

    /** Puts an icon at the start of a text view. */
    fun start(t: TextView, res: Int, tint: Int, sizeDp: Int = 18, gapDp: Int = 8) {
        val d = t.context.resources.displayMetrics.density
        val dr = androidx.core.content.ContextCompat.getDrawable(t.context, res)!!.mutate()
        dr.setTint(tintFor(t.context, tint)); val s = (sizeDp * d).toInt(); dr.setBounds(0, 0, s, s)
        t.setCompoundDrawablesRelative(dr, null, null, null); t.compoundDrawablePadding = (gapDp * d).toInt()
    }
}
