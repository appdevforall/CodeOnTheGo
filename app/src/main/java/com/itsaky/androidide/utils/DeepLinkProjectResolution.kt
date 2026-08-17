/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.utils

import android.app.Activity
import com.itsaky.androidide.resources.R.string
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File

private val log = LoggerFactory.getLogger("DeepLinkProjectResolution")

/**
 * Resolves [projectName] to a validated project directory under [projectsRoot] for a deep link,
 * handling the [SecurityException] [findValidProjectByName] can throw and reporting both "not
 * found" and "scan failed" to the user via `flashError` on the main thread. A `null` result means
 * the caller can just return -- either failure case already flashed its own message.
 *
 * Call from a background dispatcher (e.g. `Dispatchers.IO`); this only switches to
 * [Dispatchers.Main] itself for the user-facing error messages.
 */
suspend fun Activity.resolveDeepLinkProject(
	projectsRoot: File,
	projectName: String,
): File? {
	val projectDir =
		try {
			findValidProjectByName(projectsRoot, projectName)
		} catch (e: CancellationException) {
			throw e
		} catch (e: SecurityException) {
			log.error("Failed to scan {} for deep link", projectsRoot, e)
			withContext(Dispatchers.Main) {
				// Re-checked here, not before the hop -- the activity can start finishing during the
				// hop itself, and a check taken only beforehand would miss that window.
				if (!isFinishing && !isDestroyed) flashError(getString(string.msg_deeplink_scan_failed))
			}
			return null
		}

	if (projectDir == null) {
		withContext(Dispatchers.Main) {
			if (!isFinishing && !isDestroyed) {
				flashError(getString(string.msg_deeplink_project_not_found, projectName))
			}
		}
	}
	return projectDir
}
