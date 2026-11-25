package dfast.app.ui.play

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Patterns
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dfast.app.R
import dfast.app.util.OrbitColors
import dfast.app.util.SitesStore
import dfast.app.util.ThemeUtil

/** Sites: list with add, delete mode and visit history. Tapping a site opens it in the in-app browser. */
class SitesActivity : AppCompatActivity() {
    private lateinit var body: LinearLayout
    private var deleteMode = false
    private fun dp(n: Int) = PlayUi.dp(this, n)

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtil.updateTheme(this)
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.bg_orbit) }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        fun icon(res: Int, onClick: () -> Unit) = dfast.app.util.OIcons.view(this, res, 22, OrbitColors.ink(this), 9, onClick)
        bar.addView(icon(R.drawable.ic_o_arrow_back) { finish() })
        bar.addView(TextView(this).apply { text = getString(R.string.o_sites); textSize = 20f; setTextColor(OrbitColors.ink(context)); setTypeface(typeface, android.graphics.Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(icon(R.drawable.ic_o_history) { showHistory() })
        bar.addView(icon(R.drawable.ic_o_delete) { deleteMode = !deleteMode; render() })
        bar.addView(icon(R.drawable.ic_o_add) { addDialog() })
        root.addView(bar)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(24)) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars()); v.setPadding(0, b.top, 0, b.bottom); insets
        }
        render()
    }

    private fun render() {
        body.removeAllViews()
        SitesStore.all(this).forEach { s ->
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(9), dp(16), dp(9)) }
            row.addView(android.widget.ImageView(this).apply {
                setImageResource(R.drawable.ic_o_globe); setColorFilter(0xFFFFFFFF.toInt()); setPadding(dp(10), dp(10), dp(10), dp(10))
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(s.color) }
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            t.addView(TextView(this).apply { text = s.name; textSize = 16f; setTextColor(OrbitColors.ink(context)); setTypeface(typeface, android.graphics.Typeface.BOLD) })
            t.addView(TextView(this).apply { text = s.url.removePrefix("https://").removePrefix("http://"); textSize = 12f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(OrbitColors.inkSoft(context)) })
            row.addView(t, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14); marginEnd = dp(8) })
            row.addView(dfast.app.util.OIcons.view(this, if (deleteMode) R.drawable.ic_o_close else R.drawable.ic_o_chevron, 20, if (deleteMode) 0xFFFF6B6B.toInt() else OrbitColors.inkSoft(this), 4))
            row.setOnClickListener {
                if (deleteMode) {
                    AlertDialog.Builder(this).setMessage(getString(R.string.o_remove_site_q, s.name))
                        .setPositiveButton(android.R.string.ok) { _, _ -> SitesStore.remove(this, s); render() }
                        .setNegativeButton(android.R.string.cancel, null).show()
                } else startActivity(SiteBrowserActivity.intent(this, s.name, s.url))
            }
            body.addView(row)
        }
    }

    private fun addDialog() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(10), dp(22), 0) }
        val n = EditText(this).apply { hint = getString(R.string.o_site_name); isSingleLine = true }
        val u = EditText(this).apply { hint = "https://example.com"; isSingleLine = true; inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI }
        col.addView(n); col.addView(u)
        AlertDialog.Builder(this).setTitle(getString(R.string.o_add_site)).setView(col)
            .setPositiveButton(getString(R.string.o_add)) { _, _ ->
                var url = u.text.toString().trim()
                if (url.isNotEmpty() && !url.contains("://")) url = "https://$url"
                val name = n.text.toString().trim().ifEmpty { url.substringAfter("://").removePrefix("www.").substringBefore("/") }
                if (url.isEmpty() || !Patterns.WEB_URL.matcher(url).matches()) Toast.makeText(this, getString(R.string.o_invalid_url), Toast.LENGTH_LONG).show()
                else { SitesStore.add(this, name, url); render() }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun showHistory() {
        val h = SitesStore.history(this)
        val b = AlertDialog.Builder(this).setTitle(getString(R.string.o_site_history))
        if (h.isEmpty()) b.setMessage(getString(R.string.o_no_results))
        else b.setItems(h.map { it.first.ifEmpty { it.second } }.toTypedArray()) { _, w -> startActivity(SiteBrowserActivity.intent(this, h[w].first, h[w].second)) }
            .setNeutralButton(getString(R.string.o_clear_all)) { _, _ -> SitesStore.clearHistory(this) }
        b.setNegativeButton(android.R.string.cancel, null).show()
    }
}
