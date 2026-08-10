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

import android.content.Context
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.itsaky.androidide.analytics.IAnalyticsManager
import com.itsaky.androidide.preferences.internal.GeneralPreferences
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.roomData.recentproject.RecentProject
import com.itsaky.androidide.roomData.recentproject.RecentProjectRoomDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Marks [root] as the currently open project (singleton state + last-opened pref), records it in
 * Recents, and tracks the open in analytics -- the same bookkeeping
 * [com.itsaky.androidide.activities.MainActivity.openProject] does for a normal manual open,
 * extracted so a deep-link-triggered project switch gets it too even though that path bypasses
 * `openProject` entirely (see
 * [com.itsaky.androidide.activities.editor.EditorHandlerActivity.onDestroy]).
 *
 * Uses [ProcessLifecycleOwner]'s scope rather than a per-activity one, since this can run from an
 * activity's `onDestroy()` after its own `lifecycleScope` has already been cancelled.
 */
fun recordProjectOpenedBookkeeping(
	context: Context,
	root: File,
	project: RecentProject?,
	analyticsManager: IAnalyticsManager,
) {
	ProjectManagerImpl.getInstance().projectPath = root.absolutePath
	GeneralPreferences.lastOpenedProject = root.absolutePath

	val scope = ProcessLifecycleOwner.get().lifecycleScope
	scope.launch(Dispatchers.IO) {
		val location = root.absolutePath
		val recentProject =
			project ?: RecentProject(
				name = root.name,
				location = location,
				createdAt = getCreatedTime(location).toString(),
				lastModified = getLastModifiedTime(location).toString(),
			)
		RecentProjectRoomDatabase.getDatabase(context, scope).recentProjectDao().insert(recentProject)
	}

	analyticsManager.trackProjectOpened(root.absolutePath)
}
