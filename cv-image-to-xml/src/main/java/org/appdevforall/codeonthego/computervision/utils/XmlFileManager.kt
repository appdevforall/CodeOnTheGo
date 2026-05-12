package org.appdevforall.codeonthego.computervision.utils

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Utility class responsible for managing file input/output operations.
 * Specifically handles saving XML files to the device's public Downloads directory,
 * adapting to different Android API levels (Scoped Storage vs Legacy).
 *
 * @property context The application or activity context needed to access content resolvers.
 */
class XmlFileManager(private val context: Context) {

    /**
     * Saves an XML string to a file in the device's public Downloads directory.
     * It automatically handles the differences in file storage APIs between
     * Android 10 (API 29+) and older versions.
     *
     * @param xmlString The XML content to be saved.
     * @param fileName The desired name for the output file. Defaults to "layout_result.xml".
     * @return A [Result] containing the file name if successful, or an [IOException] on failure.
     */
    fun saveXmlToDownloads(xmlString: String, fileName: String = "layout_result.xml"): Result<String> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveUsingMediaStore(xmlString, fileName)
            } else {
                saveUsingLegacyFileApi(xmlString, fileName)
            }
            Result.success(fileName)
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    /**
     * Saves the file using the MediaStore API.
     * This is the required approach for Android 10 (API 29) and above due to Scoped Storage restrictions.
     *
     * @param xmlString The XML content to write.
     * @param fileName The name of the file.
     * @throws IOException If the MediaStore record cannot be created or written to.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveUsingMediaStore(xmlString: String, fileName: String) {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/xml")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw IOException("Failed to create new MediaStore record for $fileName.")

        resolver.openOutputStream(uri)?.use { outputStream ->
            outputStream.write(xmlString.toByteArray())
        } ?: throw IOException("Failed to open output stream for MediaStore URI.")
    }

    /**
     * Saves the file using the legacy File API.
     * This approach is used for devices running Android 9 (API 28) or lower.
     *
     * @param xmlString The XML content to write.
     * @param fileName The name of the file.
     * @throws IOException If the Downloads directory or the file cannot be created.
     */
    @Suppress("DEPRECATION")
    private fun saveUsingLegacyFileApi(xmlString: String, fileName: String) {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

        if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
            throw IOException("Failed to create Downloads directory.")
        }

        val file = File(downloadsDir, fileName)
        FileOutputStream(file).use { outputStream ->
            outputStream.write(xmlString.toByteArray())
        }
    }
}
