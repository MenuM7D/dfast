package dfast.app.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import dfast.app.R

/** Shares the SOURCE link of a video or song (never a file) through the Android share sheet. */
object ShareLink {
    /** Only real web links are shared. */
    fun canShare(url: String): Boolean {
        val u = url.trim()
        return u.startsWith("http://") || u.startsWith("https://")
    }

    fun share(ctx: Context, url: String, title: String = ""): Boolean {
        if (!canShare(url)) return false
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url.trim())
            if (title.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, title)
        }
        val chooser = Intent.createChooser(send, ctx.getString(R.string.share))
        if (ctx !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(chooser) }.isSuccess
    }
}
