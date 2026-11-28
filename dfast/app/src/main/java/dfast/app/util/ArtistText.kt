package dfast.app.util

import dfast.app.database.models.ResultItem

/** The Music feed writes "Artist • 233 million plays" in one line. The artist line keeps only the name: parts after a bullet that hold digits (play counts, years) are dropped. */
object ArtistText {
    private val bullets = Regex("\\s*[•·‧∙]\\s*")

    fun clean(s: String?): String {
        if (s.isNullOrBlank()) return s.orEmpty()
        val parts = s.split(bullets).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return s
        val kept = parts.filterIndexed { i, p -> i == 0 || p.none { it.isDigit() } }
        return kept.joinToString(" • ")
    }

    fun cleanAll(items: List<ResultItem>) { items.forEach { it.author = clean(it.author) } }
}
