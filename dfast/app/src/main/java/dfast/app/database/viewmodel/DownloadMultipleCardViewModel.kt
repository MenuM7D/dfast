package dfast.app.database.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.cachedIn
import androidx.preference.PreferenceManager
import dfast.app.R
import dfast.app.database.DBManager
import dfast.app.database.dao.DownloadDao
import dfast.app.database.enums.DownloadType
import dfast.app.database.models.DownloadItem
import dfast.app.database.models.DownloadItemConfigureMultiple
import dfast.app.database.models.Format
import dfast.app.database.models.FormatRecyclerView
import dfast.app.database.models.ResultItem
import dfast.app.database.repository.DownloadRepository
import dfast.app.ui.downloadcard.FormatSelectionBottomSheetDialog.FormatCategory
import dfast.app.ui.downloadcard.FormatSelectionBottomSheetDialog.FormatSorting
import dfast.app.ui.downloadcard.FormatTuple
import dfast.app.ui.downloadcard.MultipleItemFormatTuple
import dfast.app.util.Extensions.isYoutubeURL
import dfast.app.util.FileUtil
import dfast.app.util.FormatUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

class DownloadMultipleCardViewModel(application: Application) : AndroidViewModel(application) {
    private val dbManager: DBManager = DBManager.getInstance(application)
    private val dao: DownloadDao = dbManager.downloadDao
    private val repository: DownloadRepository = DownloadRepository(dao)

    val totalProcessingFileSize: Flow<Long> = repository.getProcessingSizeMetadata()
        .map { items ->
            items.sumOf { item ->
                if (item.type == DownloadType.video) {
                    if (item.format.filesize <= 5L) 0L
                    else {
                        val audioSize = if (item.videoPreferences.removeAudio) 0L
                        else {
                            item.allFormats
                                .filter { f -> item.videoPreferences.audioFormatIDs.contains(f.format_id) }
                                .sumOf { f -> f.filesize }
                        }
                        item.format.filesize + audioSize
                    }
                } else if (item.type == DownloadType.audio) {
                    item.format.filesize
                } else 0L
            }
        }
        .flowOn(Dispatchers.Default)

    private var currentPagingSource: PagingSource<Int, DownloadItemConfigureMultiple>? = null
    val processingDownloads: Flow<PagingData<DownloadItemConfigureMultiple>> = Pager(
        config = PagingConfig(
            pageSize = 40,
            enablePlaceholders = false,
            initialLoadSize = 80
        ),
        pagingSourceFactory = { dao.getProcessingDownloads().also { currentPagingSource = it } }
    ).flow.cachedIn(viewModelScope)

    val processingIds: Flow<List<Long>> = dao.getProcessingDownloadsIds()

    private val _mainProcessingItemId = MutableStateFlow<Long?>(null)
    fun setMainProcessingItem(id: Long?) {
        _mainProcessingItemId.value = id
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val firstProcessingDownload: Flow<DownloadItem?> = _mainProcessingItemId.flatMapLatest { id ->
        if (id != null) {
            dao.getDownloadByIdFlow(id)
        } else {
            dao.getFirstProcessingDownloadFlow()
        }
    }.flowOn(Dispatchers.IO)

    fun refreshProcessingDownloads() {
        currentPagingSource?.invalidate()
    }

}