package com.itsaky.androidide.repositories

import com.itsaky.androidide.templates.manager.models.CgtFileItem

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

	/**
	 * Copies [item]'s file from Downloads into the templates directory and reloads templates. The
	 * Downloads original is left in place; removing it is the caller's choice ([deleteDownloadFile]).
	 */
	suspend fun installTemplate(item: CgtFileItem): Result<Unit>

	/**
	 * Deletes [item] from the templates directory and reloads templates. There is no restore: the
	 * user re-installs from the original `.cgt`, or re-installs the plugin that provided it.
	 */
	suspend fun uninstallTemplate(item: CgtFileItem): Result<Unit>

	/** Deletes a not-installed [item]'s file from Downloads. */
	suspend fun deleteDownloadFile(item: CgtFileItem): Result<Unit>
}
