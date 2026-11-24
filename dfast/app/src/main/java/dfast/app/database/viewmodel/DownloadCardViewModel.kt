package dfast.app.database.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import dfast.app.R
import dfast.app.database.DBManager
import dfast.app.database.enums.DownloadType
import dfast.app.database.models.DownloadItem
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

class DownloadCardViewModel(application: Application) : AndroidViewModel(application) {
    var resultItem: ResultItem? = null
        private set

    var downloadItem: DownloadItem? = null
        private set

    fun setDownloadItem(item: DownloadItem?) {
        downloadItem = item
    }

    fun setResultItem(item: ResultItem?) {
        resultItem = item
    }
}