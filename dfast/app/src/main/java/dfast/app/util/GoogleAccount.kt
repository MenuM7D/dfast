package dfast.app.util

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import dfast.app.database.DBManager
import dfast.app.database.models.CookieItem
import java.io.File

/**
 * Experimental YouTube account. The login cookies live in their own private file.
 * Shelves use that file only (never the download cookies). Downloads use the account
 * only while "Use account for downloads" is on (off by default).
 */
object GoogleAccount {
    private const val DESC = "Google account"
    private fun p(c: Context) = PreferenceManager.getDefaultSharedPreferences(c)
    private fun file(c: Context) = File(c.filesDir, "google_cookies.txt")

    fun isSignedIn(c: Context) = p(c).getBoolean("google_signed_in", false) && file(c).exists()
    fun useForDownloads(c: Context) = p(c).getBoolean("google_use_dl", false)
    fun shelvesOn(c: Context) = isSignedIn(c) && p(c).getBoolean("google_shelves", true)

    /** Cookie file for the Home shelves, or null when shelves are off or signed out. */
    fun shelfCookiesPath(c: Context): String? = if (shelvesOn(c)) file(c).absolutePath else null

    fun accountName(c: Context): String = p(c).getString("google_name", "") ?: ""
    fun accountPhoto(c: Context): String = p(c).getString("google_photo", "") ?: ""
    fun setAccount(c: Context, name: String, photo: String) { p(c).edit { putString("google_name", name); putString("google_photo", photo) } }

    fun setShelves(c: Context, on: Boolean) {
        p(c).edit { putBoolean("google_shelves", on) }
        dfast.app.ui.home.HomeFeed.clearCache()
    }

    private fun otherCookiesEnabled(c: Context): Boolean =
        DBManager.getInstance(c).cookieDao.getAllCookies().any { it.enabled && it.description != DESC }

    /** Makes the downloads cookie file match the switch. Call off the main thread. */
    suspend fun syncDownloads(c: Context, vm: dfast.app.database.viewmodel.CookieViewModel) {
        val dao = DBManager.getInstance(c).cookieDao
        dao.getAllCookies().filter { it.description == DESC }.forEach { dao.delete(it.id) }
        val on = isSignedIn(c) && useForDownloads(c)
        if (on) dao.insert(CookieItem(0, "https://www.youtube.com", file(c).readText(), DESC, true))
        vm.updateCookiesFile()
        p(c).edit { putBoolean("use_cookies", on || otherCookiesEnabled(c)) }
    }

    suspend fun setUseForDownloads(c: Context, vm: dfast.app.database.viewmodel.CookieViewModel, on: Boolean) {
        p(c).edit { putBoolean("google_use_dl", on) }
        syncDownloads(c, vm)
    }

    /** Saves the WebView session to the private file. Call off the main thread. */
    suspend fun saveSession(c: Context, vm: dfast.app.database.viewmodel.CookieViewModel) {
        CookieManager.getInstance().flush()
        val a = vm.getCookiesFromDB("https://www.youtube.com").getOrNull().orEmpty()
        val b = vm.getCookiesFromDB("https://accounts.google.com").getOrNull().orEmpty()
            .lines().filter { !it.startsWith("#") && it.isNotBlank() }.joinToString("\n")
        file(c).writeText(a.trimEnd() + "\n" + b + "\n")
        p(c).edit { putBoolean("google_signed_in", true); putBoolean("google_shelves", true) }
        dfast.app.ui.home.HomeFeed.clearCache()
        syncDownloads(c, vm)
    }

    /** Removes everything: private cookie file, download cookie entry, WebView session, switches. */
    suspend fun signOut(c: Context, vm: dfast.app.database.viewmodel.CookieViewModel) {
        file(c).delete()
        p(c).edit { putBoolean("google_signed_in", false); putBoolean("google_use_dl", false); remove("google_shelves"); remove("google_name"); remove("google_photo") }
        syncDownloads(c, vm)
        CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        dfast.app.ui.home.HomeFeed.clearCache()
    }
}
