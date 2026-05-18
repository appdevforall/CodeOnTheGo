package org.appdevforall.codeonthego.computervision.data.repository

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Helper class responsible for importing and managing image files within an Android project's
 * resource directory (e.g., res/drawable).
 *
 * @property contentResolver The ContentResolver used to read data from content URIs.
 */
class DrawableImportHelper(
    private val contentResolver: ContentResolver
) {

    /**
     * Imports an image from a given URI into the 'res/drawable' directory associated with
     * the provided layout file.
     *
     * @param sourceUri The URI of the image to import.
     * @param layoutFilePath The absolute path to the current layout XML file. Used to locate the 'res' directory.
     * @param fallbackName A name to use if the original file name cannot be resolved.
     * @return A [Result] containing an [ImportedDrawable] if successful, or an exception on failure.
     */
    suspend fun importDrawable(
        sourceUri: Uri,
        layoutFilePath: String?,
        fallbackName: String
    ): Result<ImportedDrawable> = withContext(Dispatchers.IO) {
        runCatching {
            requireNotNull(layoutFilePath) { "Layout file path is not available." }

            val drawableDir = getOrCreateDrawableDirectory(layoutFilePath)
            val extension = resolveSupportedExtension(sourceUri, fallbackName)
            val baseName = sanitizeResourceName(resolveDisplayName(sourceUri) ?: fallbackName)
            val destinationFile = resolveAvailableFile(drawableDir, baseName, extension)

            copyImageToDestination(sourceUri, destinationFile)

            ImportedDrawable(
                resourceName = destinationFile.nameWithoutExtension,
                drawableReference = "@drawable/${destinationFile.nameWithoutExtension}",
                file = destinationFile
            )
        }
    }

    /**
     * Deletes an imported drawable file from the filesystem.
     *
     * @param layoutFilePath The absolute path to the current layout XML file. Used to locate the 'res' directory.
     * @param resourceName The sanitized name of the resource to delete (without extension).
     * @return A [Result] indicating success (true if deleted, false if file did not exist) or failure.
     */
    suspend fun deleteDrawable(
        layoutFilePath: String?,
        resourceName: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            requireNotNull(layoutFilePath) { "Layout file path is not available." }

            val drawableDir = resolveDrawableDir(File(layoutFilePath))
            if (!drawableDir.exists()) return@runCatching false

            val targetFile = findFileByResourceName(drawableDir, resourceName)

            targetFile?.delete() ?: false
        }
    }

    private fun getOrCreateDrawableDirectory(layoutFilePath: String): File {
        val layoutFile = File(layoutFilePath)
        val drawableDir = resolveDrawableDir(layoutFile)
        check(drawableDir.exists() || drawableDir.mkdirs()) {
            "Could not create drawable directory: ${drawableDir.absolutePath}"
        }
        return drawableDir
    }

    private fun copyImageToDestination(sourceUri: Uri, destinationFile: File) {
        contentResolver.openInputStream(sourceUri)?.use { input ->
            destinationFile.outputStream().use(input::copyTo)
        } ?: error("Could not open selected image.")
    }

    private fun findFileByResourceName(directory: File, resourceName: String): File? {
         return directory.listFiles()?.firstOrNull {
            it.nameWithoutExtension == resourceName
        }
    }

    private fun resolveDrawableDir(layoutFile: File): File {
        val resDir = generateSequence(layoutFile.parentFile) { it.parentFile }
            .firstOrNull { it.name == "res" }
            ?: throw IllegalStateException("Could not resolve res directory from: ${layoutFile.absolutePath}")

        return File(resDir, "drawable")
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
    }

    private fun resolveSupportedExtension(uri: Uri, fallbackName: String): String {
        val mimeType = contentResolver.getType(uri)?.lowercase(Locale.US)
        var extension = when (mimeType) {
            "image/png" -> "png"
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp" -> "webp"
            else -> null
        }

        if (extension == null) {
            val nameToUse = resolveDisplayName(uri) ?: fallbackName
            extension = nameToUse
                .substringAfterLast('.', missingDelimiterValue = "")
                .lowercase(Locale.US)
                .takeIf { it.isNotBlank() }
        }

        return when (extension) {
            "png", "jpg", "jpeg", "webp" -> extension
            else -> throw IllegalArgumentException("Unsupported image format. Use PNG, JPG, JPEG, or WEBP.")
        }
    }

    private fun sanitizeResourceName(rawName: String): String {
        val nameWithoutExtension = rawName.substringBeforeLast('.')
        val normalized = nameWithoutExtension
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9_]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')

        val safeName = normalized.ifBlank { "imported_image" }

        return if (safeName.first().isDigit()) {
            "img_$safeName"
        } else {
            safeName
        }
    }

    private fun resolveAvailableFile(
        drawableDir: File,
        baseName: String,
        extension: String
    ): File {
        var candidate = File(drawableDir, "$baseName.$extension")
        var index = 1

        while (!candidate.createNewFile()) {
            candidate = File(drawableDir, "${baseName}_$index.$extension")
            index++
        }

        return candidate
    }
}

data class ImportedDrawable(
    val resourceName: String,
    val drawableReference: String,
    val file: File
)
