package com.itsaky.androidide.models

import android.content.Context
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.utils.formatDate
import java.io.File

class ProjectFile(
    path: String,
    val createdAt: String?,
    val lastModified: String?,
) {

    var path: String = path
        private set

    var name: String = lastSegment(path)
        private set

    fun rename(newPath: String) {
        File(path).renameTo(File(newPath))
        path = newPath
        name = lastSegment(newPath)
    }

    fun renderDateText(context: Context): String {
        val showModified = createdAt != lastModified
        val renderDate = if (showModified) lastModified else createdAt
        val label =
            if (showModified) {
                context.getString(R.string.date_modified_label)
            } else {
                context.getString(R.string.date_created_label)
            }
        return context.getString(R.string.date, label, formatDate(renderDate ?: ""))
    }

    private companion object {
        fun lastSegment(path: String): String = path.substring(path.lastIndexOf("/") + 1)
    }
}
