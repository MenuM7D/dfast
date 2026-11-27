package dfast.app.ui.play

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dfast.app.R
import dfast.app.receiver.ShareActivity
import dfast.app.util.OrbitColors
import dfast.app.util.SitesStore
import dfast.app.util.ThemeUtil

/** Opens a site inside the app. The Download button sends the current page address to the normal download sheet. */
class SiteBrowserActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private fun dp(n: Int) = PlayUi.dp(this, n)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtil.updateTheme(this)
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra("name") ?: ""
        val url = intent.getStringExtra("url") ?: "about:blank"
        SitesStore.addHistory(this, name, url)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.bg_orbit) }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(6), dp(12), dp(6)) }
        bar.addView(dfast.app.util.OIcons.view(this, R.drawable.ic_o_arrow_back, 22, OrbitColors.ink(this), 8) { finish() })
        val title = TextView(this).apply { text = name; textSize = 17f; setTextColor(OrbitColors.ink(context)); setTypeface(typeface, android.graphics.Typeface.BOLD); maxLines = 1 }
        bar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(bar)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    val u = r.url.toString(); return !(u.startsWith("http://") || u.startsWith("https://"))
                }
            }
        }
        val fab = TextView(this).apply {
            text = getString(R.string.o_download_video); textSize = 14f
            setTextColor(0xFF14102E.toInt()); setTypeface(typeface, android.graphics.Typeface.BOLD)
            dfast.app.util.OIcons.start(this, R.drawable.ic_o_download, 0xFF14102E.toInt())
            setPadding(dp(20), dp(13), dp(20), dp(13)); elevation = dp(6).toFloat()
            background = GradientDrawable().apply { cornerRadius = 1000f; setColor(0xFF9D86FF.toInt()) }
            setOnClickListener {
                val u = web.url ?: ""
                if (u.isEmpty() || u == "about:blank") Toast.makeText(context, getString(R.string.o_open_result_first), Toast.LENGTH_LONG).show()
                else runCatching { startActivity(Intent(context, ShareActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra("inapp", true).putExtra(Intent.EXTRA_TEXT, u)) }
            }
        }
        val frame = FrameLayout(this).apply {
            addView(web, FrameLayout.LayoutParams(-1, -1))
            addView(fab, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(24) })
        }
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars()); v.setPadding(0, b.top, 0, b.bottom); insets
        }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (web.canGoBack()) web.goBack() else finish() }
        })
        web.loadUrl(url)
    }

    override fun onDestroy() { runCatching { web.destroy() }; super.onDestroy() }

    companion object {
        fun intent(c: Context, name: String, url: String) = Intent(c, SiteBrowserActivity::class.java).putExtra("name", name).putExtra("url", url)
    }
}
