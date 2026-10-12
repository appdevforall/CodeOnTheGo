package com.itsaky.androidide.app

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.plugins.services.QuickBuildAvailability
import com.itsaky.androidide.plugins.services.QuickBuildResult
import com.itsaky.androidide.plugins.services.QuickBuildState
import com.itsaky.androidide.plugins.services.QuickBuildUnavailableReason
import com.itsaky.androidide.quickbuild.QuickBuildRunTracker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildStatus
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildTone
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PluginQuickBuildCoordinatorTest {
	private fun availability(
		experimentsEnabled: Boolean = true,
		projectOpen: Boolean = true,
		synced: Boolean = true,
		pluginProject: Boolean = false,
		hasAppModule: Boolean = true,
		variantName: String? = "debug",
	) = PluginQuickBuildCoordinator.availabilityOf(experimentsEnabled, projectOpen, synced, pluginProject, hasAppModule, variantName)

	private fun reasonOf(availability: QuickBuildAvailability) = (availability as QuickBuildAvailability.Unavailable).reason

	@Test
	fun `a synced debug app project is available`() {
		assertThat(availability()).isEqualTo(QuickBuildAvailability.Available)
		assertThat(availability(variantName = "freeDebug")).isEqualTo(QuickBuildAvailability.Available)
	}

	@Test
	fun `each unavailable reason is reported`() {
		assertThat(reasonOf(availability(experimentsEnabled = false))).isEqualTo(QuickBuildUnavailableReason.EXPERIMENTS_DISABLED)
		assertThat(reasonOf(availability(projectOpen = false))).isEqualTo(QuickBuildUnavailableReason.NO_PROJECT)
		assertThat(reasonOf(availability(synced = false))).isEqualTo(QuickBuildUnavailableReason.PROJECT_SYNCING)
		assertThat(reasonOf(availability(pluginProject = true))).isEqualTo(QuickBuildUnavailableReason.PLUGIN_PROJECT)
		assertThat(reasonOf(availability(hasAppModule = false))).isEqualTo(QuickBuildUnavailableReason.NO_APP_MODULE)
		assertThat(reasonOf(availability(variantName = "release"))).isEqualTo(QuickBuildUnavailableReason.RELEASE_VARIANT)
		assertThat(reasonOf(availability(variantName = "freeRelease"))).isEqualTo(QuickBuildUnavailableReason.RELEASE_VARIANT)
	}

	@Test
	fun `experiments off wins over everything else`() {
		val all = availability(experimentsEnabled = false, projectOpen = false, synced = false, pluginProject = true)

		assertThat(reasonOf(all)).isEqualTo(QuickBuildUnavailableReason.EXPERIMENTS_DISABLED)
	}

	@Test
	fun `a project still syncing is not mistaken for one with no app module`() {
		// Before the sync the model has no modules at all.
		assertThat(reasonOf(availability(synced = false, hasAppModule = false))).isEqualTo(QuickBuildUnavailableReason.PROJECT_SYNCING)
	}

	@Test
	fun `every button tone maps to a status`() {
		assertThat(QuickBuildTone.entries.map(PluginQuickBuildCoordinator::stateOf))
			.containsExactly(
				QuickBuildState.READY,
				QuickBuildState.BUILDING,
				QuickBuildState.FULL_BUILD_NEXT,
				QuickBuildState.RECONNECTING,
				QuickBuildState.ERROR,
			).inOrder()
	}

	private val upToDate = QuickBuildStatus.UpToDate(3L, 900L)

	@Test
	fun `a tap with nothing to build is up to date after the quiet window`() =
		runTest {
			val status = MutableStateFlow<QuickBuildStatus>(upToDate)
			val tracker = QuickBuildRunTracker(upToDate, 0L) { null }

			val result = async { PluginQuickBuildCoordinator.track(status, tracker) { testScheduler.currentTime } }
			advanceTimeBy(QuickBuildRunTracker.QUIET_MILLIS - 1)
			runCurrent()
			assertThat(result.isCompleted).isFalse()

			advanceTimeBy(2)
			runCurrent()
			assertThat(result.await()).isEqualTo(QuickBuildResult.UpToDate)
		}

	@Test
	fun `a build that starts inside the quiet window is followed to its landing`() =
		runTest {
			val status = MutableStateFlow<QuickBuildStatus>(upToDate)
			val tracker = QuickBuildRunTracker(upToDate, 0L) { null }

			val result = async { PluginQuickBuildCoordinator.track(status, tracker) { testScheduler.currentTime } }
			advanceTimeBy(1_000)
			status.value = QuickBuildStatus.Building(3L)
			// A build longer than the quiet window must not be cut short by it.
			advanceTimeBy(QuickBuildRunTracker.QUIET_MILLIS * 4)
			runCurrent()
			assertThat(result.isCompleted).isFalse()

			status.value = QuickBuildStatus.UpToDate(4L, 20_000L)
			runCurrent()
			assertThat(result.await()).isEqualTo(QuickBuildResult.Reloaded(durationMillis = 21_000L, restarted = false))
		}
}
