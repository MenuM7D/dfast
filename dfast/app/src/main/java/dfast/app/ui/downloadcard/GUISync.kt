package dfast.app.ui.downloadcard

import dfast.app.database.models.ResultItem

interface GUISync {
    fun updateTitleAuthor(t: String, a: String)
    fun updateUI(res: ResultItem?)
}