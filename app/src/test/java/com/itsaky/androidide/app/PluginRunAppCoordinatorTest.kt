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

package com.itsaky.androidide.app

import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.activities.editor.ProjectHandlerActivity
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Covers the paths on which [PluginRunAppCoordinator] never reaches a build: a plugin waits on
 * [com.itsaky.androidide.plugins.services.BuildAndLaunchCallback], so every one of them has to
 * answer rather than leave the caller on its own timeout.
 */
@RunWith(RobolectricTestRunner::class)
class PluginRunAppCoordinatorTest {
	@Test
	fun `givenNoEditorActivity_whenRunningTheApp_thenTheCallerIsToldImmediately`() {
		val outcomes = mutableListOf<Pair<Boolean, String>>()

		PluginRunAppCoordinator.runApp(null) { success, message -> outcomes += success to message }

		assertThat(outcomes).hasSize(1)
		assertThat(outcomes.single().first).isFalse()
	}

	@Test
	fun `givenADestroyedEditor_whenRunningTheApp_thenCancellationIsReportedOnce`() {
		val activity = mockk<ProjectHandlerActivity>()
		val lifecycle = LifecycleRegistry.createUnsafe(activity)
		every { activity.lifecycle } returns lifecycle
		// A destroyed scope drops the launched block entirely, so nothing inside it can report.
		// DESTROYED is only reachable from CREATED; there is no event down from INITIALIZED.
		lifecycle.currentState = Lifecycle.State.CREATED
		lifecycle.currentState = Lifecycle.State.DESTROYED

		val outcomes = mutableListOf<Pair<Boolean, String>>()
		PluginRunAppCoordinator.runApp(activity) { success, message -> outcomes += success to message }
		shadowOf(Looper.getMainLooper()).idle()

		assertThat(outcomes).hasSize(1)
		assertThat(outcomes.single().first).isFalse()
	}
}
