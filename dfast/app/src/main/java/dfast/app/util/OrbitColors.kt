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
    /** Set by ThemeUtil from the "icon_color" setting. */
    @Volatile var purpleIcons = false
    /** Icon colour for code-drawn icons: the setting's purple when on, else [white] (the theme ink by default). */
    fun icon(c: Context, white: Int = ink(c)): Int = if (purpleIcons) get(c, R.attr.dfastIcon, 0xFFB3A8FF.toInt()) else white
    /** Same for icons on always-dark surfaces (player): light purple when on, else [white]. */
    fun iconDark(white: Int = 0xFFFFFFFF.toInt()): Int = if (purpleIcons) 0xFFB3A8FF.toInt() else white
    fun ink(c: Context) = get(c, R.attr.orbitInk, 0xFFFFFFFF.toInt())
    fun inkSoft(c: Context) = get(c, R.attr.orbitInkSoft, 0xB3FFFFFF.toInt())
    fun glass2(c: Context) = get(c, R.attr.orbitGlass2, 0x33FFFFFF)
    fun pill(c: Context) = get(c, R.attr.orbitPill, 0xFFFFFFFF.toInt())
    fun pillText(c: Context) = get(c, R.attr.orbitPillText, 0xFF1B1470.toInt())
}
