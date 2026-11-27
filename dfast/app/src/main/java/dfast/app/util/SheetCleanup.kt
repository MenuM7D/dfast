package dfast.app.util

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Cleanup that must still run when the screen (and its ViewModel) is going away.
 * ViewModel.viewModelScope is cancelled once the owner is destroyed, so a delete launched through it from a
 * dismiss/destroy callback can silently never run. These go straight to the DAO on a process-level scope.
 */
object SheetCleanup {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun deleteProcessingDownloads(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching { dfast.app.database.DBManager.getInstance(app).downloadDao.deleteProcessing() }
                .onFailure { android.util.Log.d("DFastInApp", "processing cleanup failed: $it") }
        }
    }

    fun deleteDuplicateStatusDownloads(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching { dfast.app.database.DBManager.getInstance(app).downloadDao.deleteWithDuplicateStatus() }
                .onFailure { android.util.Log.d("DFastInApp", "duplicate cleanup failed: $it") }
        }
    }
}
