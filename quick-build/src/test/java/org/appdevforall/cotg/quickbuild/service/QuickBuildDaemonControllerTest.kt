package org.appdevforall.cotg.quickbuild.service

import android.content.ComponentCallbacks2
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.appdevforall.cotg.quickbuild.data.DaemonReply
import org.appdevforall.cotg.quickbuild.data.DefaultQuickBuildProjectLayout
import org.appdevforall.cotg.quickbuild.data.ProxyAppInfo
import org.appdevforall.cotg.quickbuild.data.QuickBuildScratch
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Seam tests for the daemon-epoch protocol, directly against
 * [QuickBuildDaemonController] (the manager's 100 tests drive the same paths
 * end-to-end; these pin the controller's own contract).
 */
class QuickBuildDaemonControllerTest {
	@TempDir lateinit var projectRoot: File

	private val daemon = FakeDaemon()

	private fun controller() =
		QuickBuildDaemonController(
			daemon = daemon,
			scratch = QuickBuildScratch(FakePaths(projectRoot).projectScratchRoot),
			paths = FakePaths(projectRoot),
		)

	private fun proxyApp() =
		ProxyAppInfo(
			proxyAppPackage = "com.example.quickbuild",
			entryActivity = "com.example.MainActivity",
			apk = File(projectRoot, "proxy-app.apk"),
			classpath = emptyList(),
			proxyClassesDir = null,
			transformedManifest = null,
		)

	private fun layout() = DefaultQuickBuildProjectLayout(projectRoot)

	@Test
	fun `respawn superseded before start never starts a daemon`() =
		runTest {
			val controller = controller()
			val epoch = controller.epochSnapshot()
			controller.markIntentionalTransition()
			val outcome = controller.respawn(layout(), proxyApp(), epoch)
			assertThat(outcome).isEqualTo(QuickBuildDaemonController.RespawnOutcome.Superseded)
			assertThat(daemon.startConfigs).isEmpty()
		}

	@Test
	fun `respawn superseded by exactly one transition mid-start stops its own zombie daemon`() =
		runTest {
			val controller = controller()
			val epoch = controller.epochSnapshot()
			val gate = CompletableDeferred<Unit>()
			daemon.startGate = gate
			var outcome: QuickBuildDaemonController.RespawnOutcome? = null
			val job = launch { outcome = controller.respawn(layout(), proxyApp(), epoch) }
			runCurrent() // parked inside daemon.start
			assertThat(daemon.startConfigs).hasSize(1)

			// EXACTLY one intentional transition: the superseding shutdown itself. The
			// daemon the stale start brought up is a zombie only the respawn knows about.
			controller.markIntentionalTransition()
			gate.complete(Unit)
			advanceUntilIdle()
			job.join()

			assertThat(outcome).isEqualTo(QuickBuildDaemonController.RespawnOutcome.Superseded)
			assertThat(daemon.shutdownCount).isEqualTo(1)
		}

	@Test
	fun `respawn superseded by two transitions discards without stopping the successor's daemon`() =
		runTest {
			val controller = controller()
			val epoch = controller.epochSnapshot()
			val gate = CompletableDeferred<Unit>()
			daemon.startGate = gate
			var outcome: QuickBuildDaemonController.RespawnOutcome? = null
			val job = launch { outcome = controller.respawn(layout(), proxyApp(), epoch) }
			runCurrent()

			// Two transitions = a successor flow already started a fresh daemon; the
			// stale respawn must not touch it.
			controller.markIntentionalTransition()
			controller.markIntentionalTransition()
			gate.complete(Unit)
			advanceUntilIdle()
			job.join()

			assertThat(outcome).isEqualTo(QuickBuildDaemonController.RespawnOutcome.Superseded)
			assertThat(daemon.shutdownCount).isEqualTo(0)
		}

	@Test
	fun `respawn reports the daemon's failure message`() =
		runTest {
			val controller = controller()
			daemon.startReply = DaemonReply.Failed("spawn refused")
			val outcome = controller.respawn(layout(), proxyApp(), controller.epochSnapshot())
			assertThat(outcome)
				.isEqualTo(QuickBuildDaemonController.RespawnOutcome.Failed("spawn refused"))
		}

	@Test
	fun `onTrimMemory at UI_HIDDEN keeps the daemon warm`() =
		runTest {
			val controller = controller()
			daemon.isRunning = true
			controller.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, buildInFlight = false)
			assertThat(daemon.shutdownCount).isEqualTo(0)
			assertThat(controller.epochSnapshot()).isEqualTo(0L)
		}

	@Test
	fun `onTrimMemory at RUNNING_LOW is a no-op`() =
		runTest {
			val controller = controller()
			daemon.isRunning = true
			controller.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, buildInFlight = false)
			// Not even deferred: a later idle retry must find nothing pending.
			controller.shrinkIfPending(buildInFlight = false)
			assertThat(daemon.shutdownCount).isEqualTo(0)
			assertThat(controller.epochSnapshot()).isEqualTo(0L)
		}

	@Test
	fun `onTrimMemory at RUNNING_CRITICAL with no build in flight shuts down and bumps the epoch once`() =
		runTest {
			val controller = controller()
			daemon.isRunning = true
			controller.onTrimMemory(
				ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
				buildInFlight = false,
			)
			assertThat(daemon.shutdownCount).isEqualTo(1)
			assertThat(controller.epochSnapshot()).isEqualTo(1L)
		}

	@Test
	fun `a shrink deferred while building applies on the next non-building state`() =
		runTest {
			val controller = controller()
			daemon.isRunning = true
			controller.onTrimMemory(
				ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
				buildInFlight = true,
			)
			assertThat(daemon.shutdownCount).isEqualTo(0)

			// The manager's state collector retries when the build's transition lands.
			controller.shrinkIfPending(buildInFlight = false)
			assertThat(daemon.shutdownCount).isEqualTo(1)
			assertThat(controller.epochSnapshot()).isEqualTo(1L)

			// Consumed: a second retry must not shut down (or bump) again.
			controller.shrinkIfPending(buildInFlight = false)
			assertThat(daemon.shutdownCount).isEqualTo(1)
			assertThat(controller.epochSnapshot()).isEqualTo(1L)
		}
}
