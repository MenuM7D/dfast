package dfast.app.util

/** The search chips that filter videos (pure rules, unit tested). */
object SearchFilter {
    enum class Kind { ALL, VIDEOS, SHORTS, LIVE, RECENT }
    const val SHORT_MAX_SEC = 60L
    const val RECENT_DAYS = 30L

    fun kindOf(chip: String): Kind = when (chip) { "videos" -> Kind.VIDEOS; "shorts" -> Kind.SHORTS; "live" -> Kind.LIVE; "recent" -> Kind.RECENT; else -> Kind.ALL }
    fun isVideoChip(chip: String) = kindOf(chip) != Kind.ALL

    /** durationSec: 0 for live or unknown. uploadedAtMs: null when YouTube gave no date. */
    fun keep(kind: Kind, durationSec: Long, live: Boolean, uploadedAtMs: Long?, nowMs: Long): Boolean = when (kind) {
        Kind.ALL -> durationSec > 0
        Kind.VIDEOS -> !live && durationSec > SHORT_MAX_SEC
        Kind.SHORTS -> !live && durationSec in 1..SHORT_MAX_SEC
        Kind.LIVE -> live
        Kind.RECENT -> !live && durationSec > SHORT_MAX_SEC && uploadedAtMs != null && nowMs - uploadedAtMs in 0..RECENT_DAYS * 86_400_000L
    }

    /** the extra words that steer YouTube's own ranking toward the chip */
    fun querySuffix(kind: Kind): String = when (kind) { Kind.SHORTS -> " #shorts"; Kind.LIVE -> " live"; else -> "" }
}
