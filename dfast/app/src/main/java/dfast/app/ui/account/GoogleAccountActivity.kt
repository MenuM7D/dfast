package dfast.app.ui.account

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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

/** Experimental YouTube account: intro with warning, in-app Google page, account card with separate switches. */
class GoogleAccountActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private var web: WebView? = null
    private var captured = false
    private var failed = false
    private fun dp(n: Int) = PlayUi.dp(this, n)
    private fun ink() = OrbitColors.ink(this)
    private fun soft() = OrbitColors.inkSoft(this)
    private val vm by lazy { ViewModelProvider(this)[CookieViewModel::class.java] }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtil.updateTheme(this)
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.bg_orbit) }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars()); v.setPadding(0, b.top, 0, b.bottom); insets
        }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (web != null) { web?.destroy(); web = null; render() } else finish() }
        })
        render()
    }

    private fun text(t: String, size: Float, bold: Boolean = false, color: Int = ink()) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun render() {
        root.removeAllViews()
        root.addView(PlayUi.header(this, getString(R.string.o_yt_account)))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(16)) }
        root.addView(android.widget.ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        if (GoogleAccount.isSignedIn(this)) account(body) else intro(body)
    }

    private fun intro(body: LinearLayout) {
        body.addView(text(getString(R.string.o_intro_title), 20f, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(24), 0, dp(14)) })
        listOf(R.drawable.ic_o_star to R.string.o_intro_1, R.drawable.ic_o_history to R.string.o_intro_2, R.drawable.ic_o_sync to R.string.o_intro_3).forEach { (ic, t) ->
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
            row.addView(android.widget.ImageView(this).apply {
                setImageResource(ic); setColorFilter(ink()); setPadding(dp(6), dp(6), dp(6), dp(6))
                background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(OrbitColors.glass2(this@GoogleAccountActivity)) }
            }, LinearLayout.LayoutParams(dp(32), dp(32)))
            row.addView(text(getString(t), 14f).apply { setPadding(dp(14), 0, 0, 0) })
            body.addView(row)
        }
        body.addView(text(getString(R.string.o_account_note), 12f, color = soft()).apply { gravity = Gravity.CENTER; setPadding(dp(8), 0, dp(8), 0) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        body.addView(PlayUi.pill(this, getString(R.string.o_sign_in_google), true) { startSignIn() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        body.addView(text(getString(R.string.o_not_now), 13f, color = soft()).apply { gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(8)); setOnClickListener { finish() } }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun switchRow(body: LinearLayout, title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, dp(4)) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(title, 15f)); col.addView(text(hint, 12f, color = soft()))
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(androidx.appcompat.widget.SwitchCompat(this).apply {
            val st = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = android.content.res.ColorStateList(st, intArrayOf(0xFFFFFFFF.toInt(), 0xFF8E8E9A.toInt()))
            trackTintList = android.content.res.ColorStateList(st, intArrayOf(0xFF7C5CFF.toInt(), 0xFF3A3A44.toInt()))
            trackTintMode = android.graphics.PorterDuff.Mode.SRC_ATOP
            isChecked = checked; setOnCheckedChangeListener { _, on -> onChange(on) }
        })
        body.addView(row)
    }

    private fun account(body: LinearLayout) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(OrbitColors.glass2(this@GoogleAccountActivity)) }
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val av = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; setImageResource(R.drawable.ic_account)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(OrbitColors.glass2(this@GoogleAccountActivity)) }
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: android.view.View, o: android.graphics.Outline) { o.setOval(0, 0, v.width, v.height) } }
        }
        val photo = GoogleAccount.accountPhoto(this)
        if (photo.isNotBlank()) runCatching { com.squareup.picasso.Picasso.get().load(photo).into(av) }
        top.addView(av, LinearLayout.LayoutParams(dp(72), dp(72)))
        val nm = GoogleAccount.accountName(this)
        if (nm.isBlank()) lifecycleScope.launch {
            val ch = withContext(Dispatchers.IO) { GoogleAccount.ensureAccount(this@GoogleAccountActivity) }
            if (ch) recreate()
        }
        top.addView(text(if (nm.isNotBlank()) nm else getString(R.string.o_signed_in), 18f, true).apply { setPadding(0, dp(10), 0, 0) })
        top.addView(text(getString(R.string.o_drawer_connected), 12f, color = soft()))
        card.addView(top)
        body.addView(card)
        switchRow(body, getString(R.string.o_personal_shelves), getString(R.string.o_personal_shelves_hint), GoogleAccount.shelvesOn(this)) { GoogleAccount.setShelves(this, it) }
        switchRow(body, getString(R.string.o_use_for_downloads), getString(R.string.o_use_for_downloads_hint), GoogleAccount.useForDownloads(this)) { on ->
            lifecycleScope.launch(Dispatchers.IO) { GoogleAccount.setUseForDownloads(this@GoogleAccountActivity, vm, on) }
        }
        switchRow(body, getString(R.string.o_use_for_age), getString(R.string.o_use_for_age_hint), androidx.preference.PreferenceManager.getDefaultSharedPreferences(this).getBoolean(dfast.app.util.StreamAccount.KEY, true)) { on ->
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this).edit().putBoolean(dfast.app.util.StreamAccount.KEY, on).apply()
        }
        body.addView(PlayUi.pill(this, getString(R.string.o_switch_account), false) {
            lifecycleScope.launch(Dispatchers.IO) { GoogleAccount.signOut(this@GoogleAccountActivity, vm); withContext(Dispatchers.Main) { startSignIn() } }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(20) })
        body.addView(text(getString(R.string.o_account_note), 11f, color = soft()).apply { setPadding(0, dp(10), 0, 0) })
        body.addView(PlayUi.pill(this, getString(R.string.o_sign_out_delete), false) {
            lifecycleScope.launch(Dispatchers.IO) { GoogleAccount.signOut(this@GoogleAccountActivity, vm); withContext(Dispatchers.Main) { render() } }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(24) })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun startSignIn() {
        captured = false; failed = false
        root.removeAllViews()
        root.addView(PlayUi.header(this, getString(R.string.o_sign_in_google)))
        val wv = WebView(this)
        web = wv
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.webViewClient = object : WebViewClient() {
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.isForMainFrame && errorResponse.statusCode == 403) showBlocked()
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame) showBlocked()
            }
            override fun onPageFinished(view: WebView, url: String?) {
                val host = android.net.Uri.parse(url ?: "").host ?: return
                if (url?.contains("disallowed_useragent") == true) { showBlocked(); return }
                if (captured || !host.endsWith("youtube.com")) return
                view.evaluateJavascript("(function(){try{return String(window.ytcfg&&ytcfg.get('LOGGED_IN'))}catch(e){return 'x'}})()") { r ->
                    if (captured || r == null || !r.contains("true")) return@evaluateJavascript
                    captured = true
                    var done = false
                    val complete = {
                        if (!done) { done = true
                        lifecycleScope.launch(Dispatchers.IO) {
                        val ok = runCatching { GoogleAccount.saveSession(this@GoogleAccountActivity, vm) }.isSuccess
                        withContext(Dispatchers.Main) {
                            web?.destroy(); web = null
                            Toast.makeText(this@GoogleAccountActivity, getString(if (ok) R.string.o_signed_in else R.string.o_sign_in_failed), Toast.LENGTH_SHORT).show()
                            render()
                        }
                        } }
                    }
                    view.addJavascriptInterface(AcctBridge { complete() }, "DFastAcct")
                    view.evaluateJavascript(ACCT_JS, null)
                    view.postDelayed({ complete() }, 5000)
                }
            }
        }
        root.addView(wv, LinearLayout.LayoutParams(-1, 0, 1f))
        wv.loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&continue=" + java.net.URLEncoder.encode("https://m.youtube.com/", "UTF-8"))
    }

    private fun showBlocked() {
        if (failed) return
        failed = true
        web?.destroy(); web = null
        root.removeAllViews()
        root.addView(PlayUi.header(this, getString(R.string.o_sign_in_google)))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(24), dp(18), dp(16)) }
        body.addView(text(getString(R.string.o_blocked_title), 18f, true))
        body.addView(text(getString(R.string.o_blocked_desc), 13f, color = soft()).apply { setPadding(0, dp(8), 0, dp(16)) })
        body.addView(PlayUi.pill(this, getString(R.string.o_use_browser_cookies), true) {
            startActivity(Intent(this, dfast.app.ui.more.cookies.WebViewActivity::class.java).putExtra("url", "https://www.youtube.com").putExtra("description", "YouTube"))
        })
        root.addView(body)
    }

    inner class AcctBridge(private val cb: () -> Unit) {
        @android.webkit.JavascriptInterface
        fun acct(n: String, ph: String) { GoogleAccount.setAccount(this@GoogleAccountActivity, n, ph); runOnUiThread { cb() } }
    }

    private val ACCT_JS = "(async function(){try{var r=await fetch('/getAccountSwitcherEndpoint',{credentials:'include'});var t=await r.text();var m=t.match(/\"accountName\":\\{\"simpleText\":\"([^\"]+)\"/);var p=t.match(/\"accountPhoto\":\\{\"thumbnails\":\\[\\{\"url\":\"([^\"]+)\"/);DFastAcct.acct(m?m[1]:'',p?p[1].replace(/\\\\u0026/g,'&'):'')}catch(e){DFastAcct.acct('','')}})()"

    override fun onDestroy() { web?.destroy(); super.onDestroy() }
}
