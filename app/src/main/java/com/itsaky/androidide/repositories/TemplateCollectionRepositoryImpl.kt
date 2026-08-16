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
			Environment.TEMPLATES_DIR
				?.listFiles { file -> file.extension == TEMPLATE_ARCHIVE_EXTENSION }
				?.firstOrNull { it.nameWithoutExtension.equals(baseName, ignoreCase = true) }
				?.nameWithoutExtension
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

				val destFile = File(templatesDir, "$targetBaseName.$TEMPLATE_ARCHIVE_EXTENSION")
				if (destFile.exists() && !overwrite) {
					throw IllegalStateException(
						"A template collection named \"$targetBaseName\" already exists",
					)
				}

				candidateFile.copyTo(destFile, overwrite = true)
				candidateFile.delete()

				ITemplateProvider.getInstance(reload = true)
				Unit
			}.onFailure { exception ->
				Log.e(TAG, "Failed to install template collection: ${candidateFile.absolutePath}", exception)
			}
		}

	override fun isTemplatesFeatureAvailable(): Boolean = Environment.TEMPLATES_DIR != null
}
