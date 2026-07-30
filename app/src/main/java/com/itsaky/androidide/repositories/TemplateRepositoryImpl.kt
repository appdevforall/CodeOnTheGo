package com.itsaky.androidide.repositories

import android.util.Log
import com.itsaky.androidide.templates.ITemplateProvider
import com.itsaky.androidide.templates.manager.models.CgtFileItem
import com.itsaky.androidide.templates.manager.models.TemplateProvenance
import com.itsaky.androidide.templates.manager.parsing.CgtTemplateReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.adfa.constants.TEMPLATE_CORE_ARCHIVE
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
		private const val TAG = "TemplateRepository"
		private const val CGT_EXTENSION = "cgt"
		private const val PLUGIN_CGT_PREFIX = "plugin_"
	}

	override suspend fun listTemplateFiles(): Result<List<CgtFileItem>> =
		withContext(Dispatchers.IO) {
			runCatching { scanTemplates() }
				.onFailure { exception -> Log.e(TAG, "Failed to scan template files", exception) }
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
			runCatching { file.inputStream().use(CgtTemplateReader::readTemplates) }
				.onFailure { exception -> Log.w(TAG, "Failed to parse ${file.absolutePath}", exception) }
				.getOrNull()
				?: return null
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
			runCatching {
				check(!item.installed) { "'${item.name}' is already installed" }
				val dest = File(templatesDir, item.file.name)
				item.file.copyTo(dest, overwrite = true)
				item.file.delete()
				ITemplateProvider.getInstance(reload = true)
			}.onFailure { exception -> Log.e(TAG, "Failed to install template: ${item.name}", exception) }
				.map {}
		}

	override suspend fun uninstallTemplate(item: CgtFileItem): Result<Unit> =
		withContext(Dispatchers.IO) {
			runCatching {
				check(item.installed) { "'${item.name}' is not installed" }
				check(item.provenance != TemplateProvenance.BUNDLED) { "Cannot uninstall the bundled template" }

				// Restore a copy to Downloads BEFORE removing it from the store: if the restore
				// throws, the store copy below is never touched, so the user's only copy survives.
				val restored = File(downloadDir, item.file.name)
				item.file.copyTo(restored, overwrite = true)
				item.file.delete()
				ITemplateProvider.getInstance(reload = true)
			}.onFailure { exception -> Log.e(TAG, "Failed to uninstall template: ${item.name}", exception) }
				.map {}
		}

	override suspend fun deleteDownloadFile(item: CgtFileItem): Result<Unit> =
		withContext(Dispatchers.IO) {
			runCatching {
				check(!item.installed) { "Cannot delete an installed template; uninstall it first" }
				if (!item.file.delete()) {
					throw IOException("Failed to delete ${item.file.absolutePath}")
				}
			}.onFailure { exception -> Log.e(TAG, "Failed to delete download file: ${item.name}", exception) }
		}
}
