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
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The terminal teardown of the metrics watchers (ADFA-5486).
 *
 * The watchers each own a dedicated sampling thread that `newSingleThreadContext` keeps alive
 * until it is closed, so this is the one place that has to close rather than merely stop them.
 */
@RunWith(RobolectricTestRunner::class)
class MetricsViewModelTest {
	/** onCleared is protected, so it is reached the way the framework reaches it. */
	private fun cleared() = store.clear()

	private val store = ViewModelStore()

	private fun viewModel(): MetricsViewModel {
		// AndroidViewModelFactory, not NewInstanceFactory: MetricsViewModel became an
		// AndroidViewModel when the power page needed a Context for the battery broadcast, and
		// NewInstanceFactory reflects on a no-arg constructor that no longer exists. This class
		// has been failing with "Cannot create an instance of class MetricsViewModel" ever since,
		// which nothing noticed because the only CI job that runs unit tests runs them with
		// ignoreFailures set (ADFA-5559).
		val application = ApplicationProvider.getApplicationContext<Application>()
		val provider = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(application))
		return provider[MetricsViewModel::class.java]
	}

	@Test
	fun `clearing the view model closes every watcher for good`() {
		val model = viewModel()
		// All three, not two. The power watcher was added later and left out of this case, so its
		// close() -- and the sampling thread it owns -- was unasserted. Spelled out rather than
		// looped: the three watchers share no supertype that exposes isWatching.
		model.memoryUsageWatcher.startWatching()
		model.networkUsageWatcher.startWatching()
		model.powerUsageWatcher.startWatching()

		// Only the memory watcher is asserted to have started. The other two refuse when the
		// platform cannot supply their metric -- TrafficStats and the battery properties are both
		// unsupported off a device -- so requiring them to start here would pin the test
		// environment rather than the teardown. What the terminal property needs is that clear()
		// stops whatever was running and that nothing restarts afterwards, which is asserted for
		// all three below.
		assertThat(model.memoryUsageWatcher.isWatching).isTrue()

		cleared()

		// close(), not stopWatching(): a closed watcher gives up its sampling thread and refuses
		// to restart, which is what makes this the terminal teardown rather than a pause.
		assertThat(model.memoryUsageWatcher.isWatching).isFalse()
		assertThat(model.networkUsageWatcher.isWatching).isFalse()
		assertThat(model.powerUsageWatcher.isWatching).isFalse()

		model.memoryUsageWatcher.startWatching()
		model.networkUsageWatcher.startWatching()
		model.powerUsageWatcher.startWatching()
		assertThat(model.memoryUsageWatcher.isWatching).isFalse()
		assertThat(model.networkUsageWatcher.isWatching).isFalse()
		assertThat(model.powerUsageWatcher.isWatching).isFalse()
	}

	@Test
	fun `the watchers and the annotation store are the same instances across reads`() {
		val model = viewModel()

		// The history lives here precisely so it survives an activity being recreated; handing
		// back a new watcher per read would quietly defeat that.
		assertThat(model.memoryUsageWatcher).isSameInstanceAs(model.memoryUsageWatcher)
		assertThat(model.networkUsageWatcher).isSameInstanceAs(model.networkUsageWatcher)
		assertThat(model.powerUsageWatcher).isSameInstanceAs(model.powerUsageWatcher)
		assertThat(model.annotations).isSameInstanceAs(model.annotations)
	}
}
