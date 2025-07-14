package com.itsaky.androidide.utils

import android.content.Context
import java.io.InputStream

object AssetsHandler {

    fun createAssetsInputStream(context: Context): InputStream {
        TODO("Decide how to include assets in release variant")
    }
    
    suspend fun preInstall() { /*NO-OP*/ }

    suspend fun postInstall() { /*NO-OP*/ }
}