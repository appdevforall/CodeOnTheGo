package org.appdevforall.cotg.quickbuild.service.session

import android.content.ComponentCallbacks2
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.appdevforall.cotg.quickbuild.data.DaemonReply
import org.appdevforall.cotg.quickbuild.data.ProxyAppInfo
import org.appdevforall.cotg.quickbuild.data.QuickBuildProjectLayout
import org.appdevforall.cotg.quickbuild.data.QuickBuildScratch
import org.appdevforall.cotg.quickbuild.protocol.ConfigureRequest
import org.appdevforall.cotg.quickbuild.service.FakeDaemon
import org.appdevforall.cotg.quickbuild.service.FakePaths
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

	private fun proxyApp(minApi: Int = ConfigureRequest.DEFAULT_MIN_API) =
		ProxyAppInfo(
			proxyAppPackage = "com.example.quickbuild",
			entryActivity = "com.example.MainActivity",
			apk = File(projectRoot, "proxy-app.apk"),
			classpath = emptyList(),
			proxyClassesDir = null,
			transformedManifest = null,
			minApi = minApi,
		)

	private fun layout() = QuickBuildProjectLayout(projectRoot)

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
	fun `a respawn superseded mid-start whose start also failed has no zombie to stop`() =
		runTest {
			val controller = controller()
			val epoch = controller.epochSnapshot()
			val gate = CompletableDeferred<Unit>()
			daemon.startGate = gate
			daemon.startReply = DaemonReply.Failed("spawn refused")
			var outcome: QuickBuildDaemonController.RespawnOutcome? = null
			val job = launch { outcome = controller.respawn(layout(), proxyApp(), epoch) }
			runCurrent() // parked inside daemon.start

			// Exactly one transition, as in the zombie case above - but this start brought no
			// daemon up, so a shutdown here would stop whatever the superseding flow owns.
			controller.markIntentionalTransition()
			gate.complete(Unit)
			advanceUntilIdle()
			job.join()

			// Superseded, not Failed: the successor flow owns the daemon lifecycle, so this
			// respawn's own failure is not the session's news.
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
	fun `respawn names a generic failure when the reply carries no operator message`() =
		runTest {
			val controller = controller()
			// Anything but Ok means "no daemon", and only Failed carries a message. The
			// outcome still has to name something: the manager renders it as the reason the
			// session went degraded.
			daemon.startReply = DaemonReply.BuildFailed(emptyList())
			val outcome = controller.respawn(layout(), proxyApp(), controller.epochSnapshot())
			assertThat(outcome)
				.isEqualTo(QuickBuildDaemonController.RespawnOutcome.Failed("unknown failure"))
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

	@Test
	fun `the daemon config takes its min API from the baseline the proxy app build dexed`() =
		runTest {
			// A project whose effective dex level is not the protocol default. The daemon
			// must dex increments the way the seed payload was dexed, so the value has to
			// travel from setup.json into the config rather than default at each end.
			val controller = controller()

			controller.respawn(layout(), proxyApp(minApi = 26), controller.epochSnapshot())

			assertThat(daemon.startConfigs.single().minApi).isEqualTo(26)
		}
}
