package dfast.app.util

/** Length in seconds when the item has no usable duration text (the Music feed puts a play count there): a real stream's size divided by its bitrate. */
object DurationGuess {
    fun fromStream(sizeBytes: Long, tbrKbps: Double): Long =
        if (sizeBytes > 1000 && tbrKbps > 0) Math.round(sizeBytes * 8.0 / (tbrKbps * 1000.0)) else 0L
}
