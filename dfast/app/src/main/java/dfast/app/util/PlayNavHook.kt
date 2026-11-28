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
    fun takes(p: Player?): Boolean = (nextFn != null && (p == null || p.mediaItemCount <= 1)) || PlayChain.owns(p)

    fun next(p: Player?): Boolean = if (nextFn != null && (p == null || p.mediaItemCount <= 1)) (nextFn?.invoke() ?: false) else PlayChain.next(p)
    fun prev(p: Player?): Boolean = if (nextFn != null && (p == null || p.mediaItemCount <= 1)) (prevFn?.invoke() ?: false) else PlayChain.prev(p)
}
