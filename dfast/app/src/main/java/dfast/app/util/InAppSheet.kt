package dfast.app.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the in-app download sheet needs from storage. Kept tiny so the rule below can be tested without Android. */
interface InAppRowStore {
    /** Removes every row in the shared result table. */
    suspend fun deleteAllRows()
    /** True when an in-app row was created and not yet removed (survives a killed process). */
    var pendingMarker: Boolean
}

/**
 * Rule for the row the in-app download sheet creates (Music tab, search results, player):
 *  - while the sheet is open, Home ignores the result table, so the page behind the sheet does not change;
 *  - when the sheet closes, the row is removed for real;
 *  - if the app was killed with the sheet open, the next Home start removes the leftover row.
 * Sheets opened from other apps (share intent) do not use this and behave as before.
 */
object InAppSheet {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active

    fun begin() { _active.value = true }

    /** The sheet inserted its own row. The marker is saved first, so a kill cannot leave an unmarked row. */
    fun rowCreated(store: InAppRowStore) { store.pendingMarker = true }

    /** The sheet closed. Remove the row if one was made, then let Home look at the table again. */
    suspend fun end(store: InAppRowStore) {
        try {
            if (store.pendingMarker) { store.deleteAllRows(); store.pendingMarker = false }
        } finally {
            _active.value = false
        }
    }

    /** Home start: nothing is open, but a marker is left from a killed or crashed sheet. */
    suspend fun recover(store: InAppRowStore): Boolean {
        if (_active.value || !store.pendingMarker) return false
        store.deleteAllRows(); store.pendingMarker = false
        return true
    }

    internal fun resetForTest() { _active.value = false }
}

class DbInAppRowStore(private val context: android.content.Context) : InAppRowStore {
    private val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    override var pendingMarker: Boolean
        get() = prefs.getBoolean(KEY, false)
        set(v) { prefs.edit().putBoolean(KEY, v).commit() }
    override suspend fun deleteAllRows() {
        dfast.app.database.DBManager.getInstance(context.applicationContext).resultDao.deleteAll()
    }
    companion object { const val KEY = "inapp_sheet_row_pending" }
}
