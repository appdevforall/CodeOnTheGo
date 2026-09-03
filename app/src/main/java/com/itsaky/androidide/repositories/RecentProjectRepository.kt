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

package com.itsaky.androidide.repositories

import com.itsaky.androidide.roomData.recentproject.RecentProject

/**
 * Repository for recording a project's presence in Recents -- keeps [RecentProjectDao] (a Room
 * data source) out of the UI layer, per ARCHITECTURE.md's UI -> ViewModel -> Repository -> data
 * source layering.
 */
interface RecentProjectRepository {
	/** Inserts [recentProject] into Recents; a no-op if a row for its location already exists. */
	suspend fun insert(recentProject: RecentProject)

	/** Updates the detected language for the Recents row at [location]. */
	suspend fun updateLanguage(
		location: String,
		language: String,
	)
}
