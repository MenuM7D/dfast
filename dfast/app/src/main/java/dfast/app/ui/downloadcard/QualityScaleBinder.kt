package dfast.app.ui.downloadcard

import android.view.View
import com.google.android.material.card.MaterialCardView
import dfast.app.R
import dfast.app.database.models.Format
import dfast.app.util.QualityScale
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object QualityScaleBinder {
    private fun generic(f: Format) = f.format_id.contains("generic") || f.format_note.contains("dfastgeneric") || f.format_id == "best" || f.format_id == "worst" || f.format_id == "ba" || f.format_id == "bv"
    private fun isVideo(f: Format) = f.vcodec.isNotBlank() && f.vcodec != "none"
    private fun tbr(f: Format) = f.tbr?.removeSuffix("k")?.toDoubleOrNull() ?: 0.0

    fun bind(card: MaterialCardView, all: List<Format>, chosen: Format) {
        val v = card.findViewById<QualityScaleView>(R.id.quality_scale) ?: return
        val real = all.filter { !generic(it) }
        val video = isVideo(chosen) || (generic(chosen) && real.any { isVideo(it) } && real.none { !isVideo(it) && tbr(it) > 0 && chosen.format_id == "ba" })
        val levels: List<QualityScale.Level>; val rank: Int?
        if (video) {
            levels = real.filter { isVideo(it) && (it.height ?: 0) > 0 }.map { QualityScale.Level(if ((it.height ?: 0) >= 2160) "4K" else "${it.height}p", it.height ?: 0) }
            rank = if (generic(chosen)) null else chosen.height
        } else {
            levels = real.filter { !isVideo(it) && tbr(it) > 0 }.map { val k = Math.round(tbr(it)).toInt(); QualityScale.Level("${k}k", k) }
            rank = if (generic(chosen)) null else Math.round(tbr(chosen)).toInt().takeIf { it > 0 }
        }
        val r = QualityScale.build(levels, rank)
        if (r == null) v.visibility = View.GONE else { v.set(r.labels, r.selected); v.visibility = View.VISIBLE }
    }

    /** the real quality levels fetched in the background for this sheet (never saved, never changes the chosen format) */
    class Fetched { @Volatile var list: List<Format> = emptyList() }
    private val tried = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun levelCount(all: List<Format>) = all.filter { !generic(it) }.map { if (isVideo(it)) (it.height ?: 0) else Math.round(tbr(it)).toInt() }.filter { it > 0 }.toSet().size

    /** the item's own list when it has at least 2 real levels, else the fetched one */
    fun levelsSource(own: List<Format>, fetched: List<Format>): List<Format> = if (levelCount(own) >= 2 || fetched.isEmpty()) own else fetched

    /**
     * An item opened without a list of real qualities (a new source) gets one in the background, once, so the scale can show real levels.
     * Only the scale uses it: the chosen format, the saved item and the format sheet are untouched. Failure or nothing real = the card stays as it is, scale hidden.
     */
    fun warm(owner: LifecycleOwner, card: MaterialCardView, url: String, fetched: Fetched, own: () -> List<Format>, chosen: () -> Format, fetch: suspend (String) -> List<Format>) {
        if (!url.startsWith("http") || levelCount(own()) >= 2 || !tried.add(url)) return
        owner.lifecycleScope.launch(Dispatchers.IO) {
            val res = runCatching { fetch(url) }.getOrDefault(emptyList()).filter { it.format_note != "storyboard" }
            if (levelCount(res) < 2) { tried.remove(url); return@launch }
            withContext(Dispatchers.Main) {
                fetched.list = res
                bind(card, levelsSource(own(), res), chosen())
            }
        }
    }
}
