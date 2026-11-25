package dfast.app.ui.browser

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dfast.app.receiver.ShareActivity
import dfast.app.R
import dfast.app.util.ThemeUtil

/** In-app browser: open a site, find a video, tap Download to hand the page to DFast. */
class BrowserActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var urlView: TextView

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtil.updateTheme(this)
        super.onCreate(savedInstanceState)
        val start = intent.getStringExtra("url") ?: "https://m.youtube.com"

        val root = FrameLayout(this)
        root.setBackgroundResource(R.drawable.bg_orbit)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply { setColor(Color.parseColor("#33FFFFFF")) }
        }
        val back = ImageButton(this).apply {
            setImageResource(R.drawable.ic_mini_close); setColorFilter(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT); contentDescription = "Close"
            setOnClickListener { finish() }
        }
        urlView = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f; maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = start
        }
        bar.addView(back, LinearLayout.LayoutParams(dp(44), dp(44)))
        bar.addView(urlView, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        col.addView(bar, LinearLayout.LayoutParams(-1, -2))

        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) { urlView.text = url }
        }
        web.webChromeClient = WebChromeClient()
        col.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        val dl = TextView(this).apply {
            text = "Download this page"
            setTextColor(Color.parseColor("#1B1470")); textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(14), dp(22), dp(14))
            background = GradientDrawable().apply { cornerRadius = 1000f; setColor(Color.WHITE) }
            elevation = dp(8).toFloat()
            setOnClickListener {
                val url = web.url ?: return@setOnClickListener
                startActivity(
                    Intent(this@BrowserActivity, ShareActivity::class.java)
                        .setAction(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, url)
                )
                finish()
            }
        }
        root.addView(dl, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(28) })
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, b.top, 0, b.bottom)
            insets
        }

        onBackPressedDispatcher.addCallback(this) {
            if (web.canGoBack()) web.goBack() else finish()
        }
        if (savedInstanceState == null) web.loadUrl(start)
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
