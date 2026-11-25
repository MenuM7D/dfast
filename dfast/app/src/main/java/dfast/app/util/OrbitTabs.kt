package dfast.app.util

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import dfast.app.MainActivity
import dfast.app.ui.play.LibraryHubActivity
import dfast.app.ui.play.PlayActivity

/** Top tabs: Home | Play | Library. selected: 0, 1 or 2. */
object OrbitTabs {
    fun build(a: Activity, selected: Int): LinearLayout {
        val d = a.resources.displayMetrics.density
        val row = LinearLayout(a).apply {
            gravity = Gravity.START
            setPadding((16 * d).toInt(), (4 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
        }
        listOf("Home", "Play", "Library").forEachIndexed { i, name ->
            val on = i == selected
            row.addView(TextView(a).apply {
                text = name; textSize = 13f; gravity = Gravity.CENTER
                setTextColor(if (on) OrbitColors.pillText(a) else OrbitColors.ink(a))
                setPadding((18 * d).toInt(), (8 * d).toInt(), (18 * d).toInt(), (8 * d).toInt())
                background = GradientDrawable().apply { cornerRadius = 1000f; setColor(if (on) OrbitColors.pill(a) else OrbitColors.glass2(a)) }
                setOnClickListener {
                    if (on) return@setOnClickListener
                    when (i) {
                        0 -> a.startActivity(Intent(a, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        1 -> a.startActivity(Intent(a, PlayActivity::class.java))
                        else -> a.startActivity(Intent(a, LibraryHubActivity::class.java))
                    }
                    if (selected != 0) a.finish()
                    a.overridePendingTransition(0, 0)
                }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (8 * d).toInt() })
        }
        return row
    }
}
