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
import com.itsaky.androidide.lookup.Lookup
import com.itsaky.androidide.project.AndroidModels
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.projects.api.AndroidModule
import com.itsaky.androidide.projects.builder.BuildService
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CompletableFuture

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

	private fun awaitOutcome(outcomes: List<BuildState>) {
		repeat(100) {
			mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()
			if (outcomes.isNotEmpty()) return
			Thread.sleep(20)
		}
	}

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

	@Test
	fun `givenAQueuedBuild_whenTasksAreRequested_thenTheRequestIsRefusedAndReported`() {
		val viewModel = BuildViewModel()
		val outcomes = mutableListOf<BuildState>()
		viewModel.runQuickBuild(module, variant, launchInDebugMode = false)

		val accepted = viewModel.runTasks(listOf(":app:installDebug")) { outcomes += it }

		assertThat(accepted).isFalse()
		assertThat(outcomes).containsExactly(BuildState.Error("A build is already in progress."))
	}

	@Test
	fun `givenNoBuild_whenTasksAreRequested_thenTheSlotIsClaimedBeforeTheCoroutineRuns`() {
		val viewModel = BuildViewModel()

		val accepted = viewModel.runTasks(listOf(":app:installDebug"))

		assertThat(accepted).isTrue()
		assertThat(viewModel.buildState.value).isEqualTo(BuildState.InProgress)
	}

	@Test
	fun `givenTheProjectModel_thenOnlyInstallTasksOfAnAppVariantAreRoutedToTheInstaller`() {
		val debug =
			AndroidModels.AndroidVariant
				.newBuilder()
				.setName("debug")
				.setMainArtifact(AndroidModels.AndroidArtifact.newBuilder().setAssembleTaskName("assembleDebug"))
				.build()
		val app =
			mockk<AndroidModule> {
				every { path } returns ":app"
				every { variantList } returns listOf(debug)
			}
		val projectManager = mockk<IProjectManager> { every { getAndroidAppModules() } returns listOf(app) }
		val viewModel = BuildViewModel { projectManager }

		assertThat(viewModel.installsAnAppVariant(listOf(":app:installDebug"))).isTrue()
		assertThat(viewModel.installsAnAppVariant(listOf("installDebug", ":app:assembleDebug"))).isTrue()
		assertThat(viewModel.installsAnAppVariant(listOf(":app:installRelease"))).isFalse()
		assertThat(viewModel.installsAnAppVariant(listOf(":lib:installDebug"))).isFalse()
		assertThat(viewModel.installsAnAppVariant(listOf(":installDist", ":app:installGitHooks"))).isFalse()
	}

	@Test
	fun `givenANullTaskResult_whenTasksRun_thenTheFailureIsReportedInsteadOfCrashing`() {
		val buildService =
			mockk<BuildService> {
				every { executeTasks(any<List<String>>()) } returns CompletableFuture.completedFuture<TaskExecutionResult>(null)
			}
		Lookup.getDefault().register(BuildService.KEY_BUILD_SERVICE, buildService)
		try {
			val viewModel = BuildViewModel()
			val outcomes = mutableListOf<BuildState>()

			viewModel.runTasks(listOf(":app:installDebug")) { outcomes += it }
			awaitOutcome(outcomes)

			assertThat(outcomes).containsExactly(BuildState.Error("Task execution failed: null"))
		} finally {
			Lookup.getDefault().unregister(BuildService.KEY_BUILD_SERVICE)
		}
	}

	@Test
	fun `givenNoBuildService_whenTasksRun_thenTheRunEndsInAnErrorReportedOnce`() {
		val viewModel = BuildViewModel()
		val outcomes = mutableListOf<BuildState>()

		viewModel.runTasks(listOf(":app:installDebug")) { outcomes += it }
		mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

		assertThat(outcomes).containsExactly(BuildState.Error("Build service not found."))
		assertThat(viewModel.buildState.value).isEqualTo(BuildState.Error("Build service not found."))
	}
}
