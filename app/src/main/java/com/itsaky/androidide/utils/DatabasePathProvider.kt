package com.itsaky.androidide.utils

import android.content.Context

object DatabasePathProvider {
    private const val DATABASE_NAME = "documentation.db"
    
    fun getDatabasePath(context: Context): String {
        return context.getDatabasePath(DATABASE_NAME).absolutePath
    }
}