package org.appdevforall.codeonthego.computervision.data.repository

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class DrawableImportHelper(
    private val contentResolver: ContentResolver
) {

    suspend fun importDrawable(
        sourceUri: Uri,
        layoutFilePath: String?,
        fallbackName: String
    ): Result<ImportedDrawable> = withContext(Dispatchers.IO) {
        runCatching {
            requireNotNull(layoutFilePath) { "Layout file path is not available." }
            val layoutFile = File(layoutFilePath)

            val drawableDir = resolveDrawableDir(layoutFile)
            check(drawableDir.exists() || drawableDir.mkdirs()) {
                "Could not create drawable directory: ${drawableDir.absolutePath}"
            }

            val extension = resolveSupportedExtension(sourceUri, fallbackName)
            val baseName = sanitizeResourceName(resolveDisplayName(sourceUri) ?: fallbackName)
            val destinationFile = resolveAvailableFile(drawableDir, baseName, extension)

            contentResolver.openInputStream(sourceUri)?.use { input ->
                destinationFile.outputStream().use(input::copyTo)
            } ?: error("Could not open selected image.")

            ImportedDrawable(
                resourceName = destinationFile.nameWithoutExtension,
                drawableReference = "@drawable/${destinationFile.nameWithoutExtension}",
                file = destinationFile
            )
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
