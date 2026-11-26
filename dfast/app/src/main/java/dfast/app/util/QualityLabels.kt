package dfast.app.util

/** Video quality labels the way YouTube shows them (144p ... 2160p), never raw pixel heights like 1068 or 712. Pure logic, unit tested. */
object QualityLabels {
    val LADDER = listOf(144, 240, 360, 480, 720, 1080, 1440, 2160, 4320)

    /** The standard label for a stream: the "720p" / "1080p60" label when the source gives one, else the nearest standard step to the pixel height. 0 = unknown. */
    fun standard(height: Int, note: String? = null): Int {
        val fromNote = note?.let { Regex("^\\s*(\\d{3,4})p").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        if (fromNote != null && fromNote in LADDER) return fromNote
        if (height <= 0) return 0
        return LADDER.minByOrNull { kotlin.math.abs(it - height) } ?: 0
    }

    /** distinct labels, lowest first */
    fun ascending(labels: Collection<Int>): List<Int> = labels.filter { it > 0 }.distinct().sorted()

    fun text(label: Int) = "${label}p"
}
