package dfast.app.util

/**
 * The "what's new" text of a release, in the user's language (pure, unit tested).
 * A release body carries one block per language, invisible on GitHub:
 *   <!-- whatsnew:ar --> ...markdown... <!-- /whatsnew -->
 * Picks the exact tag (pt-BR), then the language (pt), then English. A body without blocks is shown as it is.
 */
object ReleaseNotes {
    private val block = Regex("<!--\\s*whatsnew:([A-Za-z0-9-]+)\\s*-->(.*?)<!--\\s*/whatsnew\\s*-->", RegexOption.DOT_MATCHES_ALL)
    private val arabicDialects = setOf("aeb", "apc", "arq", "ars", "arz", "egy")

    fun pick(body: String, languageTag: String): String {
        val blocks = block.findAll(body).associate { it.groupValues[1].lowercase() to it.groupValues[2].trim() }
        if (blocks.isEmpty()) return body
        var tag = languageTag.lowercase().replace('_', '-')
        val lang = tag.substringBefore('-').let { if (it in arabicDialects) "ar" else it }
        if (tag.substringBefore('-') in arabicDialects) tag = lang
        return blocks[tag] ?: blocks[lang] ?: blocks.entries.firstOrNull { it.key.substringBefore('-') == lang }?.value
            ?: blocks["en"] ?: blocks.values.first()
    }
}
