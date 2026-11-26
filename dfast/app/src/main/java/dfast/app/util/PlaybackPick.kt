package dfast.app.util

/**
 * Small pure rules the player uses to pick a saved file and to label the offline button.
 * Kept free of Android classes so they can be unit tested.
 */
object PlaybackPick {
    private val NOT_MEDIA = setOf("jpg", "jpeg", "png", "webp", "gif", "srt", "vtt", "ass", "lrc", "txt", "json", "description", "nfo", "part", "ytdl", "tmp")

    /** False for thumbnails, subtitles and temp files that can sit in a download's file list next to the media file. */
    fun isMediaPath(path: String): Boolean {
        if (path.isBlank()) return false
        if (path.startsWith("content://")) return true // cannot tell from the uri; the player decides
        val name = path.substringAfterLast('/')
        val ext = if (name.contains('.')) name.substringAfterLast('.').lowercase() else ""
        return ext !in NOT_MEDIA
    }

    /** In video mode a saved audio-only entry must not be used (sound with a black picture). Audio mode takes any. */
    fun typeMatches(entryIsAudio: Boolean, wantVideo: Boolean): Boolean = !wantVideo || !entryIsAudio

    enum class SaveState { SAVE, DOWNLOADED, OFFLINE }

    /** Offline copy in the app's own folder wins; any other local file is a normal download; otherwise nothing is saved. */
    fun saveState(hasOfflineCopy: Boolean, hasLocalFile: Boolean): SaveState =
        if (hasOfflineCopy) SaveState.OFFLINE else if (hasLocalFile) SaveState.DOWNLOADED else SaveState.SAVE
}
