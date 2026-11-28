package dfast.app.util

/** Text for the system media card: one line, no line breaks or stray spaces or invisible marks that make the system card clip or overlap it. */
object CardText {
    fun oneLine(t: CharSequence?): String? =
        t?.toString()?.replace(Regex("[\\u0000-\\u001F\\u007F\\u200B\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF\\u2028\\u2029]+"), " ")
            ?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }
}
