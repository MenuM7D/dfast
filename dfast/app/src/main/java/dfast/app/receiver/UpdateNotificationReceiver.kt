package dfast.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.preference.PreferenceManager
import dfast.app.util.NotificationUtil

/** Non-opening actions of the "new version" notification. */
class UpdateNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        NotificationManagerCompat.from(c).cancel(NotificationUtil.NEW_APP_UPDATE_NOTIFICATION_ID)
        if (intent.action == ACTION_SKIP) {
            val v = intent.getStringExtra("version").orEmpty()
            if (v.isBlank()) return
            val p = PreferenceManager.getDefaultSharedPreferences(c)
            val list = p.getString("skip_updates", "")?.split(",")?.distinct()?.toMutableList() ?: mutableListOf()
            if (!list.contains(v)) list.add(v)
            // same preference the dialog's Skip writes, so tryGetNewVersion stops offering this version
            p.edit().putString("skip_updates", list.joinToString(",")).apply()
        }
        // ACTION_LATER only dismisses; the reminder policy continues
    }
    companion object {
        const val ACTION_LATER = "dfast.app.UPDATE_LATER"
        const val ACTION_SKIP = "dfast.app.UPDATE_SKIP"
    }
}
