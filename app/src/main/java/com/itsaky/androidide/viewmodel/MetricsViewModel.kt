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

package com.itsaky.androidide.viewmodel

import androidx.lifecycle.ViewModel
import com.itsaky.androidide.utils.MemoryUsageWatcher
import com.itsaky.androidide.utils.NetworkUsageWatcher

/**
 * Owns the sample history behind the editor's metrics carousel.
 *
 * The watchers used to be fields on the editor activity, and survived rotation only because
 * `EditorActivityKt` happens to declare `orientation` in its `configChanges`. Drop that flag, or add
 * a screen that does not declare it, and an hour of history would vanish silently. Holding them here
 * makes survival a property of the ViewModel lifecycle instead of a manifest coincidence
 * (ADFA-5486).
 *
 * This survives configuration changes and activity recreation. It does not survive the process being
 * killed -- see ADFA-5494.
 */
class MetricsViewModel : ViewModel() {
	val memoryUsageWatcher = MemoryUsageWatcher()

	val networkUsageWatcher = NetworkUsageWatcher()

	override fun onCleared() {
		super.onCleared()
		// close(), not stopWatching(): this is the terminal teardown, and each watcher holds a
		// dedicated sampling thread that newSingleThreadContext keeps alive until it is closed.
		memoryUsageWatcher.close()
		networkUsageWatcher.close()
	}
}
