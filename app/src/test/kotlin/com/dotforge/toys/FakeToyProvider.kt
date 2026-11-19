package com.dotforge.toys

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import org.robolectric.Robolectric

/**
 * Nothing's toy list for tests and screenshots: the rows [ToyList] reads, as `service_name` and `is_active`.
 * [install] registers it under Nothing's authority; [active] says which of our toys are in the list (true) or
 * listed but off (false).
 */
class FakeToyProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor =
        MatrixCursor(arrayOf("_id", "package_name", "service_name", "is_active")).apply {
            active.entries.forEachIndexed { i, (cls, on) -> addRow(arrayOf<Any>(i, "com.dotforge", "com.dotforge.toys.$cls", if (on) "1" else "0")) }
        }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    companion object {
        var active: Map<String, Boolean> = emptyMap()

        fun install(rows: Map<String, Boolean>) {
            active = rows
            Robolectric.setupContentProvider(FakeToyProvider::class.java, "com.nothing.glyphtoyprovider")
        }
    }
}
