package dfast.app.util

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.MotionEvent
import android.view.View

/** One shared pressed effect: a soft ripple plus a small scale-down while the finger is down. Cheap: no allocations per touch. */
object Press {
    private fun mask(radiusDp: Float, d: Float) = GradientDrawable().apply { setColor(-1); cornerRadius = radiusDp * d }

    /** [radiusDp] 0 = round icon button, otherwise the corner radius of a card or row. */
    @SuppressLint("ClickableViewAccessibility")
    fun apply(v: View, radiusDp: Float = 0f, scale: Float = 0.9f): View {
        val d = v.resources.displayMetrics.density
        val tint = (OrbitColors.ink(v.context) and 0x00FFFFFF) or 0x38000000
        val m = if (radiusDp <= 0f) GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(-1) } else mask(radiusDp, d)
        v.foreground = RippleDrawable(ColorStateList.valueOf(tint), null, m)
        if (scale < 1f) v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(scale).scaleY(scale).setDuration(70).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
            }
            false
        }
        return v
    }

    fun icon(v: View) = apply(v, 0f, 0.86f)
    fun pill(v: View) = apply(v, 24f, 0.95f)
    fun card(v: View, radiusDp: Float = 14f) = apply(v, radiusDp, 0.98f)
}
