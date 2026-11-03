package com.itsaky.androidide.repositories

import org.adfa.constants.TEMPLATE_CORE_ARCHIVE
import java.io.File

/**
 * Add-on archives directly in [dir] (not its subfolders) whose extension is [extension], ignoring
 * case. Shared by the Templates tab and the Manager screen's + picker so the two list the same
 * files. Empty if [dir] is missing or unreadable.
 *
 * Leaves out:
 * - MediaProvider's placeholders. On Android 11+ it keeps a download still in progress, or a file
 *   in the trash, in its folder as `.pending-<expiry>-<name>` or `.trashed-<expiry>-<name>`, which
 *   keep the add-on extension. Offering one would install a partial archive, or a file the user
 *   deleted. Only that exact form is skipped, not every hidden name: the installer accepts a
 *   collection named `.name`, stored as `.name.cgt`, and uninstalling moves it to Downloads.
 * - The bundled core.cgt (ADFA-5645). It ships with the IDE, and installing a copy fails on its
 *   reserved name.
 */
internal fun addonFilesIn(
	dir: File,
	extension: String,
): List<File> =
	dir
		// Name checks first: they are free, and isFile is a stat per entry.
		.listFiles { file ->
			!MEDIA_STORE_PLACEHOLDER.matches(file.name) &&
				file.extension.equals(extension, ignoreCase = true) &&
				!file.name.equals(TEMPLATE_CORE_ARCHIVE, ignoreCase = true) &&
				file.isFile
		}.orEmpty()
		.toList()

// AOSP MediaProvider's FileUtils.PATTERN_EXPIRES_FILE, which names these files ".%s-%d-%s".
private val MEDIA_STORE_PLACEHOLDER = Regex("""^\.(pending|trashed)-\d+-.+$""", RegexOption.IGNORE_CASE)
