package dfast.app.util

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import dfast.app.R

/** Saves the last crash text synchronously so the next launch can offer it for sharing. */
object CrashRecorder {
    private const val PREFS = "orbit_crash"
    private const val KEY = "last"

    fun record(ctx: Context, t: Thread, e: Throwable) {
        kotlin.runCatching {
            val sw = java.io.StringWriter()
            e.printStackTrace(java.io.PrintWriter(sw))
            val text = "DFast crash\n" + java.util.Date() + "\nthread: " + t.name + "\nandroid: " + android.os.Build.VERSION.SDK_INT +
                " " + android.os.Build.MODEL + "\n\n" + sw.toString().take(12000)
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, text).commit()
        }
    }

    fun install(ctx: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            record(ctx, t, e)
            previous?.uncaughtException(t, e)
        }
    }

    fun pending(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    fun offerIfAny(a: Activity) {
        val text = pending(a) ?: return
        kotlin.runCatching {
            AlertDialog.Builder(a)
                .setTitle(R.string.o_crash_title)
                .setMessage(a.getString(R.string.o_crash_msg) + "\n\n" + text.lineSequence().drop(1).take(6).joinToString("\n"))
                .setPositiveButton(R.string.o_crash_share) { _, _ ->
                    a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
                    clear(a)
                }
                .setNeutralButton(R.string.o_crash_copy) { _, _ ->
                    (a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("crash", text))
                    Toast.makeText(a, R.string.o_copied, Toast.LENGTH_SHORT).show()
                    clear(a)
                }
                .setNegativeButton(R.string.o_crash_dismiss) { _, _ -> clear(a) }
                .setOnCancelListener { clear(a) }
                .show()
        }
    }
}
