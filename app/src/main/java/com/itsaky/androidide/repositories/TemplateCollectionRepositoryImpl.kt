package com.itsaky.androidide.repositories

import android.util.Log
import com.itsaky.androidide.templates.ITemplateProvider
import com.itsaky.androidide.templates.TemplateRecipe
import com.itsaky.androidide.templates.impl.TemplateWarning
import com.itsaky.androidide.templates.impl.zip.ZipTemplateReader
import com.itsaky.androidide.utils.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.adfa.constants.TEMPLATE_ARCHIVE_EXTENSION
import java.io.File

/**
 * Implementation of [TemplateCollectionRepository]. Templates are pure data (a zip archive
 * copied into [Environment.TEMPLATES_DIR]) so, unlike plugins, installing one never requires an
 * app restart - [ITemplateProvider.getInstance] just needs to be reloaded.
 */
class TemplateCollectionRepositoryImpl : TemplateCollectionRepository {
	private companion object {
		private const val TAG = "TemplateCollectionRepository"

		/** Case-insensitive match by base filename - the only stable "collection identity" available. */
		private fun findCollisionFile(
			templatesDir: File,
			baseName: String,
		): File? =
			templatesDir
				.listFiles { file -> file.extension == TEMPLATE_ARCHIVE_EXTENSION }
				?.firstOrNull { it.nameWithoutExtension.equals(baseName, ignoreCase = true) }
	}

	override suspend fun inspectCollection(candidateFile: File): Result<TemplateCollectionRepository.CollectionInfo> =
		withContext(Dispatchers.IO) {
			runCatching {
				val warnings = mutableListOf<TemplateWarning>()
				val templates =
					ZipTemplateReader.read(candidateFile, warnings) { _, _, _, _, _ ->
						TemplateRecipe { null }
					}

				if (templates.isEmpty()) {
					warnings.forEach { Log.w(TAG, "Template read warning: resId=${it.resId}, args=${it.args}") }
					throw IllegalArgumentException("No valid templates found in archive: ${candidateFile.name}")
				}

				TemplateCollectionRepository.CollectionInfo(
					templateNames = templates.map { it.templateNameStr },
				)
			}.onFailure { exception ->
				Log.e(TAG, "Failed to inspect template collection: ${candidateFile.absolutePath}", exception)
			}
		}

	override suspend fun findExistingCollision(baseName: String): String? =
		withContext(Dispatchers.IO) {
			Environment.TEMPLATES_DIR?.let { findCollisionFile(it, baseName) }?.nameWithoutExtension
		}

	override suspend fun installCollection(
		candidateFile: File,
		targetBaseName: String,
		overwrite: Boolean,
	): Result<Unit> =
		withContext(Dispatchers.IO) {
			runCatching {
				val templatesDir =
					Environment.TEMPLATES_DIR
						?: throw IllegalStateException("Templates system not available")

				// Reuse the same case-insensitive lookup findExistingCollision() uses, so a
				// case-variant match (e.g. installing "mytemplates" when "MyTemplates.cgt" is
				// already there) is caught here too instead of silently creating a duplicate.
				val existingMatch = findCollisionFile(templatesDir, targetBaseName)
				if (existingMatch != null && !overwrite) {
					throw IllegalStateException(
						"A template collection named \"$targetBaseName\" already exists",
					)
				}

				// Overwrite the existing case-variant file in place (preserving its casing)
				// rather than create a second, case-differing duplicate.
				val destFile = existingMatch ?: File(templatesDir, "$targetBaseName.$TEMPLATE_ARCHIVE_EXTENSION")
				if (destFile.exists() && !destFile.delete()) {
					throw IllegalStateException("Failed to replace existing file: ${destFile.name}")
				}

				// Try an atomic move first; File.renameTo() is unreliable on Android even within
				// the same app's private storage (confirmed on a physical device: it silently
				// fails here despite temp/ and templates/ both being under filesDir), so fall
				// back to copy+delete rather than trust it unconditionally.
				if (!candidateFile.renameTo(destFile)) {
					candidateFile.copyTo(destFile, overwrite = true)
					if (!candidateFile.delete()) {
						Log.w(TAG, "Installed but failed to delete source temp file: ${candidateFile.absolutePath}")
					}
				}

				ITemplateProvider.getInstance(reload = true)
				Unit
			}.onFailure { exception ->
				Log.e(TAG, "Failed to install template collection: ${candidateFile.absolutePath}", exception)
			}
		}

	override fun isTemplatesFeatureAvailable(): Boolean = Environment.TEMPLATES_DIR != null
}
