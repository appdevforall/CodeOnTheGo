package com.itsaky.androidide.repositories

import com.itsaky.androidide.templates.manager.models.CgtFileItem
import java.io.IOException

/** Thrown by [TemplateRepository.uninstallTemplate] when Downloads already has a same-named file and `overwrite` is `false`. */
class DownloadFileConflictException(
	message: String,
) : IOException(message)

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
	 * reloads templates.
	 *
	 * Downloads can already have a same-named file - e.g. the "open a .cgt from outside the app"
	 * install path never deletes the file the user opened. If so and [overwrite] is `false`, this
	 * fails with [DownloadFileConflictException] instead of touching that file, so the caller can
	 * ask the user before retrying with `overwrite = true`.
	 */
	suspend fun uninstallTemplate(
		item: CgtFileItem,
		overwrite: Boolean = false,
	): Result<Unit>

	/** Deletes a not-installed [item]'s file from Downloads. */
	suspend fun deleteDownloadFile(item: CgtFileItem): Result<Unit>
}
