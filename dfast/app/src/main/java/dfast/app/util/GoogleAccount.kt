package dfast.app.util

import android.content.Context
import android.webkit.CookieManager
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import dfast.app.database.DBManager
import dfast.app.database.models.CookieItem

/** Optional Google/YouTube sign-in. Cookies stay on the device; "use for downloads" is off by default. */
object GoogleAccount {
    private const val DESC = "Google account"
    private fun p(c: Context) = PreferenceManager.getDefaultSharedPreferences(c)

    fun isSignedIn(c: Context) = p(c).getBoolean("google_signed_in", false)
    fun useForDownloads(c: Context) = p(c).getBoolean("google_use_dl", false)
    fun name(c: Context) = p(c).getString("google_name", "") ?: ""

    fun setUseForDownloads(c: Context, on: Boolean) {
        p(c).edit { putBoolean("google_use_dl", on); putBoolean("use_cookies", on && isSignedIn(c)) }
    }

    /** Runs [block] with the account cookies enabled for yt-dlp, then restores the user's download-cookie choice. */
    suspend fun <T> withCookies(c: Context, block: suspend () -> T): T {
        if (isSignedIn(c)) p(c).edit { putBoolean("use_cookies", true) }
        try { return block() } finally { p(c).edit { putBoolean("use_cookies", (isSignedIn(c) && useForDownloads(c)) || useOtherCookies(c)) } }
    }

    private fun useOtherCookies(c: Context): Boolean = DBManager.getInstance(c).cookieDao.getAllCookies().any { it.enabled && it.description != DESC }

    /** Saves the WebView session as yt-dlp cookies. Call off the main thread. */
    suspend fun saveSession(c: Context, vm: dfast.app.database.viewmodel.CookieViewModel, name: String) {
        CookieManager.getInstance().flush()
        val a = vm.getCookiesFromDB("https://www.youtube.com").getOrNull().orEmpty()
        val b = vm.getCookiesFromDB("https://accounts.google.com").getOrNull().orEmpty()
            .lines().filter { !it.startsWith("#") && it.isNotBlank() }.joinToString("\n")
        val content = a.trimEnd() + "\n" + b + "\n"
        val dao = DBManager.getInstance(c).cookieDao
        dao.getAllCookies().filter { it.description == DESC }.forEach { dao.delete(it.id) }
        dao.insert(CookieItem(0, "https://www.youtube.com", content, DESC, true))
        vm.updateCookiesFile()
        p(c).edit { putBoolean("google_signed_in", true); putString("google_name", name) }
    }

    suspend fun signOut(c: Context, vm: dfast.app.database.viewmodel.CookieViewModel) {
        val dao = DBManager.getInstance(c).cookieDao
        dao.getAllCookies().filter { it.description == DESC }.forEach { dao.delete(it.id) }
        vm.updateCookiesFile()
        CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush()
        p(c).edit { putBoolean("google_signed_in", false); putBoolean("google_use_dl", false); remove("google_name"); putBoolean("use_cookies", useOtherCookies(c)) }
        dfast.app.ui.home.HomeFeed.clearCache()
    }
}
