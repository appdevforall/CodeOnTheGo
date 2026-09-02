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
 * The outcome of [resolveDeepLinkProject]. Keeps "no such project" apart from "could not tell",
 * which a bare `File?` collapsed: callers record a definitive absence so the link stops re-reporting
 * itself on every recreate, and recording an [Unverifiable] the same way made a momentary
 * filesystem failure kill a perfectly valid link permanently (ADFA-5067 review).
 */
sealed interface DeepLinkProjectLookup {
	data class Found(
		val projectDir: File,
	) : DeepLinkProjectLookup

	/** No project of that name exists. Definitive, so callers may remember it. */
	data object NotFound : DeepLinkProjectLookup

	/** The lookup failed for a reason unrelated to the project's existence. Remember nothing. */
	data object Unverifiable : DeepLinkProjectLookup
}

/**
 * Resolves [projectName] to a validated project directory under [projectsRoot] for a deep link,
 * reporting every failure to the user via `flashError` on the main thread. The caller can return on
 * anything but [DeepLinkProjectLookup.Found] -- each failure case has already shown its own message
 * -- but must consult *which* failure it was before recording the request as dealt with.
 *
 * Call from a background dispatcher (e.g. `Dispatchers.IO`); this only switches to
 * [Dispatchers.Main] itself for the user-facing error messages.
 */
suspend fun Activity.resolveDeepLinkProject(
	projectsRoot: File,
	projectName: String,
): DeepLinkProjectLookup {
	val lookup =
		try {
			lookupValidProjectByName(projectsRoot, projectName)
		} catch (e: CancellationException) {
			throw e
		} catch (e: SecurityException) {
			log.error("Failed to scan {} for deep link", projectsRoot, e)
			flashOnMain(getString(string.msg_deeplink_scan_failed))
			// A denied scan says nothing about whether the project is there.
			return DeepLinkProjectLookup.Unverifiable
		}

	return when (lookup) {
		is ProjectNameLookup.Found -> {
			DeepLinkProjectLookup.Found(lookup.dir)
		}

		is ProjectNameLookup.Unverifiable -> {
			log.error("Could not determine whether project {} exists under {}", projectName, projectsRoot, lookup.cause)
			// Deliberately the scan-failed message, not "no project named X": telling the user that a
			// project they can see in the projects list does not exist is worse than saying the
			// lookup failed.
			flashOnMain(getString(string.msg_deeplink_scan_failed))
			DeepLinkProjectLookup.Unverifiable
		}

		ProjectNameLookup.NotFound -> {
			flashOnMain(getString(string.msg_deeplink_project_not_found, projectName))
			DeepLinkProjectLookup.NotFound
		}
	}
}

private suspend fun Activity.flashOnMain(message: String) {
	withContext(Dispatchers.Main) {
		// Re-checked here, not before the hop -- the activity can start finishing during the hop
		// itself, and a check taken only beforehand would miss that window.
		if (!isFinishing && !isDestroyed) flashError(message)
	}
}
