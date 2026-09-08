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

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.project.AndroidModels
import com.itsaky.androidide.projects.api.AndroidModule
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Rule
import org.junit.Test

/**
 * Covers [BuildViewModel.runQuickBuild]'s single-build guard. The dispatcher is deliberately
 * [StandardTestDispatcher] rather than the unconfined default: nothing the view model launches runs
 * until the test advances it, which is the window a second caller races through.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BuildViewModelTest {
	@get:Rule
	val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

	private val module = mockk<AndroidModule>(relaxed = true)
	private val variant: AndroidModels.AndroidVariant = AndroidModels.AndroidVariant.getDefaultInstance()

	@Test
	fun `givenAQueuedBuild_whenASecondRequestArrivesBeforeItRuns_thenTheSecondIsRejected`() {
		val viewModel = BuildViewModel()
		val outcomes = mutableListOf<BuildState>()

		// Neither launched block has run, so the second call sees exactly what a second thread
		// would racing the first: the state a coroutine body has not had the chance to claim yet.
		viewModel.runQuickBuild(module, variant, launchInDebugMode = false) { outcomes += it }
		viewModel.runQuickBuild(module, variant, launchInDebugMode = false) { outcomes += it }

		assertThat(outcomes).containsExactly(BuildState.Error("A build is already in progress."))
	}

	@Test
	fun `givenNoBuild_whenRequestingOne_thenTheStateIsClaimedBeforeTheCoroutineRuns`() {
		val viewModel = BuildViewModel()

		viewModel.runQuickBuild(module, variant, launchInDebugMode = false)

		assertThat(viewModel.buildState.value).isEqualTo(BuildState.InProgress)
	}
}
