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
 *  along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.utils

import com.itsaky.androidide.roomData.recentproject.RecentProject
import com.itsaky.androidide.templates.Language
import java.io.File

/** Collapses Recents rows that resolve to the same directory, keeping useful project metadata. */
internal fun reconcileRecentProjectLocations(projects: List<RecentProject>): List<RecentProject> =
	projects
		.groupBy { project -> File(project.location).canonicalProjectLocation() }
		.map { (canonicalLocation, duplicates) ->
			val latest = duplicates.maxBy { it.id }
			val earliestCreatedAt =
				duplicates
					.mapNotNull { project ->
						project.createdAt.toLongOrNull()?.let { it to project.createdAt }
					}.minByOrNull { it.first }
					?.second ?: latest.createdAt
			val latestModifiedAt =
				duplicates
					.mapNotNull { project ->
						project.lastModified.toLongOrNull()?.let { it to project.lastModified }
					}.maxByOrNull { it.first }
					?.second ?: latest.lastModified
			latest.copy(
				location = canonicalLocation,
				createdAt = earliestCreatedAt,
				lastModified = latestModifiedAt,
				templateName = duplicates.firstUsefulValue { it.templateName },
				language = duplicates.firstUsefulValue { it.language },
			)
		}

private inline fun <T> List<RecentProject>.firstUsefulValue(value: (RecentProject) -> T): T {
	val unknown = Language.Unknown.lang
	return firstNotNullOfOrNull { project ->
		value(project).takeUnless { it.toString().equals(unknown, ignoreCase = true) }
	} ?: value(first())
}
