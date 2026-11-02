package com.itsaky.androidide.repositories

import org.adfa.constants.TEMPLATE_CORE_ARCHIVE
import java.io.File

/**
 * Add-on archives directly in [dir] (not its subfolders) whose extension is [extension], ignoring
 * case. Shared by the Templates tab and the Manager screen's + picker so the two list the same
 * files. Empty if [dir] is missing or unreadable.
 *
 * Leaves out:
 * - Hidden names. On Android 11+ MediaProvider keeps a download still in progress, or a file in
 *   the trash, in its folder as `.pending-<expiry>-<name>` or `.trashed-<expiry>-<name>`, which
 *   keep the add-on extension. Offering one would install a partial archive, or a file the user
 *   deleted.
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
			!file.name.startsWith(".") &&
				file.extension.equals(extension, ignoreCase = true) &&
				!file.name.equals(TEMPLATE_CORE_ARCHIVE, ignoreCase = true) &&
				file.isFile
		}.orEmpty()
		.toList()
