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
import dfast.app.util.OrbitColors
import dfast.app.util.ThemeUtil

/** In-app browser: open a site, find a video, tap Download to hand the page to DFast. */
class BrowserActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var urlView: TextView
    private lateinit var dlButton: TextView
    @Volatile private var mediaSeen = false
    @Volatile private var lastMedia: String? = null

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
            background = GradientDrawable().apply { setColor(OrbitColors.glass2(this@BrowserActivity)) }
        }
        val back = ImageButton(this).apply {
            setImageResource(R.drawable.ic_mini_close); setColorFilter(OrbitColors.ink(this@BrowserActivity))
            setBackgroundColor(Color.TRANSPARENT); contentDescription = getString(R.string.o_close)
            setOnClickListener { finish() }
        }
        urlView = TextView(this).apply {
            setTextColor(OrbitColors.ink(this@BrowserActivity)); textSize = 13f
            text = start
        }
        bar.addView(back, LinearLayout.LayoutParams(dp(44), dp(44)))
        bar.addView(urlView, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        col.addView(bar, LinearLayout.LayoutParams(-1, -2))

        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                mediaSeen = false
                lastMedia = null
                urlView.text = url
                dlButton.text = getString(R.string.browser_download_page)
            }
            override fun onPageFinished(view: WebView, url: String) { urlView.text = url }
            override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                val u = request.url.toString().substringBefore('?').lowercase()
                if (!mediaSeen && (u.endsWith(".mp4") || u.endsWith(".m3u8") || u.endsWith(".webm") || u.endsWith(".mp3") || u.endsWith(".m4a") || u.endsWith(".mpd"))) {
                    mediaSeen = true
                    lastMedia = request.url.toString()
                    runOnUiThread { dlButton.text = getString(R.string.browser_download_found) }
                }
                return super.shouldInterceptRequest(view, request)
            }
        }
        web.webChromeClient = WebChromeClient()
        col.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        val dl = TextView(this).apply {
            text = getString(R.string.browser_download_page)
            setTextColor(OrbitColors.pillText(this@BrowserActivity)); textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(14), dp(22), dp(14))
            background = GradientDrawable().apply { cornerRadius = 1000f; setColor(OrbitColors.pill(this@BrowserActivity)) }
            elevation = dp(8).toFloat()
            setOnClickListener { sendToDownload() }
        }
        dlButton = dl
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

    private fun sendToDownload() {
        val pageUrl = web.url ?: return
        val uri = android.net.Uri.parse(pageUrl)
        val bareHome = (uri.path.isNullOrEmpty() || uri.path == "/") && uri.query.isNullOrEmpty()
        val media = lastMedia
        val target = when {
            bareHome && media != null -> media
            bareHome -> {
                android.widget.Toast.makeText(this, getString(R.string.o_browser_open_video), android.widget.Toast.LENGTH_LONG).show()
                return
            }
            else -> pageUrl
        }
        val cookieStr = android.webkit.CookieManager.getInstance().getCookie(pageUrl)
        val vm = androidx.lifecycle.ViewModelProvider(this)[dfast.app.database.viewmodel.CookieViewModel::class.java]
        Thread {
            // give yt-dlp the login the user just used in this browser
            if (!cookieStr.isNullOrBlank()) kotlin.runCatching {
                val content = vm.getCookiesFromDB(pageUrl).getOrNull()
                if (!content.isNullOrBlank()) {
                    kotlinx.coroutines.runBlocking {
                        vm.insert(dfast.app.database.models.CookieItem(0, pageUrl, content, "Browser: " + (uri.host ?: ""), true))
                    }
                    vm.updateCookiesFile()
                    androidx.preference.PreferenceManager.getDefaultSharedPreferences(this).edit().putBoolean("use_cookies", true).apply()
                }
            }
            runOnUiThread {
                kotlin.runCatching {
                    startActivity(
                        Intent(this, ShareActivity::class.java)
                            .setAction(Intent.ACTION_SEND).setType("text/plain").putExtra("inapp", true)
                            .putExtra(Intent.EXTRA_TEXT, target)
                    )
                    android.widget.Toast.makeText(this, getString(R.string.o_browser_added), android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
