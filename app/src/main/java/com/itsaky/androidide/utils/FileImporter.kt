package com.itsaky.androidide.utils

import android.content.ClipData
import android.content.Context
import com.itsaky.androidide.R
import com.itsaky.androidide.dnd.toImportableExternalUris
import java.io.File

/**
 * Utility class responsible for safely copying external files dropped into the IDE.
 */
class FileImporter(private val context: Context) {

    sealed interface ImportResult {
        data class Success(val count: Int) : ImportResult
        data class PartialSuccess(val count: Int, val error: Throwable) : ImportResult
        data class Failure(val error: Throwable) : ImportResult
    }

    /**
     * Iterates through the provided [clipData] and copies the valid external files into the [targetFile] directory.
     * * @return An [ImportResult] indicating the success, partial success, or failure of the operation.
     */
    fun copyDroppedFiles(clipData: ClipData, targetFile: File): ImportResult {
        val targetDirectory = resolveTargetDirectory(targetFile)
        require(targetDirectory.exists() && targetDirectory.isDirectory) {
            context.getString(R.string.msg_file_tree_drop_destination_missing)
        }

        val validUris = (0 until clipData.itemCount)
            .flatMap { clipData.getItemAt(it).toImportableExternalUris(context) }

        if (validUris.isEmpty()) {
            return ImportResult.Failure(IllegalArgumentException(context.getString(R.string.msg_file_tree_drop_no_files)))
        }

        val results = validUris.map { uri ->
            runCatching {
                val defaultName = context.getString(R.string.msg_file_tree_drop_default_name)
                val rawName = uri.getFileName(context).ifBlank { defaultName }
                var sanitizedName = rawName.substringAfterLast('/').substringAfterLast('\\')

                if (sanitizedName == "." || sanitizedName == ".." || sanitizedName.isBlank()) {
                    sanitizedName = defaultName
                }

                val destinationFile = createAvailableTargetFile(targetDirectory, sanitizedName)

                UriFileImporter.copyUriToFile(
                    context = context,
                    uri = uri,
                    destinationFile = destinationFile,
                    onOpenFailed = {
                        IllegalStateException(
                            context.getString(
                                R.string.msg_file_tree_drop_read_failed,
                                sanitizedName
                            )
                        )
                    }
                )
            }
        }

        val successes = results.count { it.isSuccess }
        val errors = results.mapNotNull { it.exceptionOrNull() }

        return when {
            errors.isEmpty() -> ImportResult.Success(successes)
            successes > 0 -> ImportResult.PartialSuccess(successes, errors.first())
            else -> ImportResult.Failure(errors.first())
        }
    }

    private fun resolveTargetDirectory(targetFile: File): File {
        return if (targetFile.isDirectory) {
            targetFile
        } else {
            targetFile.parentFile
                ?: error(context.getString(R.string.msg_file_tree_drop_destination_unresolved))
        }
    }

    private fun createAvailableTargetFile(directory: File, originalName: String): File {
        val dotIndex = originalName.lastIndexOf('.')
        val hasExtension = dotIndex > 0 && dotIndex < originalName.lastIndex
        val baseName = if (hasExtension) originalName.take(dotIndex) else originalName
        val extension = if (hasExtension) originalName.substring(dotIndex) else ""

        var candidate = File(directory, originalName)
        var suffix = 1
        while (candidate.exists()) {
            candidate = File(directory, "$baseName ($suffix)$extension")
            suffix++
        }

        return candidate
    }
}
