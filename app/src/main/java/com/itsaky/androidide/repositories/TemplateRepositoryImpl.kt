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

	/**
	 * One card per archive, not per file on disk. The same `.cgt` name can exist in both
	 * directories at once - a copy dropped into Downloads by hand, or the picker-install path,
	 * which copies rather than moves. Both rows would then render identically apart from the
	 * status line, and the Downloads twin is a dead end: [installTemplate] refuses to overwrite,
	 * so its Install can only ever fail. The installed copy wins.
	 *
	 * Names are compared case-insensitively, matching the stricter of the two install paths
	 * (`TemplateCollectionRepository.findExistingCollision`), so any row still listed as
	 * "not installed" is one the user can actually install.
	 *
	 * The bundled `core.cgt` is excluded outright (ADFA-5645): it ships with the IDE, isn't
	 * something the user installed or can uninstall, and has nothing to do with this screen's
	 * install/uninstall/delete flows.
	 */
	private fun scanTemplates(): List<CgtFileItem> {
		val installed = cgtFilesIn(templatesDir).mapNotNull { file -> parseCgtFile(file, installed = true) }
		val installedNames = installed.mapTo(mutableSetOf()) { item -> item.name.lowercase() }
		val downloaded =
			cgtFilesIn(downloadDir)
				.filterNot { file -> file.name.lowercase() in installedNames }
				.mapNotNull { file -> parseCgtFile(file, installed = false) }
		// Filtered out of the combined list, not just `installed`: core.cgt must be excluded even
		// if templatesDir doesn't have it yet (bootstrap incomplete) and only a Downloads copy exists.
		return (installed + downloaded).filterNot { item -> isCoreArchive(item.name) }
	}

	/** Case-insensitive, like the twin dedup above and `findExistingCollision`'s reserved name. */
	private fun isCoreArchive(fileName: String): Boolean = fileName.equals(TEMPLATE_CORE_ARCHIVE, ignoreCase = true)

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
		if (fileName.startsWith(PLUGIN_CGT_PREFIX)) TemplateProvenance.PLUGIN else TemplateProvenance.USER

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

	/**
	 * On a post-copy delete failure: rolls back (deletes the Downloads copy this call created) when
	 * `overwrite` was false, but leaves the Downloads copy in place when `overwrite` was true, since
	 * the pre-existing content there was already replaced and can't be recovered either way.
	 */
	override suspend fun uninstallTemplate(
		item: CgtFileItem,
		overwrite: Boolean,
	): Result<Unit> =
		withContext(Dispatchers.IO) {
			try {
				check(item.installed) { "'${item.name}' is not installed" }
				// Guarded here as well as hidden by scanTemplates: once moved out, core.cgt is never
				// listed again, so there would be no in-app way to put it back.
				check(!isCoreArchive(item.name)) { "Cannot uninstall the bundled template" }

				// Restore a copy to Downloads BEFORE removing it from the store: if the restore
				// throws, the store copy below is never touched, so the user's only copy survives.
				// The existing-file lookup ignores case, like scanTemplates: a twin differing only in
				// case is hidden from the list, so it must still raise the Replace prompt.
				val existingDownload =
					downloadDir.listFiles()?.firstOrNull { file ->
						file.isFile && file.name.equals(item.file.name, ignoreCase = true)
					}
				val restored = existingDownload ?: File(downloadDir, item.file.name)
				val hadExistingDownload = existingDownload != null
				if (hadExistingDownload && !overwrite) {
					// Not thrown, so it skips the catch blocks below - log it here instead, or a
					// repeated replace-conflict leaves no trace for support to find.
					logger.warn(
						"Uninstall of '{}' would overwrite an existing Downloads file; asking for confirmation",
						item.name,
					)
					return@withContext Result.failure(TemplateReplaceConflictException(restored.name))
				}
				copyThroughTempFile(item.file, restored)
				if (!item.file.delete()) {
					// Only roll back a copy this call created itself. When `overwrite` replaced a
					// pre-existing Downloads file, that original content is already gone - deleting
					// `restored` here would destroy the new copy too and leave the user with nothing,
					// whereas the still-installed source (its delete just failed) means leaving the
					// new copy in place costs nothing and loses no data.
					if (!hadExistingDownload) {
						restored.delete()
						throw IOException("Failed to delete source file after copying: ${item.file.absolutePath}")
					}
					throw IOException(
						"Replaced '${restored.name}' in Downloads, but failed to delete the installed copy at " +
							"${item.file.absolutePath} - the template now exists in both places",
					)
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

	/**
	 * Copies [source] to [target] via a temp file renamed into place, so a copy that fails partway
	 * (storage full, I/O error) leaves an existing [target] intact and no truncated file behind.
	 * `copyTo(overwrite = true)` deletes the target before copying, which would lose it.
	 */
	private fun copyThroughTempFile(
		source: File,
		target: File,
	) {
		// Not a .cgt name, so a leftover is never listed as a template.
		val temp = File(target.parentFile, ".${target.name}.part")
		try {
			source.copyTo(temp, overwrite = true)
			if (!temp.renameTo(target)) {
				throw IOException("Failed to move ${temp.absolutePath} into place as ${target.absolutePath}")
			}
		} finally {
			temp.delete()
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
