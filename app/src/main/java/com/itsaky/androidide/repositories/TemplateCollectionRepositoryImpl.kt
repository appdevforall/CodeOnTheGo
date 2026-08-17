package com.itsaky.androidide.repositories

import com.itsaky.androidide.templates.ITemplateProvider
import com.itsaky.androidide.templates.TemplateRecipe
import com.itsaky.androidide.templates.impl.TemplateWarning
import com.itsaky.androidide.templates.impl.zip.ZipTemplateReader
import com.itsaky.androidide.utils.Environment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.adfa.constants.TEMPLATE_ARCHIVE_EXTENSION
import org.adfa.constants.TEMPLATE_CORE_ARCHIVE
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID

/**
 * Implementation of [TemplateCollectionRepository]. Templates are pure data (a zip archive
 * copied into [Environment.TEMPLATES_DIR]) so, unlike plugins, installing one never requires an
 * app restart - [ITemplateProvider.getInstance] just needs to be reloaded.
 *
 * All suspend functions here hop to [Dispatchers.IO] internally, so callers don't need to. On
 * failure, [installCollection] always leaves its `candidateFile` argument untouched (see that
 * function's kdoc) so the caller can retry with the same file.
 */
class TemplateCollectionRepositoryImpl : TemplateCollectionRepository {
	private companion object {
		private val log = LoggerFactory.getLogger(TemplateCollectionRepositoryImpl::class.java)

		/** Base filename of the bundled default templates archive - reserved, never a user collection. */
		private val RESERVED_BASE_NAME = File(TEMPLATE_CORE_ARCHIVE).nameWithoutExtension

		/** Case-insensitive match by base filename - the only stable "collection identity" available. */
		private fun findCollisionFile(
			templatesDir: File,
			baseName: String,
		): File? =
			templatesDir
				.listFiles { file -> file.extension.equals(TEMPLATE_ARCHIVE_EXTENSION, ignoreCase = true) }
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
					warnings.forEach { log.warn("Template read warning: resId={}, args={}", it.resId, it.args) }
					throw IllegalArgumentException("No valid templates found in archive: ${candidateFile.name}")
				}

