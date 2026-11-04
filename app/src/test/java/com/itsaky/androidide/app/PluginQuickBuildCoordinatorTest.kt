package com.itsaky.androidide.app

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.plugins.services.QuickBuildAvailability
import com.itsaky.androidide.plugins.services.QuickBuildResult
import com.itsaky.androidide.plugins.services.QuickBuildState
import com.itsaky.androidide.plugins.services.QuickBuildUnavailableReason
import com.itsaky.androidide.quickbuild.QuickBuildRunTracker
import com.itsaky.androidide.resources.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.appdevforall.cotg.quickbuild.domain.classify.InvalidationReason
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
	) = PluginQuickBuildCoordinator.availabilityOf(
		experimentsEnabled,
		projectOpen,
		synced,
		pluginProject,
		hasAppModule,
		variantName,
	) { id, args ->
		"$id${args.toList()}"
	}

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
	fun `a plugin hears the same refusal the toolbar shows`() {
		val pluginProject = availability(pluginProject = true) as QuickBuildAvailability.Unavailable
		val release = availability(variantName = "freeRelease") as QuickBuildAvailability.Unavailable

		assertThat(pluginProject.message).isEqualTo("${R.string.quick_build_unsupported_plugin_project}[]")
		assertThat(release.message).isEqualTo("${R.string.quick_build_non_debuggable_variant}[freeRelease]")
		assertThat(availability(synced = false).let { (it as QuickBuildAvailability.Unavailable).message })
			.isEqualTo("${R.string.quick_build_waiting_for_sync}[]")
		assertThat(availability(hasAppModule = false).let { (it as QuickBuildAvailability.Unavailable).message })
			.isEqualTo("${R.string.quick_build_no_app_module}[]")
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

	private fun TestScope.trackAsync(
		status: StateFlow<QuickBuildStatus>,
		tracker: QuickBuildRunTracker,
		onBuildStarted: () -> Unit = {},
		askOutstanding: () -> Boolean = { false },
	) = async { PluginQuickBuildCoordinator.track(status, tracker, onBuildStarted, askOutstanding) { testScheduler.currentTime } }

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

	@Test
	fun `a tap queued behind the warm compile is followed to its build, not settled up to date`() =
		runTest {
			// The warm compile projects as the deployed generation, so the status shows nothing.
			val status = MutableStateFlow<QuickBuildStatus>(upToDate)
			val tracker = QuickBuildRunTracker(upToDate, 0L) { null }
			var askOutstanding = true

			val result = trackAsync(status, tracker, askOutstanding = { askOutstanding })
			advanceTimeBy(QuickBuildRunTracker.QUIET_MILLIS * 3)
			runCurrent()
			assertThat(result.isCompleted).isFalse()

			status.value = QuickBuildStatus.Building(3L)
			runCurrent()
			status.value = QuickBuildStatus.UpToDate(4L, 2_000L)
			askOutstanding = false
			runCurrent()
			assertThat(result.await()).isInstanceOf(QuickBuildResult.Reloaded::class.java)
		}

	@Test
	fun `a tap the session answers without building is up to date once answered`() =
		runTest {
			val status = MutableStateFlow<QuickBuildStatus>(upToDate)
			val tracker = QuickBuildRunTracker(upToDate, 0L) { null }
			var askOutstanding = true

			val result = trackAsync(status, tracker, askOutstanding = { askOutstanding })
			advanceTimeBy(QuickBuildRunTracker.QUIET_MILLIS + 1_000)
			runCurrent()
			assertThat(result.isCompleted).isFalse()

			askOutstanding = false
			advanceTimeBy(QuickBuildRunTracker.ASK_POLL_MILLIS)
			runCurrent()
			assertThat(result.await()).isEqualTo(QuickBuildResult.UpToDate)
		}

	@Test
	fun `an ask a dead compiler keeps recorded does not hold the run open`() =
		runTest {
			val down = QuickBuildStatus.Reconnecting(3L, restartFailed = true)
			val status = MutableStateFlow<QuickBuildStatus>(down)
			val tracker = QuickBuildRunTracker(down, 0L) { null }

			val result = trackAsync(status, tracker, askOutstanding = { true })
			advanceTimeBy(QuickBuildRunTracker.QUIET_MILLIS + 1)
			runCurrent()

			assertThat(result.await()).isInstanceOf(QuickBuildResult.Failed::class.java)
		}

	@Test
	fun `a throw while following the run fails it instead of escaping`() =
		runTest {
			val status = MutableStateFlow<QuickBuildStatus>(upToDate)
			val tracker = QuickBuildRunTracker(upToDate, 0L) { null }

			val result = trackAsync(status, tracker, onBuildStarted = { error("boom") })
			runCurrent()
			status.value = QuickBuildStatus.Building(3L)
			runCurrent()

			assertThat(result.await()).isEqualTo(QuickBuildResult.Failed("Quick Build lost track of this run: boom", null))
		}

	@Test
	fun `a stop before the tap reaches the session is held until it does`() {
		var sent = 0
		val handoff = PluginQuickBuildCoordinator.TapHandoff { sent++ }

		handoff.cancel()
		assertThat(sent).isEqualTo(0)

		handoff.onTapQueued()
		assertThat(sent).isEqualTo(1)
	}

	@Test
	fun `a stop after the tap reaches the session is sent at once`() {
		var sent = 0
		val handoff = PluginQuickBuildCoordinator.TapHandoff { sent++ }

		handoff.onTapQueued()
		assertThat(sent).isEqualTo(0)

		handoff.cancel()
		assertThat(sent).isEqualTo(1)
	}

	@Test
	fun `a build that starts with no stop asked is left alone`() {
		var sent = 0
		val handoff = PluginQuickBuildCoordinator.TapHandoff { sent++ }

		handoff.onTapQueued()
		handoff.onBuildStarted()

		assertThat(sent).isEqualTo(0)
	}

	@Test
	fun `a stop between a live tap and its build stops the build`() =
		runTest {
			val status = MutableStateFlow<QuickBuildStatus>(upToDate)
			val tracker = QuickBuildRunTracker(upToDate, 0L) { null }
			// A live session's stop cancels only a build already running, as the reducer's does.
			val handoff =
				PluginQuickBuildCoordinator.TapHandoff {
					if (status.value is QuickBuildStatus.Building) status.value = QuickBuildStatus.UpToDate(3L, null)
				}
			handoff.onTapQueued()

			val result = async { PluginQuickBuildCoordinator.track(status, tracker, handoff::onBuildStarted) { testScheduler.currentTime } }
			runCurrent()
			tracker.onCancelRequested()
			handoff.cancel()
			advanceTimeBy(250)
			status.value = QuickBuildStatus.Building(3L)
			runCurrent()
			status.value = QuickBuildStatus.UpToDate(4L, 1_200L)
			runCurrent()

			assertThat(result.await()).isEqualTo(QuickBuildResult.Cancelled)
		}

	@Test
	fun `a stop before a queued rebaseline starts stops the rebaseline`() =
		runTest {
			val reason = InvalidationReason.MANIFEST_CHANGED
			val queued = QuickBuildStatus.NeedsFullBuild(reason, 3L)
			val status = MutableStateFlow<QuickBuildStatus>(queued)
			val tracker = QuickBuildRunTracker(queued, 0L) { null }
			// A queued rebaseline's stop only withdraws the ask; its Provisioning is what a stop cancels.
			val handoff =
				PluginQuickBuildCoordinator.TapHandoff {
					if (status.value is QuickBuildStatus.Provisioning) {
						status.value = QuickBuildStatus.NeedsFullBuild(reason, 3L, awaitingRetry = true)
					}
				}
			handoff.onTapQueued()

			val result = async { PluginQuickBuildCoordinator.track(status, tracker, handoff::onBuildStarted) { testScheduler.currentTime } }
			runCurrent()
			tracker.onCancelRequested()
			handoff.cancel()
			status.value = QuickBuildStatus.Provisioning(reason)
			runCurrent()
			if (!result.isCompleted) {
				// Unstopped, the rebaseline lands and reloads.
				status.value = QuickBuildStatus.UpToDate(1L, null)
				runCurrent()
			}

			assertThat(result.await()).isEqualTo(QuickBuildResult.Cancelled)
		}

	@Test
	fun `a run whose session stops moving fails at the ceiling instead of holding the slot`() =
		runTest {
			val status = MutableStateFlow<QuickBuildStatus>(upToDate)
			val tracker = QuickBuildRunTracker(upToDate, 0L) { "the log" }

			val result = async { PluginQuickBuildCoordinator.track(status, tracker) { testScheduler.currentTime } }
			runCurrent()
			status.value = QuickBuildStatus.Building(3L)
			advanceTimeBy(PluginQuickBuildCoordinator.TRACK_CEILING_MILLIS - 1)
			runCurrent()
			assertThat(result.isCompleted).isFalse()

			advanceTimeBy(2)
			runCurrent()
			val giveUp = "Quick Build did not finish within 30 minutes; this run was given up on."
			assertThat(result.await()).isEqualTo(QuickBuildResult.Failed(giveUp, "the log"))
		}

	@Test
	fun `a stopped run that never settles is cancelled at the ceiling, not failed`() =
		runTest {
			val reason = InvalidationReason.MANIFEST_CHANGED
			val status = MutableStateFlow<QuickBuildStatus>(upToDate)
			val tracker = QuickBuildRunTracker(upToDate, 0L) { "the log" }

			val result = async { PluginQuickBuildCoordinator.track(status, tracker) { testScheduler.currentTime } }
			runCurrent()
			// The stop was accepted, but the rebaseline it cancelled never leaves Provisioning.
			status.value = QuickBuildStatus.Provisioning(reason)
			runCurrent()
			tracker.onCancelRequested()
			advanceTimeBy(PluginQuickBuildCoordinator.TRACK_CEILING_MILLIS + 1)
			runCurrent()

			assertThat(result.await()).isEqualTo(QuickBuildResult.Cancelled)
		}
}
