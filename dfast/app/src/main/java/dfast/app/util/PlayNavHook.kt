package dfast.app.util

import androidx.media3.common.Player

/**
 * Lets the media card, the mini player and the lock screen follow the screen's "related" chain.
 * PlayerActivity registers itself while it owns the playing song. The card asks [takes] first:
 * only when the service holds a single viewer song (no background queue) does the chain handle next/previous;
 * with a real background queue the old queue behaviour is untouched.
 */
object PlayNavHook {
    @Volatile private var owner: Any? = null
    @Volatile private var nextFn: (() -> Boolean)? = null
    @Volatile private var prevFn: (() -> Boolean)? = null

    fun register(o: Any, next: () -> Boolean, prev: () -> Boolean) { owner = o; nextFn = next; prevFn = prev }
    fun unregister(o: Any) { if (owner === o) { owner = null; nextFn = null; prevFn = null } }

    /** true when the related chain should drive the card: the screen is registered and the service has at most one song */
    fun takes(p: Player?): Boolean = nextFn != null && (p == null || p.mediaItemCount <= 1)

    fun next(p: Player?): Boolean = takes(p) && (nextFn?.invoke() ?: false)
    fun prev(p: Player?): Boolean = takes(p) && (prevFn?.invoke() ?: false)
}
