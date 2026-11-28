package dfast.app.util

import android.content.Context
import android.util.TypedValue
import androidx.annotation.AttrRes
import dfast.app.R

/** Theme colours of the Orbit look (they change with the palette and light/dark mode). */
object OrbitColors {
    fun get(context: Context, @AttrRes attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(attr, tv, true)) tv.data else fallback
    }
    /** The one colour of every code-drawn icon: the theme ink (light on dark themes, dark ink on light ones). */
    fun icon(c: Context, color: Int = ink(c)): Int = color
    /** Code-drawn icons on an always-dark surface (player, video scrim). */
    fun iconOnDark(): Int = 0xFFFFFFFF.toInt()
    fun ink(c: Context) = get(c, R.attr.orbitInk, 0xFFFFFFFF.toInt())
    fun inkSoft(c: Context) = get(c, R.attr.orbitInkSoft, 0xB3FFFFFF.toInt())
    fun glass2(c: Context) = get(c, R.attr.orbitGlass2, 0x33FFFFFF)
    fun pill(c: Context) = get(c, R.attr.orbitPill, 0xFFFFFFFF.toInt())
    fun pillText(c: Context) = get(c, R.attr.orbitPillText, 0xFF1B1470.toInt())
}
