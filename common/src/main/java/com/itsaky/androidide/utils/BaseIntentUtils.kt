package com.itsaky.androidide.utils

import android.content.Context
import android.content.Intent
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * Utilities for sharing files.
 *
 * @author Akash Yadav
 */
object BaseIntentUtils {

    // using '*/*' results in weird syntax highlighting on github
    // use this as a workaround
    private const val MIME_ANY = "*" + "/" + "*"

    @JvmStatic
    fun shareFile(
        context: Context,
        file: File,
        mimeType: String,
    ) {
        startIntent(context = context, file = file, mimeType = mimeType)
    }

    @JvmStatic
    @JvmOverloads
    fun startIntent(
        context: Context,
        file: File,
        mimeType: String = MIME_ANY,
        intentAction: String = Intent.ACTION_SEND,
    ) {
        val uri =
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.providers.fileprovider",
                file,
            )
        val intent =
            ShareCompat
                .IntentBuilder(context)
                .setType(mimeType)
                .setStream(uri)
                .intent
                .setAction(intentAction)
                .setDataAndType(uri, mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        context.startActivity(Intent.createChooser(intent, null))
    }
}