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

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.itsaky.androidide.utils.DevicePowerSource
import com.itsaky.androidide.utils.MemoryUsageWatcher
import com.itsaky.androidide.utils.MetricsAnnotationStore
import com.itsaky.androidide.utils.MetricsSource
import com.itsaky.androidide.utils.NetworkUsageWatcher
import com.itsaky.androidide.utils.PowerUsageWatcher

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
class MetricsViewModel(
	application: Application,
) : AndroidViewModel(application),
	MetricsSource.Metrics {
	override val memoryUsageWatcher = MemoryUsageWatcher()

	override val networkUsageWatcher = NetworkUsageWatcher()

	/**
	 * Temperature and power (ADFA-5499). Needs a Context for the battery broadcast, which is why
	 * this is an AndroidViewModel.
	 */
	override val powerUsageWatcher = PowerUsageWatcher(source = DevicePowerSource(application))

	/** Significant events for the charts to annotate (ADFA-5486). */
	override val annotations = MetricsAnnotationStore()

	init {
		// So a crash handler can reach the history (ADFA-5526). It has no activity to ask.
		MetricsSource.register(this)
	}

	override fun onCleared() {
		super.onCleared()
		MetricsSource.unregister(this)
		// close(), not stopWatching(): this is the terminal teardown, and each watcher holds a
		// dedicated sampling thread that newSingleThreadContext keeps alive until it is closed.
		memoryUsageWatcher.close()
		networkUsageWatcher.close()
		powerUsageWatcher.close()
	}
}
