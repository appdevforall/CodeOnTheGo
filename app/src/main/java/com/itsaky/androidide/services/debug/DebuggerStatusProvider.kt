package com.itsaky.androidide.services.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.itsaky.androidide.lookup.Lookup
import com.itsaky.androidide.lsp.IDEDebugClientImpl
import org.slf4j.LoggerFactory

class DebuggerStatusProvider : ContentProvider() {

    companion object {
        private val logger = LoggerFactory.getLogger(DebuggerStatusProvider::class.java)
    }

    override fun onCreate(): Boolean = false

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        if (uri.authority != "org.adfa.cogo.debugger") {
            logger.error("Invalid authority: ${uri.authority}")
            return null
        }

        val debugClient = Lookup.getDefault().lookup(IDEDebugClientImpl::class.java)
        if (debugClient == null) {
            logger.error("Unable to find debug client")
            return null
        }

        val cursor = MatrixCursor(arrayOf("status"))
        cursor.addRow(arrayOf("active"))
        return cursor
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}