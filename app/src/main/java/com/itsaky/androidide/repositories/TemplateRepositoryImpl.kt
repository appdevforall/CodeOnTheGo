package com.itsaky.androidide.repositories

import com.itsaky.androidide.templates.ITemplateProvider
import com.itsaky.androidide.templates.manager.models.CgtFileItem
import com.itsaky.androidide.templates.manager.models.TemplateProvenance
import com.itsaky.androidide.templates.manager.parsing.CgtTemplateReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.adfa.constants.TEMPLATE_CORE_ARCHIVE
import org.json.JSONException
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException

/**
 * Implementation of [TemplateRepository].
 *
 * Reimplements the install/uninstall/delete semantics of the reference
 * `TemplateManagerPlugin` fragment as direct file operations, since the host app already has
 * unrestricted access to [templatesDir]/[downloadDir] and doesn't need `IdeTemplateService`'s
 * plugin-facing permission gate.
 */
class TemplateRepositoryImpl(
	private val templatesDir: File,
	private val downloadDir: File,
) : TemplateRepository {
	private companion object {
		private val logger = LoggerFactory.getLogger(TemplateRepositoryImpl::class.java)
		private const val CGT_EXTENSION = "cgt"
		private const val PLUGIN_CGT_PREFIX = "plugin_"
	}

	override suspend fun listTemplateFiles(): Result<List<CgtFileItem>> =
		withContext(Dispatchers.IO) {
			try {
				Result.success(scanTemplates())
			} catch (e: CancellationException) {
				throw e
			} catch (e: IOException) {
				logger.error("Failed to scan template files", e)
				Result.failure(e)
			} catch (e: SecurityException) {
				logger.error("Failed to scan template files", e)
				Result.failure(e)
			}
		}

	private fun scanTemplates(): List<CgtFileItem> {
		val installed = cgtFilesIn(templatesDir).map { file -> parseCgtFile(file, installed = true) }
		val downloaded = cgtFilesIn(downloadDir).map { file -> parseCgtFile(file, installed = false) }
		return (installed + downloaded).filterNotNull()
	}

	private fun cgtFilesIn(dir: File): List<File> =
		dir
			.listFiles { file -> file.isFile && file.extension.equals(CGT_EXTENSION, ignoreCase = true) }
			?.sortedBy { it.name }
			?: emptyList()

	/** Parses a .cgt (which may bundle multiple templates) into a card item, or null if it contains no template.json. */
	private fun parseCgtFile(
		file: File,
		installed: Boolean,
	): CgtFileItem? {
		val templates =
			try {
				file.inputStream().use(CgtTemplateReader::readTemplates)
			} catch (e: CancellationException) {
				throw e
			} catch (e: IOException) {
				logger.warn("Failed to parse {}", file.absolutePath, e)
				return null
			} catch (e: JSONException) {
				logger.warn("Failed to parse {}", file.absolutePath, e)
				return null
			} catch (e: IllegalArgumentException) {
				// ZipInputStream.nextEntry throws this for a malformed (non-UTF-8) entry name -
				// downloadDir is the public Downloads folder, so a corrupt/hostile .cgt is
				// untrusted input, not a programming error. Skip it like any other bad archive.
				logger.warn("Failed to parse {}", file.absolutePath, e)
				return null
			}
		if (templates.isEmpty()) return null
		return CgtFileItem(
			file = file,
			name = file.name,
			templates = templates,
			installed = installed,
			provenance = provenanceOf(file.name),
		)
	}

	private fun provenanceOf(fileName: String): TemplateProvenance =
		when {
			fileName == TEMPLATE_CORE_ARCHIVE -> TemplateProvenance.BUNDLED
			fileName.startsWith(PLUGIN_CGT_PREFIX) -> TemplateProvenance.PLUGIN
			else -> TemplateProvenance.USER
		}

	override suspend fun installTemplate(item: CgtFileItem): Result<Unit> =
		withContext(Dispatchers.IO) {
			try {
				check(!item.installed) { "'${item.name}' is already installed" }
				val dest = File(templatesDir, item.file.name)
				check(!dest.exists()) { "A template named '${dest.name}' already exists in $templatesDir" }
				item.file.copyTo(dest, overwrite = false)
				if (!item.file.delete()) {
					dest.delete()
					throw IOException("Failed to delete source file after copying: ${item.file.absolutePath}")
				}
				ITemplateProvider.getInstance(reload = true)
				Result.success(Unit)
			} catch (e: CancellationException) {
				throw e
			} catch (e: IOException) {
				logger.error("Failed to install template: {}", item.name, e)
				Result.failure(e)
			} catch (e: IllegalStateException) {
				logger.error("Failed to install template: {}", item.name, e)
				Result.failure(e)
			}
		}

	override suspend fun uninstallTemplate(item: CgtFileItem): Result<Unit> =
		withContext(Dispatchers.IO) {
			try {
				check(item.installed) { "'${item.name}' is not installed" }
				check(item.provenance != TemplateProvenance.BUNDLED) { "Cannot uninstall the bundled template" }

				// Restore a copy to Downloads BEFORE removing it from the store: if the restore
				// throws, the store copy below is never touched, so the user's only copy survives.
				val restored = File(downloadDir, item.file.name)
				check(!restored.exists()) { "A download named '${restored.name}' already exists in $downloadDir" }
				item.file.copyTo(restored, overwrite = false)
				if (!item.file.delete()) {
					restored.delete()
					throw IOException("Failed to delete source file after copying: ${item.file.absolutePath}")
				}
				ITemplateProvider.getInstance(reload = true)
				Result.success(Unit)
			} catch (e: CancellationException) {
				throw e
			} catch (e: IOException) {
				logger.error("Failed to uninstall template: {}", item.name, e)
				Result.failure(e)
			} catch (e: IllegalStateException) {
				logger.error("Failed to uninstall template: {}", item.name, e)
				Result.failure(e)
			}
		}

	override suspend fun deleteDownloadFile(item: CgtFileItem): Result<Unit> =
		withContext(Dispatchers.IO) {
			try {
				check(!item.installed) { "Cannot delete an installed template; uninstall it first" }
				if (!item.file.delete()) {
					throw IOException("Failed to delete ${item.file.absolutePath}")
				}
				Result.success(Unit)
			} catch (e: CancellationException) {
				throw e
			} catch (e: IOException) {
				logger.error("Failed to delete download file: {}", item.name, e)
				Result.failure(e)
			} catch (e: IllegalStateException) {
				logger.error("Failed to delete download file: {}", item.name, e)
				Result.failure(e)
			}
		}
}
