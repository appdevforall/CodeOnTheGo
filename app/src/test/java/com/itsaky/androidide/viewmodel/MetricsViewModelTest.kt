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

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
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
		val provider = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())
		return provider[MetricsViewModel::class.java]
	}

	@Test
	fun `clearing the view model closes both watchers for good`() {
		val model = viewModel()
		model.memoryUsageWatcher.startWatching()
		model.networkUsageWatcher.startWatching()
		assertThat(model.memoryUsageWatcher.isWatching).isTrue()

		cleared()

		// close(), not stopWatching(): a closed watcher gives up its sampling thread and refuses
		// to restart, which is what makes this the terminal teardown rather than a pause.
		assertThat(model.memoryUsageWatcher.isWatching).isFalse()
		assertThat(model.networkUsageWatcher.isWatching).isFalse()

		model.memoryUsageWatcher.startWatching()
		model.networkUsageWatcher.startWatching()
		assertThat(model.memoryUsageWatcher.isWatching).isFalse()
		assertThat(model.networkUsageWatcher.isWatching).isFalse()
	}

	@Test
	fun `the watchers and the annotation store are the same instances across reads`() {
		val model = viewModel()

		// The history lives here precisely so it survives an activity being recreated; handing
		// back a new watcher per read would quietly defeat that.
		assertThat(model.memoryUsageWatcher).isSameInstanceAs(model.memoryUsageWatcher)
		assertThat(model.networkUsageWatcher).isSameInstanceAs(model.networkUsageWatcher)
		assertThat(model.annotations).isSameInstanceAs(model.annotations)
	}
}
