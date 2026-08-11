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

import android.database.SQLException
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.itsaky.androidide.analytics.IAnalyticsManager
import com.itsaky.androidide.preferences.internal.GeneralPreferences
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.roomData.recentproject.RecentProject
import com.itsaky.androidide.roomData.recentproject.RecentProjectDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File

private val log = LoggerFactory.getLogger("ProjectOpenBookkeeping")

/**
 * Marks [root] as the currently open project (singleton state + last-opened pref), records it in
 * Recents, and tracks the open in analytics -- the same bookkeeping
 * [com.itsaky.androidide.activities.MainActivity.openProject] does for a normal manual open,
 * extracted so a deep-link-triggered project switch gets it too even though that path bypasses
 * `openProject` entirely (see
 * [com.itsaky.androidide.activities.editor.EditorHandlerActivity.onDestroy]).
 *
 * [recentProjectDao] is the caller's Koin-provided instance (`by inject()`), the same one
 * `di/AppModule.kt` wires into `MainViewModel`/`RecentProjectsViewModel` -- per ADR 0001/0006,
 * persistence is always acquired through Koin, never by re-deriving the database directly.
 *
 * Uses [ProcessLifecycleOwner]'s scope rather than a per-activity one, since this can run from an
 * activity's `onDestroy()` after its own `lifecycleScope` has already been cancelled.
 */
fun recordProjectOpenedBookkeeping(
	recentProjectDao: RecentProjectDao,
	root: File,
	project: RecentProject?,
	analyticsManager: IAnalyticsManager,
) {
	ProjectManagerImpl.getInstance().projectPath = root.absolutePath
	GeneralPreferences.lastOpenedProject = root.absolutePath

	ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.IO) {
		val location = root.absolutePath
		val recentProject =
			project ?: RecentProject(
				name = root.name,
				location = location,
				createdAt = getCreatedTime(location).toString(),
				lastModified = getLastModifiedTime(location).toString(),
			)
		try {
			recentProjectDao.insert(recentProject)
		} catch (e: SQLException) {
			// This runs on ProcessLifecycleOwner's app-wide scope -- an uncaught exception here would
			// crash the whole process, not just fail to record one Recents entry. The project-open
			// state above is already set synchronously, so a Recents-write failure doesn't affect it.
			// Catches SQLException specifically (Room propagates it, or subtypes like
			// SQLiteConstraintException, from a failed @Insert) rather than a blanket Exception, so an
			// unrelated bug here still surfaces instead of being silently swallowed.
			log.warn("Failed to record opened project '{}' in Recents", recentProject.name, e)
		}
	}

	analyticsManager.trackProjectOpened(root.absolutePath)
}
