package com.itsaky.androidide.repositories

import java.io.File

/** A file in the Downloads folder, as offered by the Manager screen's install picker. */
data class DownloadedFile(
	val file: File,
	val sizeBytes: Long,
	val lastModified: Long,
) {
	val name: String get() = file.name
}

/**
 * Lists add-on files in the Downloads folder by extension.
 *
 * The system file picker cannot do this: SAF filters by MIME type, and neither `.cgp` nor `.cgt`
 * has a registered one, so it lists every file (ADFA-6729).
 */
interface DownloadFilesRepository {
	/**
	 * Files directly in the Downloads folder (not its subfolders) whose extension is [extension],
	 * ignoring case, newest first. Empty if the folder is missing or unreadable.
	 */
	suspend fun list(extension: String): List<DownloadedFile>
}
