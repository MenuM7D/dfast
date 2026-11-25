package dfast.app.util

import android.content.Context
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import dfast.app.R

/** In-app playlists: pick an existing one or create a new one, then add one item or a whole list at once. */
object PlaylistPicker {
    fun show(ctx: Context, refs: List<MediaRef>, suggestedName: String = "") {
        if (refs.isEmpty()) return
        val names = PlayStore.playlistNames(ctx)
        val labels = (names + ctx.getString(R.string.play_new_playlist)).toTypedArray()
        AlertDialog.Builder(ctx).setTitle(ctx.getString(R.string.play_add_to_playlist)).setItems(labels) { _, w ->
            if (w < names.size) addAll(ctx, names[w], refs)
            else {
                val et = EditText(ctx).apply { setText(suggestedName) }
                AlertDialog.Builder(ctx).setTitle(ctx.getString(R.string.play_new_playlist)).setView(et).setPositiveButton(android.R.string.ok) { _, _ ->
                    val n = et.text.toString().trim()
                    if (n.isEmpty()) Toast.makeText(ctx, ctx.getString(R.string.o_name_empty), Toast.LENGTH_SHORT).show()
                    else { PlayStore.createPlaylist(ctx, n); addAll(ctx, n, refs) }
                }.setNegativeButton(android.R.string.cancel, null).show()
            }
        }.show()
    }

    private fun addAll(ctx: Context, name: String, refs: List<MediaRef>) {
        refs.forEach { PlayStore.addToPlaylist(ctx, name, it) }
        Toast.makeText(ctx, name + " (" + refs.size + ")", Toast.LENGTH_SHORT).show()
    }
}
