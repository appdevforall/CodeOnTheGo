package com.itsaky.androidide.repositories

import com.itsaky.androidide.templates.manager.models.CgtFileItem
import java.io.IOException

/**
 * Repository interface for template (`.cgt`) file operations.
 *
 * Unlike [PluginRepository], this talks directly to the filesystem
 * (`Environment.TEMPLATES_DIR` + the Downloads folder) rather than through a plugin-facing
 * service - the host app doesn't need the `pluginId`/permission indirection that
 * `IdeTemplateService` exists for.
 */
interface TemplateRepository {
	/**
	 * Scans `Environment.TEMPLATES_DIR` (installed) and the Downloads folder (not installed)
	 * for `.cgt` files and parses each into a [CgtFileItem].
	 */
	suspend fun listTemplateFiles(): Result<List<CgtFileItem>>

	/** Moves [item]'s file from Downloads into the templates directory and reloads templates. */
	suspend fun installTemplate(item: CgtFileItem): Result<Unit>

	/**
	 * Restores a copy of [item]'s file to Downloads, removes it from the templates directory, and
	 * reloads templates. Fails with [TemplateReplaceConflictException] if a file of the same name
	 * already exists in Downloads, unless [overwrite] is true.
	 */
	suspend fun uninstallTemplate(
		item: CgtFileItem,
		overwrite: Boolean = false,
	): Result<Unit>

	/** Deletes a not-installed [item]'s file from Downloads. */
	suspend fun deleteDownloadFile(item: CgtFileItem): Result<Unit>
}

/** Thrown by [TemplateRepository.uninstallTemplate] when restoring to Downloads would silently
 * overwrite an existing file there and the caller didn't opt into replacing it. */
class TemplateReplaceConflictException(
	val fileName: String,
) : IOException("A file named '$fileName' already exists in Downloads")
