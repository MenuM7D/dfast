package dfast.app.ui.account

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import dfast.app.R
import dfast.app.database.viewmodel.CookieViewModel
import dfast.app.ui.play.PlayUi
import dfast.app.util.GoogleAccount
import dfast.app.util.OrbitColors
import dfast.app.util.ThemeUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Account page: sign in with Google (in-app page), account card, "use for downloads" switch, sign out. */
class GoogleAccountActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private var web: WebView? = null
    private var captured = false
    private fun dp(n: Int) = PlayUi.dp(this, n)

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtil.updateTheme(this)
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.bg_orbit) }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars()); v.setPadding(0, b.top, 0, b.bottom); insets
        }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (web != null) { web = null; render() } else finish() }
        })
        render()
    }

    private fun ink() = OrbitColors.ink(this)

    private fun render() {
        root.removeAllViews()
        root.addView(PlayUi.header(this, getString(R.string.o_google_account)))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(16)) }
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        val signed = GoogleAccount.isSignedIn(this)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(18))
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(OrbitColors.glass2(this@GoogleAccountActivity)) }
        }
        card.addView(TextView(this).apply { text = if (signed) getString(R.string.o_signed_in) else getString(R.string.o_sign_in_title); textSize = 18f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(ink()) })
        card.addView(TextView(this).apply { text = if (signed) getString(R.string.o_signed_in_desc) else getString(R.string.o_sign_in_desc); textSize = 13f; setTextColor(OrbitColors.inkSoft(this@GoogleAccountActivity)); setPadding(0, dp(6), 0, 0) })
        body.addView(card)
        if (!signed) {
            body.addView(PlayUi.pill(this, getString(R.string.o_sign_in_google), true) { startSignIn() }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
            body.addView(TextView(this).apply { text = getString(R.string.o_sign_in_risk); textSize = 12f; setTextColor(OrbitColors.inkSoft(this@GoogleAccountActivity)); setPadding(0, dp(14), 0, 0) })
            return
        }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(18), 0, dp(4)) }
        row.addView(TextView(this).apply { text = getString(R.string.o_use_for_downloads); textSize = 15f; setTextColor(ink()) }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(androidx.appcompat.widget.SwitchCompat(this).apply {
            isChecked = GoogleAccount.useForDownloads(this@GoogleAccountActivity)
            setOnCheckedChangeListener { _, on -> GoogleAccount.setUseForDownloads(this@GoogleAccountActivity, on) }
        })
        body.addView(row)
        body.addView(TextView(this).apply { text = getString(R.string.o_use_for_downloads_hint); textSize = 12f; setTextColor(OrbitColors.inkSoft(this@GoogleAccountActivity)) })
        body.addView(PlayUi.pill(this, getString(R.string.o_sign_out), false) {
            val vm = ViewModelProvider(this)[CookieViewModel::class.java]
            lifecycleScope.launch(Dispatchers.IO) { GoogleAccount.signOut(this@GoogleAccountActivity, vm); withContext(Dispatchers.Main) { render() } }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(22) })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun startSignIn() {
        captured = false
        root.removeAllViews()
        root.addView(PlayUi.header(this, getString(R.string.o_sign_in_google)))
        val wv = WebView(this)
        web = wv
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        // a normal mobile Chrome identity: Google refuses sign-in inside a WebView that says so
        wv.settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                val host = android.net.Uri.parse(url ?: "").host ?: return
                if (captured || !host.endsWith("youtube.com")) return
                view.evaluateJavascript("(function(){try{return String(window.ytcfg&&ytcfg.get('LOGGED_IN'))}catch(e){return 'x'}})()") { r ->
                    if (captured || r == null || !r.contains("true")) return@evaluateJavascript
                    captured = true
                    val vm = ViewModelProvider(this@GoogleAccountActivity)[CookieViewModel::class.java]
                    lifecycleScope.launch(Dispatchers.IO) {
                        val ok = runCatching { GoogleAccount.saveSession(this@GoogleAccountActivity, vm, "") }.isSuccess
                        withContext(Dispatchers.Main) {
                            web = null
                            Toast.makeText(this@GoogleAccountActivity, getString(if (ok) R.string.o_signed_in else R.string.o_sign_in_failed), Toast.LENGTH_SHORT).show()
                            render()
                        }
                    }
                }
            }
        }
        root.addView(wv, LinearLayout.LayoutParams(-1, 0, 1f))
        wv.loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&hl=en&continue=" + java.net.URLEncoder.encode("https://m.youtube.com/", "UTF-8"))
    }

    override fun onDestroy() { web?.destroy(); super.onDestroy() }
}
