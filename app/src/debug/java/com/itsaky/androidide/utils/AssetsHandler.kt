package com.itsaky.androidide.utils

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.adfa.constants.DOCUMENTATION_DB
import java.io.InputStream

object AssetsHandler {

    /**
     * Create an input stream for the assets zip file.
     *
     * @param context The context.
     */
    fun createAssetsInputStream(@Suppress("UNUSED_PARAMETER") context: Context): InputStream {
        return Environment.SPLIT_ASSETS_ZIP.inputStream()
    }
    
    suspend fun preInstall() { /*NO-OP*/ }

    suspend fun postInstall() {
        // allow updating documentation database -- only in debug builds
        val splitDb = Environment.DOWNLOAD_DIR.resolve(DOCUMENTATION_DB)
        if (splitDb.exists()) {
            withContext(Dispatchers.IO) { splitDb.copyTo(Environment.DOC_DB) }
        }
    }
}