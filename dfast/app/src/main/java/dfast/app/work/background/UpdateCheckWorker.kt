package dfast.app.work.background

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dfast.app.App
import dfast.app.BuildConfig
import dfast.app.core.RuntimeManager
import dfast.app.util.FileUtil
import dfast.app.util.NotificationUtil
import dfast.app.util.UpdateUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

class UpdateCheckWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result {
        val updateUtil = UpdateUtil(App.instance)
        val notificationUtil = NotificationUtil(App.instance)
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

        val checkUpdatesApp = sharedPreferences.getBoolean("update_app", true)
        val checkUpdatesYTDL = sharedPreferences.getBoolean("auto_update_ytdlp", true)

        if (checkUpdatesApp) {
            val newRelease = updateUtil.tryGetNewVersion()
            if (newRelease.isSuccess && BuildConfig.FLAVOR == "github") {
                val rel = newRelease.getOrNull()!!
                // once per version: the same notice is not posted again every run
                if (sharedPreferences.getString("notified_app_version", "") != rel.tag_name) {
                    notificationUtil.showNewAppUpdate(rel.tag_name, dfast.app.util.ReleaseNotes.pick(rel.body, java.util.Locale.getDefault().toLanguageTag()))
                    sharedPreferences.edit().putString("notified_app_version", rel.tag_name).apply()
                }
            }

            val skipRemindingPackageUpdate = sharedPreferences.getStringSet("skip_reminding_package_update", setOf())!!.toMutableSet()
            RuntimeManager.packages.forEach { pkg ->
                val instance = pkg.plugin.getInstance()
                if (instance.bundledVersion.isNullOrBlank() && instance.downloadedVersion.isNullOrBlank()) return@forEach
                instance.getReleases().apply {
                    val releases = this.getOrElse { listOf() }
                    if (releases.isEmpty()) return@apply

                    val latestRelease = releases.first()
                    if (latestRelease.isBundled || latestRelease.isInstalled) return@apply
                    if (skipRemindingPackageUpdate.contains(latestRelease.tag_name)) return@apply

                    notificationUtil.showNewPackageUpdate(pkg.title, latestRelease.tag_name)

                    skipRemindingPackageUpdate.add(latestRelease.tag_name)
                    sharedPreferences.edit().putStringSet("skip_reminding_package_update", skipRemindingPackageUpdate).apply()
                }
            }
        }

        if (checkUpdatesYTDL) {
            val res = updateUtil.updateYTDL()
            when (res.status) {
                UpdateUtil.YTDLPUpdateStatus.DONE -> {
                    withContext(Dispatchers.IO) {
                        File(FileUtil.getInfoJsonPath(context)).deleteRecursively()
                    }
                }

                else -> {}
            }
        }

        return Result.success()
    }

    companion object {

        const val WORK_NAME = "dfast_update_check"

        fun schedule(context: Context, intervalMinutes: Long = 6 * 60L) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
                intervalMinutes, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setInitialDelay(minOf(intervalMinutes, 60L), TimeUnit.MINUTES)
                .build()

            // KEEP once this interval is set: re-scheduling on every app open must not push the next run back
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val same = prefs.getLong("update_worker_interval", 0L) == intervalMinutes
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                if (same) ExistingPeriodicWorkPolicy.KEEP else ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            if (!same) prefs.edit().putLong("update_worker_interval", intervalMinutes).apply()
        }
    }

}