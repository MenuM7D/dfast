package dfast.app.util

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import dfast.app.database.enums.DownloadType
import dfast.app.database.models.Format
import java.io.File
import kotlin.math.roundToInt

/**
 * Single source for the format chips shown on download cards.
 *
 * Rule: a chip shows what the SAVED FILE is (or, before it exists, what the user REQUESTED).
 * It never shows the source stream's values for a file that was converted. If a value is not
 * known for sure, the chip is empty (and the UI hides it).
 */
object FormatTruth {
    data class Chips(val note: String = "", val codec: String = "")

    data class Probed(
        val container: String,
        val codec: String,
        val kbps: Int,
        val height: Int,
        val fps: Int,
        val sampleRate: Int,
        val hasVideo: Boolean
    ) {
        /** "160 KBPS" for audio files, "1080P" for video files, "" if unknown. */
        val note: String
            get() = if (hasVideo) (if (height > 0) "${height}P" else "")
            else if (kbps > 0) "$kbps KBPS" else ""
    }

    fun videoCodecLabel(raw: String): String {
        val c = raw.lowercase()
        return when {
            c == "" || c == "none" -> ""
            c.startsWith("avc") || c.contains("h264") -> "H.264"
            c.startsWith("hev") || c.startsWith("hvc") || c.contains("h265") -> "HEVC"
            c.startsWith("vp09") || c.startsWith("vp9") -> "VP9"
            c.startsWith("av01") || c.startsWith("av1") -> "AV1"
            else -> raw.uppercase()
        }
    }

    private fun audioCodecForContainer(container: String): String = when (container.lowercase()) {
        "mp3" -> "MP3"
        "m4a", "aac" -> "AAC"
        "opus" -> "OPUS"
        "flac" -> "FLAC"
        "wav" -> "PCM"
        else -> "" // ogg/webm/mka/empty: codec depends on the source, not known before the file exists
    }

    private fun isLossy(container: String) = container.lowercase() in listOf("mp3", "m4a", "aac", "opus", "ogg")

    private fun cleanBitrate(b: String): String {
        val digits = b.filter { it.isDigit() }
        return if (digits.isEmpty() || digits.toIntOrNull() == 0) "" else "$digits KBPS"
    }

    /** Chips for a download that has not produced a file yet (queued, scheduled, active, duplicate check, logs). */
    fun requested(
        type: DownloadType,
        container: String,
        format: Format,
        audioBitrate: String,
        recodeVideo: Boolean = false
    ): Chips {
        val hasVideoStream = format.vcodec != "" && format.vcodec != "none"
        val isVideo = type == DownloadType.video || (type == DownloadType.auto && hasVideoStream)
        return when {
            type == DownloadType.command -> Chips()
            isVideo -> Chips(
                note = if (format.format_note == "?") "" else format.format_note.uppercase().replace("\n", " "),
                codec = if (recodeVideo) "" else videoCodecLabel(format.vcodec)
            )
            else -> {
                val c = container.ifEmpty { "" }
                Chips(
                    note = if (isLossy(c)) cleanBitrate(audioBitrate) else "",
                    codec = audioCodecForContainer(c)
                )
            }
        }
    }

    /** Reads the real file. Returns null if it is missing or cannot be read. */
    fun probe(file: File): Probed? {
        if (!file.exists() || file.length() <= 0L) return null
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(file.absolutePath)
            var codec = ""
            var kbps = 0
            var height = 0
            var fps = 0
            var sr = 0
            var hasVideo = false
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    hasVideo = true
                    height = if (f.containsKey(MediaFormat.KEY_HEIGHT)) f.getInteger(MediaFormat.KEY_HEIGHT) else 0
                    if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) fps = runCatching { f.getInteger(MediaFormat.KEY_FRAME_RATE) }.getOrDefault(0)
                    if (!hasVideo || codec == "" ) codec = mimeLabel(mime)
                } else if (mime.startsWith("audio/")) {
                    if (!hasVideo && codec == "") codec = mimeLabel(mime)
                    if (f.containsKey(MediaFormat.KEY_BIT_RATE)) kbps = f.getInteger(MediaFormat.KEY_BIT_RATE) / 1000
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                }
            }
            if (hasVideo) {
                // codec of the video track
                for (i in 0 until ex.trackCount) {
                    val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/")) { codec = mimeLabel(mime); break }
                }
            }
            if (!hasVideo && kbps <= 0) {
                // audio only file without a stated track bitrate: average = bytes / duration
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(file.absolutePath)
                    val b = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
                    kbps = (b / 1000.0).roundToInt()
                } catch (_: Exception) {
                } finally { r.release() }
            }
            Probed(file.extension.lowercase(), codec, kbps, height, fps, sr, hasVideo)
        } catch (e: Exception) {
            null
        } finally {
            ex.release()
        }
    }

    private fun mimeLabel(mime: String): String = when (mime.lowercase()) {
        "audio/mpeg", "audio/mpeg-l1", "audio/mpeg-l2" -> "MP3"
        "audio/mp4a-latm" -> "AAC"
        "audio/opus" -> "OPUS"
        "audio/vorbis" -> "VORBIS"
        "audio/flac" -> "FLAC"
        "audio/raw" -> "PCM"
        "audio/ac3" -> "AC3"
        "video/avc" -> "H.264"
        "video/hevc" -> "HEVC"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/x-vnd.on2.vp8" -> "VP8"
        "video/av01" -> "AV1"
        else -> ""
    }

    /** Rewrites a Format (about to be stored in history) so it describes the real saved file. Unknown values are cleared. */
    fun describeSaved(format: Format, file: File): Format {
        val p = probe(file)
        format.filesize = if (file.exists()) file.length() else format.filesize
        format.container = file.extension
        // clear every source-stream value first so nothing from the source can leak through
        format.encoding = ""
        format.format_note = ""
        format.tbr = ""
        format.fps = ""
        format.asr = ""
        if (p != null) {
            format.encoding = p.codec
            format.vcodec = if (p.hasVideo) p.codec else "none"
            format.acodec = if (p.hasVideo) "" else p.codec
            format.format_note = p.note
            if (p.kbps > 0) format.tbr = p.kbps.toString()
            if (p.fps > 0) format.fps = p.fps.toString()
            if (p.sampleRate > 0) format.asr = p.sampleRate.toString()
        } else {
            format.vcodec = ""
            format.acodec = ""
        }
        return format
    }
}
