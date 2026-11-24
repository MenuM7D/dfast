package dfast.app.work.background

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dfast.app.App
import dfast.app.database.DBManager
import dfast.app.database.repository.DownloadRepository
import dfast.app.util.FileUtil
import dfast.app.util.NotificationUtil
import java.io.File

class CleanUpLeftoverDownloads(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result {
        val notificationUtil = NotificationUtil(App.Companion.instance)
        val id = System.currentTimeMillis().toInt()

        val notification = notificationUtil.createDeletingLeftoverDownloadsNotification()
        if (Build.VERSION.SDK_INT >= 33) {
            setForegroundAsync(
                ForegroundInfo(
                    id,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            )
        }else{
            setForegroundAsync(ForegroundInfo(id, notification))
        }

        val dbManager = DBManager.Companion.getInstance(context)
        val downloadRepo = DownloadRepository(dbManager.downloadDao)
        downloadRepo.deleteCancelled()
        downloadRepo.deleteErrored()

        val activeDownloadCount = downloadRepo.getActiveDownloadsCount()
        if (activeDownloadCount == 0){
            File(FileUtil.getCacheDownloadsPath(context)).deleteRecursively()
        }

        return Result.success()
    }

}