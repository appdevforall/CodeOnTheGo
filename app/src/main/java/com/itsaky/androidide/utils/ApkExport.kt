package com.itsaky.androidide.utils

import java.io.File
import java.io.IOException

/** Copies a built APK out of app storage under a readable name (ADFA-4930). */
internal object ApkExport {
	const val MIME_TYPE = "application/vnd.android.package-archive"

	/**
	 * Copies [apk] into [dir] as `<name>.apk`, or `<name> (2).apk` and so on when that is taken, so
	 * an earlier export is never overwritten.
	 *
	 * @param name the file name without `.apk`; characters a file name cannot hold are replaced.
	 * @return the copy.
	 * @throws IOException when [dir] cannot be created or the copy fails.
	 */
	fun copyTo(
		apk: File,
		dir: File,
		name: String,
	): File {
		if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
		val target = freeTarget(dir, name.map { if (it in "/\\:*?\"<>|" || it.isISOControl()) '_' else it }.joinToString(""))
		return apk.copyTo(target)
	}

	private fun freeTarget(
		dir: File,
		name: String,
	): File =
		generateSequence(1) { it + 1 }
			.map { n -> File(dir, if (n == 1) "$name.apk" else "$name ($n).apk") }
			.first { !it.exists() }
}
