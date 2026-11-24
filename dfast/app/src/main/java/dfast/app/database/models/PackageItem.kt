package dfast.app.database.models

import dfast.app.core.packages.PackageBase

data class PackageItem(
    val title: String,
    val plugin: PackageBase
) {
    fun getInstance(): PackageBase = plugin.getInstance()
}