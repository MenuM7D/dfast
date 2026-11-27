package dfast.app.util

/**
 * Choosing links out of ONE yt-dlp format listing (pure, unit tested).
 * A listing holds the link of every format, so the first play, the quality list and every later quality pick can come out of
 * the same single extraction instead of one yt-dlp process each.
 */
data class Fmt(val url: String?, val height: Int, val vcodec: String, val acodec: String, val note: String?, val container: String, val tbr: Double)

object QualityPick {
    private fun hasVideo(f: Fmt) = f.vcodec.isNotBlank() && f.vcodec != "none"
    private fun hasAudio(f: Fmt) = f.acodec.isNotBlank() && f.acodec != "none"
    private fun usable(f: Fmt) = !f.url.isNullOrBlank() && !f.url.contains(".m3u8") && !f.url.contains("/manifest/")
    private fun avc(f: Fmt) = f.vcodec.startsWith("avc", true) || f.vcodec.contains("h264", true)

    /** [video link, audio link] (or one muxed link) for exactly this label; empty when the listing has none. */
    fun pairFor(fs: List<Fmt>, label: Int): List<String> {
        val vids = fs.filter { usable(it) && hasVideo(it) && it.height > 0 && QualityLabels.standard(it.height, it.note) == label }
        if (vids.isEmpty()) return emptyList()
        val only = vids.filter { !hasAudio(it) }
        val audio = fs.filter { usable(it) && hasAudio(it) && !hasVideo(it) }
            .sortedWith(compareByDescending<Fmt> { it.container == "m4a" || it.acodec.startsWith("mp4a") }.thenByDescending { it.tbr }).firstOrNull()
        if (only.isNotEmpty() && audio != null) {
            val v = only.sortedWith(compareByDescending<Fmt> { avc(it) }.thenByDescending { it.tbr }).first()
            return listOf(v.url!!, audio.url!!)
        }
        val muxed = vids.filter { hasAudio(it) }.maxByOrNull { it.tbr }
        return if (muxed != null) listOf(muxed.url!!) else emptyList()
    }

    /** The label for the first play: the best one up to 720p that really has a playable pair, else the lowest above it (same idea as yt-dlp's "res:720"). 0 when nothing is playable. */
    fun defaultLabel(fs: List<Fmt>, cap: Int = 720): Int {
        val labels = QualityLabels.ascending(fs.filter { usable(it) && hasVideo(it) && it.height > 0 }.map { QualityLabels.standard(it.height, it.note) })
            .filter { pairFor(fs, it).isNotEmpty() }
        return labels.lastOrNull { it <= cap } ?: labels.firstOrNull { it > cap } ?: 0
    }

    fun defaultPair(fs: List<Fmt>): List<String> { val l = defaultLabel(fs); return if (l > 0) pairFor(fs, l) else emptyList() }
}
