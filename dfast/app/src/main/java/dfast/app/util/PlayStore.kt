package dfast.app.util

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject

/** One watchable item, identified by its page URL. Used by Recents, Favorites and Playlists. */
data class MediaRef(
    val url: String,
    val title: String,
    val author: String = "",
    val thumb: String = "",
    val duration: String = "",
    val audio: Boolean = false
) {
    fun toJson() = JSONObject().put("url", url).put("title", title).put("author", author)
        .put("thumb", thumb).put("duration", duration).put("audio", audio)

    companion object {
        fun fromJson(o: JSONObject) = MediaRef(
            o.optString("url"), o.optString("title"), o.optString("author"),
            o.optString("thumb"), o.optString("duration"), o.optBoolean("audio")
        )
    }
}

object PlayStore {
    private fun prefs(c: Context) = PreferenceManager.getDefaultSharedPreferences(c)

    private fun readList(c: Context, key: String): MutableList<MediaRef> {
        val a = try { JSONArray(prefs(c).getString(key, "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until a.length()).map { MediaRef.fromJson(a.getJSONObject(it)) }.toMutableList()
    }

    private fun writeList(c: Context, key: String, l: List<MediaRef>) {
        val a = JSONArray(); l.forEach { a.put(it.toJson()) }
        prefs(c).edit { putString(key, a.toString()) }
    }

    fun favorites(c: Context) = readList(c, "ps_favorites")
    fun isFavorite(c: Context, url: String) = favorites(c).any { it.url == url }
    fun toggleFavorite(c: Context, ref: MediaRef): Boolean {
        val l = favorites(c)
        val had = l.removeAll { it.url == ref.url }
        if (!had) l.add(0, ref)
        writeList(c, "ps_favorites", l)
        return !had
    }

    fun recents(c: Context) = readList(c, "ps_recents")
    fun addRecent(c: Context, ref: MediaRef) {
        val l = recents(c)
        l.removeAll { it.url == ref.url }
        l.add(0, ref)
        writeList(c, "ps_recents", l.take(60))
    }
    fun clearRecents(c: Context) = writeList(c, "ps_recents", emptyList())

    private fun playlistsJson(c: Context) = try { JSONObject(prefs(c).getString("ps_playlists", "{}")) } catch (e: Exception) { JSONObject() }
    fun playlistNames(c: Context): List<String> = playlistsJson(c).keys().asSequence().toList()
    fun playlist(c: Context, name: String): MutableList<MediaRef> {
        val a = playlistsJson(c).optJSONArray(name) ?: return mutableListOf()
        return (0 until a.length()).map { MediaRef.fromJson(a.getJSONObject(it)) }.toMutableList()
    }
    private fun savePlaylist(c: Context, name: String, l: List<MediaRef>?) {
        val o = playlistsJson(c)
        if (l == null) o.remove(name) else { val a = JSONArray(); l.forEach { a.put(it.toJson()) }; o.put(name, a) }
        prefs(c).edit { putString("ps_playlists", o.toString()) }
    }
    fun createPlaylist(c: Context, name: String) { if (name.isNotBlank() && !playlistNames(c).contains(name)) savePlaylist(c, name, emptyList()) }
    fun deletePlaylist(c: Context, name: String) = savePlaylist(c, name, null)
    fun addToPlaylist(c: Context, name: String, ref: MediaRef) {
        val l = playlist(c, name)
        if (l.none { it.url == ref.url }) l.add(ref)
        savePlaylist(c, name, l)
    }
    fun removeFromPlaylist(c: Context, name: String, url: String) {
        val l = playlist(c, name); l.removeAll { it.url == url }; savePlaylist(c, name, l)
    }
}
