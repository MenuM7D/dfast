package dfast.app.util

/** Pure logic for the quality scale: real levels only, highest first, at most [MAX] dots, never a made-up level. */
object QualityScale {
    const val MAX = 7
    data class Level(val label: String, val rank: Int)
    data class Result(val labels: List<String>, val selected: Int)

    /** rank = height in pixels (video) or bitrate in kbps (audio). Returns null when there is nothing to compare (fewer than 2 distinct levels). */
    fun build(levels: List<Level>, chosenRank: Int?): Result? {
        val distinct = levels.filter { it.rank > 0 }.groupBy { it.rank }.map { it.value.first() }.sortedByDescending { it.rank }
        if (distinct.size < 2) return null
        val sel = if (chosenRank == null) 0 else distinct.indices.minByOrNull { Math.abs(distinct[it].rank - chosenRank) } ?: 0
        var shown = distinct.indices.toList()
        if (shown.size > MAX) {
            val keep = linkedSetOf(0, distinct.size - 1, sel)
            var k = 1
            while (keep.size < MAX && k < distinct.size - 1) { keep.add(Math.round(k * (distinct.size - 1).toFloat() / (MAX - 1)).coerceIn(0, distinct.size - 1)); k++ }
            shown = keep.sorted()
        }
        return Result(shown.map { distinct[it].label }, shown.indexOf(sel))
    }
}
