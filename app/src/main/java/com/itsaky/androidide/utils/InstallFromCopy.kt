package com.itsaky.androidide.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Installs from a copy of [source] at [copy], because an install deletes the file it is given and a
 * built plugin should stay for Export (ADFA-4930). The copy is always deleted afterwards: a failed
 * install, a copy that stops part-way, or a cancelled caller must not leave one in `filesDir/temp`.
 *
 * @param copy where to copy [source]; it must not exist yet.
 * @param install installs the copy.
 * @return the install's result, or the failure of the copy.
 */
internal suspend fun installFromCopy(
	source: File,
	copy: File,
	install: suspend (File) -> Result<Unit>,
): Result<Unit> =
	try {
		withContext(Dispatchers.IO) { source.copyTo(copy) }
		install(copy)
	} catch (e: IOException) {
		Result.failure(e)
	} finally {
		withContext(NonCancellable + Dispatchers.IO) { copy.delete() }
	}
