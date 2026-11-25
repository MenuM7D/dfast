package dfast.app.services

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.datasource.DataSourceBitmapLoader
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

/** Draws the track artwork as a vinyl disc over a dimmed artwork background, for the system media card. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DiscBitmapLoader(context: Context) : BitmapLoader {
    private val base = DataSourceBitmapLoader(context)

    override fun supportsMimeType(mimeType: String) = base.supportsMimeType(mimeType)
    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        Futures.transform(base.decodeBitmap(data), { disc(it) }, MoreExecutors.directExecutor())
    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
        Futures.transform(base.loadBitmap(uri), { disc(it) }, MoreExecutors.directExecutor())

    private fun disc(src: Bitmap): Bitmap {
        val n = 512
        val out = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        // background: artwork, center crop, dimmed
        val side = minOf(src.width, src.height)
        val sx = (src.width - side) / 2; val sy = (src.height - side) / 2
        c.drawBitmap(src, android.graphics.Rect(sx, sy, sx + side, sy + side), android.graphics.Rect(0, 0, n, n), p)
        c.drawColor(0x99000000.toInt())
        // vinyl
        val cx = n / 2f; val cy = n / 2f
        p.color = 0xFF141414.toInt(); c.drawCircle(cx, cy, n * 0.44f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f; p.color = 0x33FFFFFF
        var r = n * 0.20f
        while (r < n * 0.43f) { c.drawCircle(cx, cy, r, p); r += n * 0.025f }
        p.style = Paint.Style.FILL
        // label: artwork circle
        val lr = n * 0.17f
        val label = Bitmap.createBitmap((lr * 2).toInt(), (lr * 2).toInt(), Bitmap.Config.ARGB_8888)
        val lc = Canvas(label)
        val lp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        lc.drawCircle(lr, lr, lr, lp)
        lp.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        lc.drawBitmap(src, android.graphics.Rect(sx, sy, sx + side, sy + side), android.graphics.Rect(0, 0, label.width, label.height), lp)
        c.drawBitmap(label, cx - lr, cy - lr, p)
        p.color = 0xFF141414.toInt(); c.drawCircle(cx, cy, n * 0.018f, p)
        return out
    }
}