				TemplateCollectionRepository.CollectionInfo(
					templateNames = templates.map { it.templateNameStr },
				)
			}.onFailure { exception ->
				if (exception is CancellationException) throw exception
				log.error("Failed to inspect template collection: {}", candidateFile.name, exception)
			}
		}

	override suspend fun findExistingCollision(baseName: String): String? =
		withContext(Dispatchers.IO) {
			try {
				Environment.TEMPLATES_DIR?.let { findCollisionFile(it, baseName) }?.nameWithoutExtension
			} catch (e: CancellationException) {
				throw e
			} catch (exception: Exception) {
				log.error("Failed to check for an existing template collection: {}", baseName, exception)
				null
			}
		}

	/**
	 * Installs [candidateFile] as `<targetBaseName>.cgt` in [Environment.TEMPLATES_DIR]. On any
	 * failure (including a validation error), [candidateFile] is left untouched so the caller can
	 * retry - it's only deleted once the install has fully succeeded.
	 */
	override suspend fun installCollection(
		candidateFile: File,
		targetBaseName: String,
		overwrite: Boolean,
	): Result<Unit> =
		withContext(Dispatchers.IO) {
			runCatching {
				if (targetBaseName.equals(RESERVED_BASE_NAME, ignoreCase = true)) {
					throw IllegalStateException("\"$targetBaseName\" is a reserved name and cannot be used")
				}

				// targetBaseName ends up as a single path segment below; reject anything that
				// could make it span multiple segments (or escape templatesDir entirely) before
				// it ever reaches a File constructor.
				if (targetBaseName.isBlank() ||
					targetBaseName.contains('/') ||
					targetBaseName.contains('\\') ||
					targetBaseName == "." ||
					targetBaseName == ".."
				) {
					throw IllegalArgumentException("Invalid template collection name: \"$targetBaseName\"")
				}

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

				// Belt-and-braces against the character check above: confirm the resolved path
				// still lands directly inside templatesDir once symlinks/".." are resolved.
				if (destFile.canonicalFile.parentFile != templatesDir.canonicalFile) {
					throw IllegalArgumentException("Invalid template collection name: \"$targetBaseName\"")
				}

				// Stage a copy of the incoming archive fully under templatesDir before touching
				// destFile, so a failure while writing the new content never destroys the
				// existing collection. candidateFile itself is deliberately left alone here (not
				// moved/deleted) so that if anything below fails, the caller can retry the whole
				// call with the same file - it's only deleted once the swap and the provider
				// reload have both fully succeeded. The staging (and backup) filenames carry a
				// random suffix so two concurrent installCollection() calls targeting the same
				// destFile never race on the same intermediate path.
				val stagingFile = File(templatesDir, "${destFile.name}.${UUID.randomUUID()}.tmp")
				candidateFile.copyTo(stagingFile, overwrite = true)

				// Back up (rather than delete) any existing destFile, so it can be put back if
				// the swap below fails for any reason - the existing collection is only ever
				// removed once the new one is confirmed successfully in its place. Same
				// renameTo()-then-copyTo() fallback as the swap below: renameTo() is unreliable
				// on-device even for a same-directory move (confirmed there during this PR).
				val hadExisting = destFile.exists()
				val backupFile = File(templatesDir, "${destFile.name}.${UUID.randomUUID()}.bak")
				if (hadExisting) {
					val backedUp =
						destFile.renameTo(backupFile) ||
							runCatching { destFile.copyTo(backupFile, overwrite = true).also { destFile.delete() } }.isSuccess
					if (!backedUp) {
						stagingFile.delete()
						throw IllegalStateException("Failed to back up existing file before replacing: ${destFile.name}")
					}
				}

				// Both files are now on the same volume (templatesDir), so this is a cheap,
				// same-directory move - renameTo() failing here (as opposed to across the
				// temp/templates boundary candidateFile itself would have to cross) would be
				// unexpected, but fall back to a copy anyway.
				val swapSucceeded =
					stagingFile.renameTo(destFile) ||
						runCatching { stagingFile.copyTo(destFile, overwrite = true) }.isSuccess

				if (!swapSucceeded) {
					if (hadExisting) {
						val restored =
							backupFile.renameTo(destFile) ||
								runCatching { backupFile.copyTo(destFile, overwrite = true) }.isSuccess
						if (!restored) {
							// Nothing more we can do here - surface it loudly rather than silently
							// leaving the user's original collection sitting under the backup's
							// random filename, invisible to findExistingCollision().
							log.error(
								"Failed to restore backup after a failed swap for \"{}\" - original content may still be at: {}",
								destFile.name,
								backupFile.name,
							)
						} else {
							backupFile.delete()
						}
					}
					stagingFile.delete()
					throw IllegalStateException("Failed to replace existing file: ${destFile.name}")
				}

				stagingFile.delete()
				if (hadExisting && backupFile.exists() && !backupFile.delete()) {
					log.warn("Installed but failed to delete backup file: {}", backupFile.name)
				}

				// The file swap above is the operation's real postcondition - it already fully
				// succeeded by this point. A reload failure here (e.g. templatesDir briefly
				// unreadable) shouldn't turn that into a reported failure: doing so would leave
				// destFile installed on disk while the caller believes nothing happened and
				// retries, immediately hitting a spurious "already exists".
				try {
					ITemplateProvider.getInstance(reload = true)
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					log.error(
						"Template collection installed but the provider failed to reload: {}",
						destFile.name,
						e,
					)
				}

				if (!candidateFile.delete()) {
					log.warn("Installed but failed to delete source temp file: {}", candidateFile.name)
				}
				Unit
			}.onFailure { exception ->
				if (exception is CancellationException) throw exception
				log.error("Failed to install template collection: {}", candidateFile.name, exception)
			}
		}

	override fun isTemplatesFeatureAvailable(): Boolean = Environment.TEMPLATES_DIR != null
}
