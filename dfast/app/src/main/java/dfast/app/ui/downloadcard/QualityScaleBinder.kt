package dfast.app.ui.downloadcard

import android.view.View
import com.google.android.material.card.MaterialCardView
import dfast.app.R
import dfast.app.database.models.Format
import dfast.app.util.QualityScale

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
}
