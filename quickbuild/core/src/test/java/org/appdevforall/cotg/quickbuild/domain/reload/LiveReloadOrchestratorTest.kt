@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.appdevforall.cotg.quickbuild.domain.reload

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.appdevforall.cotg.quickbuild.domain.classify.BuildRoute
import org.appdevforall.cotg.quickbuild.domain.classify.ChangeClassifier
import org.appdevforall.cotg.quickbuild.domain.classify.InvalidationReason
import org.appdevforall.cotg.quickbuild.service.telemetry.report
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Pins the concurrency model: the pending changed-set is never lost - not by a save landing
 * mid-build, not by a failed compile, not by a superseded build.
 *
 * The ways that is easy to break: clearing changedSrc BEFORE a compile drops the user's edits
 * when it fails, conflating an empty changed-set with an unknown one runs spurious full
 * recompiles, and an untagged result lets a superseded build's outcome land anyway.
 */
class LiveReloadOrchestratorTest {
	private class GatedExecutor : LiveReloadExecutor {
		val requests = mutableListOf<BuildRequest>()
		val gates = mutableListOf<CompletableDeferred<BuildOutcome>>()
		var cancellations = 0
		var throwOnNext: Throwable? = null
		var promotions = 0

		/**
		 * How many builds ran all the way to returning an outcome - the stand-in for a payload
		 * reaching the proxy app. An abandoned build must never get this far.
		 */
		var deploys = 0

		override fun markCurrentBuildUserInitiated() {
			promotions++
		}

		override suspend fun execute(request: BuildRequest): BuildOutcome {
			requests += request
			throwOnNext?.let { error ->
				throwOnNext = null
				throw error
			}
			val gate = CompletableDeferred<BuildOutcome>()
			gates += gate
			try {
				val outcome = gate.await()
				deploys++
				return outcome
			} catch (e: CancellationException) {
				cancellations++
				throw e
			}
		}

		fun finish(
			index: Int,
			outcome: BuildOutcome,
		) {
			gates[index].complete(outcome)
		}
	}

	private fun known(vararg paths: String) = ChangedFiles.Known(paths.map(::File).toSet())

	private fun success(generation: Long = 1L) = BuildOutcome.Success(generation = generation, durationMillis = 100)

	private fun compileError() =
		BuildOutcome.CompileError(
			listOf(BuildDiagnostic(BuildDiagnostic.Severity.ERROR, "expecting ')'", "B.kt", 7, 13)),
		)

	/**
	 * A relink that fails for something no edit can reach - the daemon could not link at all,
	 * as opposed to aapt2 rejecting the user's XML (which is a [compileError]).
	 */
	private fun relinkFailure() = BuildOutcome.InfrastructureFailure("relink: library resource snapshot is missing R.txt")

	/** A deploy that did not land, without the not-connected shape that escalates on a repeat. */
	private fun deployFailure() = BuildOutcome.DeployFailure("the payload could not be written")

	private fun notConnected() =
		BuildOutcome.DeployFailure(
			"Proxy app is not connected. Relaunch your app to reconnect, then deploy again.",
			proxyAppNotConnected = true,
		)

	/**
	 * aapt2 rejecting the project's resources. Every error names a file under `res/`, which is
	 * how the orchestrator tells an aapt2 rejection from a kotlinc one - the two never mix in
	 * one outcome, because a failed compile returns before the relink runs.
	 */
	private fun resourceError() =
		BuildOutcome.CompileError(
			listOf(
				BuildDiagnostic(
					BuildDiagnostic.Severity.ERROR,
					"resource style/Theme.Library not found",
					resLayout,
					12,
					5,
				),
			),
		)

	private val resLayout = "app/src/main/res/layout/activity_main.xml"
	private val srcA = "app/src/main/java/com/example/A.kt"
	private val srcB = "app/src/main/java/com/example/B.kt"
	private val srcC = "app/src/main/java/com/example/C.kt"

	@Test
	fun `a save starts a build with exactly the saved files`() =
		runTest {
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			orchestrator.onFilesChanged(known(srcA, srcB))
			runCurrent()

			assertThat(executor.requests).hasSize(1)
			assertThat(executor.requests[0].changes).isEqualTo(known(srcA, srcB))
			assertThat(executor.requests[0].route).isEqualTo(BuildRoute.CodeOnly)
			assertThat(events).containsExactly(
				OrchestratorEvent.BuildStarted(1L, BuildRoute.CodeOnly, known(srcA, srcB)),
			)
		}

	@Test
	fun `a build's trigger stamp is the arriving change's time - e2e t0`() =
		runTest {
			var nowMs = 100L
			val executor = GatedExecutor()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope, now = { nowMs }) {}

			nowMs = 100L
			orchestrator.onFilesChanged(known(srcA))
			runCurrent()

			assertThat(executor.requests.single().triggeredAtMillis).isEqualTo(100L)
		}

	@Test
	fun `a coalesced follow-up's trigger is its EARLIEST mid-build change - not the change that landed later`() =
		runTest {
			var nowMs = 100L
			val executor = GatedExecutor()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope, now = { nowMs }) {}

			orchestrator.onFilesChanged(known(srcA)) // starts build 0 at t=100
			runCurrent()
			nowMs = 200L
			orchestrator.onFilesChanged(known(srcB)) // first of the mid-build batch
			nowMs = 300L
			orchestrator.onFilesChanged(known(srcC)) // coalesces; must not reset t0
			runCurrent()

			executor.finish(0, success(generation = 1))
			runCurrent()

			assertThat(executor.requests).hasSize(2)
			assertThat(executor.requests[0].triggeredAtMillis).isEqualTo(100L)
			// The follow-up waited from srcB's arrival (200), not srcC's (300).
			assertThat(executor.requests[1].triggeredAtMillis).isEqualTo(200L)
		}

	@Test
	fun `a forced catch-up on an empty queue is stamped at the request time`() =
		runTest {
			// The reconnect catch-up is the one remaining caller that forces a build of an
			// empty set; a user tap with nothing pending builds nothing at all.
			var nowMs = 500L
			val executor = GatedExecutor()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope, now = { nowMs }) {}

			orchestrator.onLiveReloadRequested(userInitiated = false)
			runCurrent()

			assertThat(executor.requests.single().triggeredAtMillis).isEqualTo(500L)
		}

	@Test
	fun `a failed build's trigger is not inherited by the save that follows it`() =
		runTest {
			// The T16 defect: the failed attempt's batch returns to pending, and with it its t0.
			// The next build then measured from that dead stamp, so the pane reported 197.3s of
			// queueing for a 2.25s save - about 100x, and the number the feature is judged on.
			var nowMs = 100L
			val executor = GatedExecutor()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope, now = { nowMs }) {}

			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			nowMs = 2_300L
			executor.finish(0, deployFailure())
			runCurrent()

			// The user reads the error and fixes the code; none of that is queueing.
			nowMs = 197_500L
			orchestrator.onFilesChanged(known(srcB))
			runCurrent()

			assertThat(executor.requests).hasSize(2)
			assertThat(executor.requests[1].triggeredAtMillis).isEqualTo(197_500L)
		}

	@Test
	fun `a save that queued behind a failing build keeps its own trigger`() =
		runTest {
			// The other half of the fix: a mid-build save really did wait behind the in-flight
			// build, so dropping ITS stamp too would under-report a queue that was genuine.
			var nowMs = 100L
			val executor = GatedExecutor()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope, now = { nowMs }) {}

			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			nowMs = 200L
			orchestrator.onFilesChanged(known(srcB))
			runCurrent()
			nowMs = 300L
			executor.finish(0, compileError())
			runCurrent()

			assertThat(executor.requests).hasSize(2)
			assertThat(executor.requests[1].triggeredAtMillis).isEqualTo(200L)
		}

	@Test
	fun `a tap after a failed build is stamped at the tap, not at the dead build`() =
		runTest {
			var nowMs = 100L
			val executor = GatedExecutor()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope, now = { nowMs }) {}

			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			nowMs = 400L
			executor.finish(0, deployFailure())
			runCurrent()
			nowMs = 61_400L
			orchestrator.onLiveReloadRequested()
			runCurrent()

			assertThat(executor.requests).hasSize(2)
			assertThat(executor.requests[1].triggeredAtMillis).isEqualTo(61_400L)
		}

	@Test
	fun `a build picked up with no queue clock is stamped at its own start`() =
		runTest {
			// Nothing arrived after the failure, so the returned batch carries no clock at all;
			// the warm-compile request is what happens to start the build. Its t0 is that moment,
			// which reports the wait as the zero it was rather than as a missing measurement.
			var nowMs = 100L
			val executor = GatedExecutor()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope, now = { nowMs }) {}

			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			nowMs = 400L
			executor.finish(0, deployFailure())
			runCurrent()
			nowMs = 5_000L
			orchestrator.onWarmCompileRequested()
			runCurrent()

			assertThat(executor.requests).hasSize(2)
			// The real batch outranks the warm compile, so this is a code build, not a warm one.
			assertThat(executor.requests[1].route).isEqualTo(BuildRoute.CodeOnly)
			assertThat(executor.requests[1].triggeredAtMillis).isEqualTo(5_000L)
		}

	@Test
	fun `a stop tap does not leave its trigger for the next save`() =
		runTest {
			var nowMs = 100L
			val executor = GatedExecutor()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope, now = { nowMs }) {}

			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			nowMs = 300L
			orchestrator.onCancelRequested()
			runCurrent()
			nowMs = 60_300L
			orchestrator.onFilesChanged(known(srcB))
			runCurrent()

			assertThat(executor.requests).hasSize(2)
			assertThat(executor.requests[1].triggeredAtMillis).isEqualTo(60_300L)
		}

	@Test
	fun `a failed proxy app rebuild does not charge its own duration to the next save`() =
		runTest {
			// A rebuild runs for minutes. Its held batch coming back must not come back with a
			// clock that has been running the whole time.
			var nowMs = 100L
			val executor = GatedExecutor()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope, now = { nowMs }) {}

			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			nowMs = 150L
			orchestrator.onProxyAppRebuildStarted()
			runCurrent()
			nowMs = 200_000L
			orchestrator.onProxyAppRebuildFailed()
			runCurrent()
			nowMs = 200_100L
			orchestrator.onFilesChanged(known(srcB))
			runCurrent()

			assertThat(executor.requests).hasSize(2)
			assertThat(executor.requests[1].triggeredAtMillis).isEqualTo(200_100L)
		}

	@Test
	fun `an external build's hand-back does not start a queue clock of its own`() =
		runTest {
			// onBaselineUntrusted starts no build, so a clock started there would run until the
			// user's next save and be charged to it.
			var nowMs = 1_000L
			val executor = GatedExecutor()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope, now = { nowMs }) {}

			orchestrator.onBaselineUntrusted()
			runCurrent()
			nowMs = 91_000L
			orchestrator.onFilesChanged(known(srcB))
			runCurrent()

			assertThat(executor.requests.single().triggeredAtMillis).isEqualTo(91_000L)
		}

	@Test
	fun `save during in-flight build coalesces and never cancels the running compile`() =
		runTest {
			val executor = GatedExecutor()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) {}

			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			orchestrator.onFilesChanged(known(srcB))
			orchestrator.onFilesChanged(known(srcC))
			runCurrent()

			// Still one build in flight; nothing was cancelled.
			assertThat(executor.requests).hasSize(1)
			assertThat(executor.cancellations).isEqualTo(0)

			executor.finish(0, success(generation = 1))
			runCurrent()

			// Both mid-build edits are present in the coalesced follow-up.
			assertThat(executor.requests).hasSize(2)
			assertThat(executor.requests[1].changes).isEqualTo(known(srcB, srcC))
		}

	@Test
	fun `a file modified in one mid-build batch then deleted in the next is only removed in the follow-up`() =
		runTest {
			// Pending accumulates across coalesced batches while a build is in flight. A plain
			// set union would carry srcB as BOTH modified and removed, and the executor would
			// feed it to the daemon compile as changed and removed at once.
			val executor = GatedExecutor()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) {}

			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			orchestrator.onFilesChanged(known(srcB)) // batch 1: srcB modified
			orchestrator.onFilesChanged(ChangedFiles.Known(emptySet(), setOf(File(srcB)))) // batch 2: srcB deleted
			runCurrent()

			executor.finish(0, success(generation = 1))
			runCurrent()

			assertThat(executor.requests).hasSize(2)
			assertThat(executor.requests[1].changes)
				.isEqualTo(ChangedFiles.Known(emptySet(), setOf(File(srcB))))
		}

	@Test
	fun `multi-file batch survives a failed compile - nothing is dropped`() =
		runTest {
			// Clearing changedSrc before the compile would drop every file in the batch
			// the moment that compile fails.
			val executor = GatedExecutor()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) {}

			orchestrator.onFilesChanged(known(srcA, srcB))
			runCurrent()
			executor.finish(0, compileError())
			runCurrent()

			// No new saves arrived mid-build: the orchestrator waits (retrying the identical
			// batch would fail identically). The failed batch is back in pending.
			assertThat(executor.requests).hasSize(1)

			// The user fixes B - the next build carries the WHOLE failed batch, not just B.
			orchestrator.onFilesChanged(known(srcB))
			runCurrent()

			assertThat(executor.requests).hasSize(2)
			assertThat(executor.requests[1].changes).isEqualTo(known(srcA, srcB))
		}

	@Test
	fun `plan 1-4 sequence - failed batch unions with mid-build save, fix rebuilds everything`() =
		runTest {
			val executor = GatedExecutor()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) {}

			// save A, B -> build #1 {A, B}
			orchestrator.onFilesChanged(known(srcA, srcB))
			runCurrent()
			// save C mid-build
			orchestrator.onFilesChanged(known(srcC))
			runCurrent()
			// build #1 FAILS (typo in B)
			executor.finish(0, compileError())
			runCurrent()

			// C arrived mid-build and may contain the fix: rebuild immediately from the
			// accumulated set {A, B, C}. (Deviation from the plan's diagram, which waits
			// for the next save - documented in the ticket status doc, wrapper repo.)
			assertThat(executor.requests).hasSize(2)
			assertThat(executor.requests[1].changes).isEqualTo(known(srcA, srcB, srcC))

			// B is still broken -> build #2 fails; no new mid-build saves -> wait.
			executor.finish(1, compileError())
			runCurrent()
			assertThat(executor.requests).hasSize(2)

			// User fixes B -> build #3 carries the full accumulated set.
			orchestrator.onFilesChanged(known(srcB))
			runCurrent()
			assertThat(executor.requests).hasSize(3)
			assertThat(executor.requests[2].changes).isEqualTo(known(srcA, srcB, srcC))

			executor.finish(2, success(generation = 1))
			runCurrent()
			assertThat(executor.requests).hasSize(3)
		}

	@Test
	fun `no-op save does not trigger a build`() =
		runTest {
			// Conflating an empty changed-set with an unknown one turns a no-op save
			// into a spurious full recompile.
			val executor = GatedExecutor()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) {}

			orchestrator.onFilesChanged(ChangedFiles.Known.EMPTY)
			orchestrator.onFilesChanged(ChangedFiles.Known.EMPTY)
			runCurrent()

			assertThat(executor.requests).isEmpty()
		}

	@Test
	fun `unknown changes force a full recompile on the live reload path`() =
		runTest {
			val executor = GatedExecutor()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) {}

			orchestrator.onFilesChanged(ChangedFiles.Unknown)
			runCurrent()

			assertThat(executor.requests).hasSize(1)
			assertThat(executor.requests[0].changes).isEqualTo(ChangedFiles.Unknown)
			assertThat(executor.requests[0].route).isEqualTo(BuildRoute.CodeAndResources)
		}

	@Test
	fun `rapid save burst coalesces into a single follow-up build`() =
		runTest {
			val executor = GatedExecutor()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) {}

			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			val burst = (1..10).map { "app/src/main/java/com/example/Burst$it.kt" }
			for (path in burst) {
				orchestrator.onFilesChanged(known(path))
			}
			runCurrent()

			// No queue growth: one in flight, everything else coalesced.
			assertThat(executor.requests).hasSize(1)

			executor.finish(0, success(generation = 1))
			runCurrent()

			assertThat(executor.requests).hasSize(2)
			assertThat(executor.requests[1].changes).isEqualTo(known(*burst.toTypedArray()))
		}

	@Test
	fun `manifest change requests invalidation instead of a quick build, exactly once`() =
		runTest {
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			orchestrator.onFilesChanged(known("app/src/main/AndroidManifest.xml"))
			runCurrent()

			assertThat(executor.requests).isEmpty()
			assertThat(events).containsExactly(
				OrchestratorEvent.InvalidationRequired(InvalidationReason.MANIFEST_CHANGED),
			)

			// More saves while invalidated: no duplicate event, still no quick build.
			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			assertThat(executor.requests).isEmpty()
			assertThat(events).hasSize(1)
		}

	@Test
	fun `after a baseline reset the session builds normally again`() =
		runTest {
			val executor = GatedExecutor()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) {}

			orchestrator.onFilesChanged(known("app/src/main/AndroidManifest.xml"))
			runCurrent()
			orchestrator.onProxyAppRebuildStarted()
			orchestrator.onBaselineReset()
			runCurrent()

			// The manifest edit was absorbed by the proxy app rebuild; a fresh code save builds.
			orchestrator.onFilesChanged(known(srcA))
			runCurrent()

			assertThat(executor.requests).hasSize(1)
			assertThat(executor.requests[0].changes).isEqualTo(known(srcA))
		}

	@Test
	fun `save landing mid-rebuild is kept and quick-built right after the reset`() =
		runTest {
			// The Gradle build only absorbs what existed when it
			// STARTED; a save landing while it runs must not be dropped with the batch.
			val executor = GatedExecutor()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) {}

			orchestrator.onFilesChanged(known("app/src/main/AndroidManifest.xml"))
			runCurrent()
			orchestrator.onProxyAppRebuildStarted()
			orchestrator.onFilesChanged(known(srcA)) // mid-rebuild save
			runCurrent()
			assertThat(executor.requests).isEmpty() // still invalidated: no quick build yet

			orchestrator.onBaselineReset()
			runCurrent()

			assertThat(executor.requests).hasSize(1)
			assertThat(executor.requests[0].changes).isEqualTo(known(srcA))
		}

	@Test
	fun `a save echo predating the rebuild start is absorbed, not resurfaced as a spurious invalidation`() =
		runTest {
			// F4: the tap's own build.gradle.kts save echo, debounced past onProxyAppRebuildStarted,
			// stranded in pending and came back from onBaselineReset as a GRADLE_CONFIG_CHANGED
			// invalidation 27ms after the rebaseline had already absorbed that very save.
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val gradleConfig = "app/build.gradle.kts"
			val orchestrator =
				LiveReloadOrchestrator(
					executor,
					ChangeClassifier(),
					backgroundScope,
					wallClock = { 10_000L },
					fileLastModified = { file -> if (file.path == gradleConfig) 9_900L else 0L },
				) { events += it }

			orchestrator.onFilesChanged(known(gradleConfig))
			runCurrent()
			assertThat(events.filterIsInstance<OrchestratorEvent.InvalidationRequired>()).hasSize(1)

			orchestrator.onProxyAppRebuildStarted()
			// The echo of the very save the rebuild is absorbing: on disk (mtime 9900) before
			// the rebuild started (10000), so Gradle read it with the rest of the tree.
			orchestrator.onFilesChanged(known(gradleConfig))
			orchestrator.onBaselineReset()
			runCurrent()

			assertThat(events.filterIsInstance<OrchestratorEvent.InvalidationRequired>()).hasSize(1)
			assertThat(executor.requests).isEmpty()
		}

	@Test
	fun `a mid-rebuild batch is split by mtime - the echo absorbed, the newer edit kept`() =
		runTest {
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val gradleConfig = "app/build.gradle.kts"
			val mtimes = mapOf(gradleConfig to 9_900L, srcA to 10_500L)
			val orchestrator =
				LiveReloadOrchestrator(
					executor,
					ChangeClassifier(),
					backgroundScope,
					wallClock = { 10_000L },
					fileLastModified = { file -> mtimes[file.path] ?: 0L },
				) { events += it }

			orchestrator.onFilesChanged(known("app/src/main/AndroidManifest.xml"))
			runCurrent()
			orchestrator.onProxyAppRebuildStarted()
			// One batch: the config save's echo plus a real edit made while Gradle runs.
			orchestrator.onFilesChanged(known(gradleConfig, srcA))
			orchestrator.onBaselineReset()
			runCurrent()

			// Only the newer edit survives to a quick build; the echo went with the rebuild.
			assertThat(executor.requests).hasSize(1)
			assertThat(executor.requests[0].changes).isEqualTo(known(srcA))
			assertThat(events.filterIsInstance<OrchestratorEvent.InvalidationRequired>()).hasSize(1)
		}

	@Test
	fun `a fully absorbed echo does not stamp the queue clock`() =
		runTest {
			// A batch the rebuild absorbed queued nothing, so the next real save's t0 must be
			// its own arrival - not the echo's, which would charge it the whole rebuild gap.
			var nowMs = 1_000L
			val executor = GatedExecutor()
			val gradleConfig = "app/build.gradle.kts"
			val orchestrator =
				LiveReloadOrchestrator(
					executor,
					ChangeClassifier(),
					backgroundScope,
					now = { nowMs },
					wallClock = { 10_000L },
					fileLastModified = { file -> if (file.path == gradleConfig) 9_900L else 0L },
				) {}

			orchestrator.onFilesChanged(known(gradleConfig))
			runCurrent()
			orchestrator.onProxyAppRebuildStarted()
			nowMs = 2_000L
			orchestrator.onFilesChanged(known(gradleConfig)) // echo, fully absorbed
			orchestrator.onBaselineReset()
			runCurrent()
			assertThat(executor.requests).isEmpty()

			nowMs = 60_000L
			orchestrator.onFilesChanged(known(srcA))
			runCurrent()

			assertThat(executor.requests.single().triggeredAtMillis).isEqualTo(60_000L)
		}

	@Test
	fun `a failed rebuild restores absorbed echoes to pending along with the held set`() =
		runTest {
			// Nothing was absorbed after all - the echo folded into awaitingAbsorption must come
			// back with the rest, or a failed rebuild silently loses the echoed save.
			val executor = GatedExecutor()
			val mtimes = mapOf(srcB to 9_900L, srcC to 10_500L)
			val orchestrator =
				LiveReloadOrchestrator(
					executor,
					ChangeClassifier(),
					backgroundScope,
					wallClock = { 10_000L },
					fileLastModified = { file -> mtimes[file.path] ?: 0L },
				) {}

			orchestrator.onFilesChanged(known(srcA)) // starts build #1
			runCurrent()
			orchestrator.onProxyAppRebuildStarted() // absorbs the in-flight batch {srcA}
			// srcB (echo, absorbed) and srcC (newer, stays pending) in one mid-rebuild batch.
			orchestrator.onFilesChanged(known(srcB, srcC))
			orchestrator.onProxyAppRebuildFailed()
			runCurrent()
			assertThat(executor.requests).hasSize(1) // the failure starts nothing on its own

			orchestrator.onFilesChanged(known(srcA))
			runCurrent()

			assertThat(executor.requests).hasSize(2)
			assertThat(executor.requests[1].changes).isEqualTo(known(srcA, srcB, srcC))
		}

	@Test
	fun `a save echo stamped at exactly the rebuild's start millisecond is absorbed - the boundary is inclusive`() =
		runTest {
			// The F4 case verbatim: coarse mtimes regularly stamp the tap's save echo with the
			// same millisecond the rebuild started on. An exclusive upper bound (`until`
			// semantics) would strand it in pending and re-open F4 as a spurious invalidation.
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val gradleConfig = "app/build.gradle.kts"
			val orchestrator =
				LiveReloadOrchestrator(
					executor,
					ChangeClassifier(),
					backgroundScope,
					wallClock = { 10_000L },
					fileLastModified = { 10_000L },
				) { events += it }

			orchestrator.onFilesChanged(known(gradleConfig))
			runCurrent()
			assertThat(events.filterIsInstance<OrchestratorEvent.InvalidationRequired>()).hasSize(1)

			orchestrator.onProxyAppRebuildStarted()
			orchestrator.onFilesChanged(known(gradleConfig))
			orchestrator.onBaselineReset()
			runCurrent()

			assertThat(events.filterIsInstance<OrchestratorEvent.InvalidationRequired>()).hasSize(1)
			assertThat(executor.requests).isEmpty()
		}

	@Test
	fun `a mid-rebuild file with no readable mtime stays pending - nothing proves it predates the read`() =
		runTest {
			// 0 means missing or unreadable, not ancient: absorbing it would drop a real
			// mid-rebuild edit whose mtime simply could not be read.
			val executor = GatedExecutor()
			val orchestrator =
				LiveReloadOrchestrator(
					executor,
					ChangeClassifier(),
					backgroundScope,
					wallClock = { 10_000L },
					fileLastModified = { 0L },
				) {}

			orchestrator.onFilesChanged(known("app/src/main/AndroidManifest.xml"))
			runCurrent()
			orchestrator.onProxyAppRebuildStarted()
			orchestrator.onFilesChanged(known(srcA))
			orchestrator.onBaselineReset()
			runCurrent()

			assertThat(executor.requests).hasSize(1)
			assertThat(executor.requests[0].changes).isEqualTo(known(srcA))
		}

	@Test
	fun `a mid-rebuild removal stays pending - no mtime is left to date it`() =
		runTest {
			val executor = GatedExecutor()
			val removal = ChangedFiles.Known(emptySet(), setOf(File(srcB)))
			val orchestrator =
				LiveReloadOrchestrator(
					executor,
					ChangeClassifier(),
					backgroundScope,
					wallClock = { 10_000L },
					// Every path reads as pre-start, so a split that dated removals by mtime
					// WOULD absorb this one; a deleted file must not be dated at all.
					fileLastModified = { 9_900L },
				) {}

			orchestrator.onFilesChanged(known("app/src/main/AndroidManifest.xml"))
			runCurrent()
			orchestrator.onProxyAppRebuildStarted()
			orchestrator.onFilesChanged(removal)
			orchestrator.onBaselineReset()
			runCurrent()

			// The deletion still needs its own build once the fresh baseline lands.
			assertThat(executor.requests).hasSize(1)
			assertThat(executor.requests[0].changes).isEqualTo(removal)
		}

	@Test
	fun `a mid-rebuild Unknown batch passes through the echo split un-absorbed`() =
		runTest {
			// Unknown enumerates nothing, so nothing can prove any of it predates the rebuild's
			// read; absorbing it wholesale would swallow "recompile everything from current
			// disk" into a rebuild that only read what existed at its start.
			val executor = GatedExecutor()
			val orchestrator =
				LiveReloadOrchestrator(
					executor,
					ChangeClassifier(),
					backgroundScope,
					wallClock = { 10_000L },
					fileLastModified = { 9_900L },
				) {}

			orchestrator.onFilesChanged(known("app/src/main/AndroidManifest.xml"))
			runCurrent()
			orchestrator.onProxyAppRebuildStarted()
			orchestrator.onFilesChanged(ChangedFiles.Unknown)
			orchestrator.onBaselineReset()
			runCurrent()

			assertThat(executor.requests).hasSize(1)
			assertThat(executor.requests[0].changes).isEqualTo(ChangedFiles.Unknown)
		}

	@Test
	fun `failed proxy app rebuild returns the held batch to pending and re-reports on next save`() =
		runTest {
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			orchestrator.onFilesChanged(known("app/src/main/AndroidManifest.xml"))
			runCurrent()
			assertThat(events.filterIsInstance<OrchestratorEvent.InvalidationRequired>()).hasSize(1)

			orchestrator.onProxyAppRebuildStarted()
			orchestrator.onProxyAppRebuildFailed()
			runCurrent()
			// Nothing was absorbed; no event yet (re-reporting here would loop the fallback).
			assertThat(events.filterIsInstance<OrchestratorEvent.InvalidationRequired>()).hasSize(1)

			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			// Manifest is still pending -> invalidation is re-reported, no quick build runs.
			assertThat(events.filterIsInstance<OrchestratorEvent.InvalidationRequired>()).hasSize(2)
			assertThat(executor.requests).isEmpty()
		}

	@Test
	fun `baseline reset without started falls back to dropping pending`() =
		runTest {
			// Protocol-violation compatibility path: reset with no started call drops all.
			val executor = GatedExecutor()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) {}

			orchestrator.onFilesChanged(known("app/src/main/AndroidManifest.xml"))
			runCurrent()
			orchestrator.onBaselineReset()
			runCurrent()

			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			assertThat(executor.requests).hasSize(1)
			assertThat(executor.requests[0].changes).isEqualTo(known(srcA))
		}

	@Test
	fun `result of a superseded build is discarded, never rendered`() =
		runTest {
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			events.clear()

			// A full Gradle proxy app rebuild reset the session's baseline while build #1 was in flight.
			orchestrator.onProxyAppRebuildStarted()
			orchestrator.onBaselineReset()
			executor.finish(0, success(generation = 7))
			runCurrent()

			// The late result must produce no events - its diagnostics/success are stale.
			assertThat(events).isEmpty()
		}

	// Dropping the in-flight REFERENCE is not enough: an orphaned coroutine runs on and deploys
	// a payload compiled against the pre-rebuild baseline into an app Gradle is reinstalling.
	@Test
	fun `a proxy app rebuild cancels the build it supersedes instead of orphaning its deploy`() =
		runTest {
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			assertThat(executor.requests).hasSize(1)
			events.clear()

			orchestrator.onProxyAppRebuildStarted()
			runCurrent()

			// The build coroutine is dead, not merely unreferenced.
			assertThat(executor.cancellations).isEqualTo(1)

			// And it stays dead: the compile finishing cannot push the stale payload out.
			executor.finish(0, success(generation = 7))
			runCurrent()
			assertThat(executor.deploys).isEqualTo(0)
			assertThat(events).isEmpty()
		}

	@Test
	fun `a baseline reset with no rebuild started cancels the build it orphans`() =
		runTest {
			// Same defect on the protocol-violation fallback: it drops the pending set, so it must
			// not leave a build running against a baseline that just moved under it.
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			assertThat(executor.requests).hasSize(1)
			events.clear()

			orchestrator.onBaselineReset()
			runCurrent()

			assertThat(executor.cancellations).isEqualTo(1)

			executor.finish(0, success(generation = 7))
			runCurrent()
			assertThat(executor.deploys).isEqualTo(0)
			assertThat(events).isEmpty()
		}

	// pendingUserInitiated must not latch across a rebaseline: the tap it records is answered by
	// the Gradle build that absorbs its changes, and a surviving flag would report the next
	// unrelated automatic save as the user's own ask, pulling them out of the editor into the
	// proxy app.
	@Test
	fun `a tap absorbed by a proxy app rebuild does not tag the next automatic save as the user's ask`() =
		runTest {
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			// A manifest edit parks the session on an invalidation, so the tap lands with real work
			// pending and no build to consume it - which is what arms the flag.
			orchestrator.onFilesChanged(known("app/src/main/AndroidManifest.xml"))
			runCurrent()
			assertThat(orchestrator.onLiveReloadRequested(userInitiated = true))
				.isEqualTo(LiveReloadRequestOutcome.AWAITS_DEPLOY)
			runCurrent()
			assertThat(executor.requests).isEmpty()

			// Gradle absorbs the manifest edit; that build is the answer to the tap.
			orchestrator.onProxyAppRebuildStarted()
			orchestrator.onBaselineReset()
			runCurrent()
			assertThat(executor.requests).isEmpty()

			// A plain autosave, much later. The user asked for nothing here.
			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			executor.finish(0, success(generation = 1))
			runCurrent()

			assertThat(executor.requests.single().userInitiated).isFalse()
			val succeeded = events.filterIsInstance<OrchestratorEvent.BuildSucceeded>().single()
			assertThat(succeeded.userInitiated).isFalse()
		}

	@Test
	fun `a tap dropped by the no-rebuild-started fallback does not tag the next save either`() =
		runTest {
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			orchestrator.onFilesChanged(known("app/src/main/AndroidManifest.xml"))
			runCurrent()
			assertThat(orchestrator.onLiveReloadRequested(userInitiated = true))
				.isEqualTo(LiveReloadRequestOutcome.AWAITS_DEPLOY)
			// Drops the pending set, and with it the tap that asked about it.
			orchestrator.onBaselineReset()
			runCurrent()
			assertThat(executor.requests).isEmpty()

			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			executor.finish(0, success(generation = 1))
			runCurrent()

			assertThat(executor.requests.single().userInitiated).isFalse()
			val succeeded = events.filterIsInstance<OrchestratorEvent.BuildSucceeded>().single()
			assertThat(succeeded.userInitiated).isFalse()
		}

	@Test
	fun `the reconnect catch-up with nothing changed still executes a forced redeploy build`() =
		runTest {
			val executor = GatedExecutor()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) {}

			orchestrator.onLiveReloadRequested(userInitiated = false)
			runCurrent()

			assertThat(executor.requests).hasSize(1)
			assertThat(executor.requests[0].forced).isTrue()
			assertThat(executor.requests[0].route).isEqualTo(BuildRoute.NoOp)
			assertThat(executor.requests[0].changes.isEmpty).isTrue()
		}

	@Test
	fun `a reconnect catch-up during an in-flight build runs a forced follow-up after success`() =
		runTest {
			val executor = GatedExecutor()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) {}

			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			orchestrator.onLiveReloadRequested(userInitiated = false)
			runCurrent()
			assertThat(executor.requests).hasSize(1)

			executor.finish(0, success(generation = 1))
			runCurrent()

			assertThat(executor.requests).hasSize(2)
			assertThat(executor.requests[1].forced).isTrue()
		}

	@Test
	fun `a failed forced catch-up build retries forced`() =
		runTest {
			// The forced flag is re-armed by a failure - the app is still behind, so the retry
			// must still redeploy even if the retrying save's own route would not.
			val executor = GatedExecutor()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) {}

			orchestrator.onLiveReloadRequested(userInitiated = false)
			runCurrent()
			executor.finish(0, deployFailure())
			runCurrent()

			orchestrator.onFilesChanged(known(srcA))
			runCurrent()

			assertThat(executor.requests).hasSize(2)
			assertThat(executor.requests[1].forced).isTrue()
		}

	@Test
	fun `an executor that throws is treated as an infrastructure failure and the batch survives`() =
		runTest {
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			executor.throwOnNext = IllegalStateException("daemon socket closed")
			orchestrator.onFilesChanged(known(srcA, srcB))
			runCurrent()

			val failure = events.filterIsInstance<OrchestratorEvent.BuildFailed>().single()
			assertThat(failure.outcome).isInstanceOf(BuildOutcome.InfrastructureFailure::class.java)

			// The batch is preserved: the next save rebuilds everything.
			orchestrator.onFilesChanged(known(srcC))
			runCurrent()
			assertThat(executor.requests).hasSize(2)
			assertThat(executor.requests[1].changes).isEqualTo(known(srcA, srcB, srcC))
		}

	@Test
	fun `crash recovery - priming with unknown yields one slow-but-correct first build`() =
		runTest {
			// After a CoGo restart the watcher history is gone; the session manager primes
			// the fresh orchestrator with Unknown. First build is full, nothing is lost.
			val executor = GatedExecutor()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) {}

			orchestrator.onFilesChanged(ChangedFiles.Unknown)
			runCurrent()

			assertThat(executor.requests).hasSize(1)
			assertThat(executor.requests[0].route).isEqualTo(BuildRoute.CodeAndResources)

			executor.finish(0, success(generation = 42))
			runCurrent()

			// Back to normal incremental behavior afterwards.
			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			assertThat(executor.requests).hasSize(2)
			assertThat(executor.requests[1].changes).isEqualTo(known(srcA))
		}

	@Test
	fun `success and failure events carry the outcome for the status surface`() =
		runTest {
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			executor.finish(0, success(generation = 3))
			runCurrent()

			orchestrator.onFilesChanged(known(srcB))
			runCurrent()
			executor.finish(1, compileError())
			runCurrent()

			val succeeded = events.filterIsInstance<OrchestratorEvent.BuildSucceeded>().single()
			assertThat(succeeded.result.generation).isEqualTo(3)

			val failed = events.filterIsInstance<OrchestratorEvent.BuildFailed>().single()
			val error = failed.outcome as BuildOutcome.CompileError
			assertThat(error.diagnostics.single().file).isEqualTo("B.kt")
			assertThat(error.diagnostics.single().line).isEqualTo(7)
		}

	@Test
	fun `onBaselineUntrusted marks the baseline dirty without starting a build`() =
		runTest {
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			orchestrator.onBaselineUntrusted()
			runCurrent()

			// Deferred refresh: no build, no events, until the next save or tap.
			assertThat(executor.requests).isEmpty()
			assertThat(events).isEmpty()

			orchestrator.onFilesChanged(known(srcA))
			runCurrent()

			// The next build recompiles everything from current disk.
			assertThat(executor.requests.single().changes).isEqualTo(ChangedFiles.Unknown)
			assertThat(executor.requests.single().route).isEqualTo(BuildRoute.CodeAndResources)
		}

	@Test
	fun `onBaselineUntrusted during an in-flight build coalesces the refresh into the follow-up`() =
		runTest {
			val executor = GatedExecutor()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) {}

			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			orchestrator.onBaselineUntrusted()
			runCurrent()

			// The running compile is never cancelled; the mark waits.
			assertThat(executor.requests).hasSize(1)
			assertThat(executor.cancellations).isEqualTo(0)

			executor.finish(0, success(generation = 1))
			runCurrent()

			assertThat(executor.requests).hasSize(2)
			assertThat(executor.requests[1].changes).isEqualTo(ChangedFiles.Unknown)
		}

	@Test
	fun `warm-compile request with nothing pending starts a warm-compile build compiling everything`() =
		runTest {
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			orchestrator.onWarmCompileRequested()
			runCurrent()

			assertThat(executor.requests).hasSize(1)
			assertThat(executor.requests[0].route).isEqualTo(BuildRoute.WarmCompile)
			assertThat(executor.requests[0].changes).isEqualTo(ChangedFiles.Unknown)
			assertThat(executor.requests[0].forced).isFalse()
			assertThat(events).containsExactly(
				// Unknown, not Known.EMPTY - a warm compile covers every source, so metrics must
				// not read this as "0 files changed".
				OrchestratorEvent.BuildStarted(1L, BuildRoute.WarmCompile, ChangedFiles.Unknown),
			)

			executor.finish(0, success(generation = 0))
			runCurrent()
			val succeeded = events.filterIsInstance<OrchestratorEvent.BuildSucceeded>().single()
			assertThat(succeeded.route).isEqualTo(BuildRoute.WarmCompile)
		}

	@Test
	fun `a save that lands before the warm compile starts drops it - the real build warms implicitly`() =
		runTest {
			val executor = GatedExecutor()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) {}

			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			// The save's build is in flight; the warm-compile request arrives late.
			orchestrator.onWarmCompileRequested()
			executor.finish(0, success(generation = 1))
			runCurrent()

			// No second build: the save's build already compiled the full source set
			// (daemon first-build contract), so the warm compile would be pure waste.
			assertThat(executor.requests).hasSize(1)
			assertThat(executor.requests[0].route).isEqualTo(BuildRoute.CodeOnly)
		}

	@Test
	fun `a save landing mid-warm-compile queues and builds right after it finishes`() =
		runTest {
			val executor = GatedExecutor()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) {}

			orchestrator.onWarmCompileRequested()
			runCurrent()
			orchestrator.onFilesChanged(known(srcA))
			runCurrent()

			// Single-flight: the save waits for the warm compile, never overlaps it.
			assertThat(executor.requests).hasSize(1)
			assertThat(executor.cancellations).isEqualTo(0)

			executor.finish(0, success(generation = 0))
			runCurrent()

			assertThat(executor.requests).hasSize(2)
			assertThat(executor.requests[1].route).isEqualTo(BuildRoute.CodeOnly)
			assertThat(executor.requests[1].changes).isEqualTo(known(srcA))
		}

	@Test
	fun `daemon replacement with nothing pending re-warms via a deploy-nothing warm compile`() =
		runTest {
			val executor = GatedExecutor()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) {}

			orchestrator.onDaemonReplaced()
			runCurrent()

			assertThat(executor.requests).hasSize(1)
			assertThat(executor.requests[0].route).isEqualTo(BuildRoute.WarmCompile)
			assertThat(executor.requests[0].changes).isEqualTo(ChangedFiles.Unknown)
		}

	@Test
	fun `daemon replacement with pending saves marks the baseline dirty and deploys`() =
		runTest {
			val executor = GatedExecutor()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) {}

			// Save lands while the daemon is dead (watcher outlives it), then the respawn.
			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			executor.finish(0, compileError()) // dead daemon's build failed; batch unioned back
			runCurrent()
			orchestrator.onDaemonReplaced()
			runCurrent()

			// A REAL deploying build over everything, not a warm compile.
			val replay = executor.requests.last()
			assertThat(replay.route).isEqualTo(BuildRoute.CodeAndResources)
			assertThat(replay.changes).isEqualTo(ChangedFiles.Unknown)
		}

	@Test
	fun `daemon replacement mid-build unions Unknown into pending - the build's own failure, not a supersession, starts the follow-up`() =
		runTest {
			val executor = GatedExecutor()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) {}

			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			// Daemon died mid-build; respawn lands BEFORE the failure result does. The
			// in-flight build is NOT superseded here (its buildId stays inFlight) - it
			// still owns its own failure/follow-up below; onDaemonReplaced only marks the
			// pending batch Unknown for whatever build eventually follows.
			orchestrator.onDaemonReplaced()
			runCurrent()
			assertThat(executor.requests).hasSize(1)

			executor.finish(0, BuildOutcome.InfrastructureFailure("daemon died", daemonDied = true))
			runCurrent()

			// The follow-up carries the batch + the Unknown mark - full recompile, deploys.
			assertThat(executor.requests).hasSize(2)
			assertThat(executor.requests[1].changes).isEqualTo(ChangedFiles.Unknown)
			assertThat(executor.requests[1].route).isEqualTo(BuildRoute.CodeAndResources)
		}

	@Test
	fun `a failed warm compile leaves nothing pending and does not auto-retry`() =
		runTest {
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			orchestrator.onWarmCompileRequested()
			runCurrent()
			executor.finish(0, compileError())
			runCurrent()

			// No retry loop for a background warm-up...
			assertThat(executor.requests).hasSize(1)
			val failed = events.filterIsInstance<OrchestratorEvent.BuildFailed>().single()
			assertThat(failed.route).isEqualTo(BuildRoute.WarmCompile)

			// ...and the next real save builds exactly its own batch (nothing leaked in).
			orchestrator.onFilesChanged(known(srcB))
			runCurrent()
			assertThat(executor.requests).hasSize(2)
			assertThat(executor.requests[1].changes).isEqualTo(known(srcB))
		}

	@Test
	fun `a warm compile's failure does not prime relinkStuck for the first real failure the user sees`() =
		runTest {
			// A warm-compile failure is invisible to the user (the session manager never surfaces
			// it), so it must not count as the first of the repeat pair that flags a stuck
			// relink - the user would then be told a single resource typo is blocking every build.
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			orchestrator.onWarmCompileRequested()
			runCurrent()
			// A real save lands WHILE the warm compile runs - its build starts automatically
			// as this build's auto-follow-up once the warm compile's own result lands.
			orchestrator.onFilesChanged(known(resLayout))
			runCurrent()
			executor.finish(0, resourceError()) // the warm compile's (invisible) failure
			runCurrent()
			assertThat(executor.requests).hasSize(2)
			executor.finish(1, resourceError()) // identical diagnostics to the warm compile's failure
			runCurrent()

			val realFailure = events.filterIsInstance<OrchestratorEvent.BuildFailed>().single { it.route != BuildRoute.WarmCompile }
			assertThat(realFailure.relinkStuck).isFalse()
		}

	// Review gap (2026-07-26 #69): a proxy app rebuild landing mid-warm-compile supersedes it - the
	// warm compile's late result must be discarded, and it must NOT re-queue after the reset
	// (the proxy app rebuild's own Gradle build just recompiled the world).
	@Test
	fun `a warm compile superseded by a proxy app rebuild is discarded and does not restart after the reset`() =
		runTest {
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			orchestrator.onWarmCompileRequested()
			runCurrent()
			assertThat(executor.requests.single().route).isEqualTo(BuildRoute.WarmCompile)

			// A gradle/manifest edit forced a proxy app rebuild while the warm compile runs.
			orchestrator.onProxyAppRebuildStarted()
			events.clear()
			executor.finish(0, success(generation = 0))
			runCurrent()
			// The superseded warm compile's result is discarded: no Succeeded/Failed escapes
			// (a WarmCompileFinished here would flip the session out of its proxy-app-rebuild flow).
			assertThat(events).isEmpty()

			orchestrator.onBaselineReset()
			runCurrent()
			// Nothing pending, and the dead warm compile was not resurrected.
			assertThat(executor.requests).hasSize(1)
			assertThat(events).isEmpty()

			// The session then builds normally again, with exactly the new batch.
			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			assertThat(executor.requests).hasSize(2)
			assertThat(executor.requests[1].changes).isEqualTo(known(srcA))
			assertThat(executor.requests[1].route).isEqualTo(BuildRoute.CodeOnly)
		}

	// Bryan's button spec: the trigger SOURCE has to survive all the way to the deploy, and a
	// stop has to abandon a build without losing its edits.

	@Test
	fun `a tap with pending work reports that its answer is the deploy, and tags that build`() =
		runTest {
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			// A save landed but its build has not started yet (mid-rebuild absorption is the
			// real-world shape); the tap coalesces into it and must wait for the deploy.
			orchestrator.onProxyAppRebuildStarted()
			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			assertThat(executor.requests).isEmpty()

			val outcome = orchestrator.onLiveReloadRequested(userInitiated = true)
			orchestrator.onBaselineReset()
			runCurrent()
			executor.finish(0, success(generation = 2))
			runCurrent()

			assertThat(outcome).isEqualTo(LiveReloadRequestOutcome.AWAITS_DEPLOY)
			val succeeded = events.filterIsInstance<OrchestratorEvent.BuildSucceeded>().single()
			assertThat(succeeded.userInitiated).isTrue()
		}

	@Test
	fun `a clean tap with nothing pending builds nothing and tells the caller to switch`() =
		runTest {
			// The F7 root fix's do-nothing half: the deployed app is current, so answering the
			// tap costs no build at all - where the old forced NoOp recompiled a whole module
			// to redeploy identical bytes.
			val executor = GatedExecutor()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) {}

			val outcome = orchestrator.onLiveReloadRequested(userInitiated = true, expectChanges = false)
			runCurrent()

			assertThat(outcome).isEqualTo(LiveReloadRequestOutcome.SWITCH_NOW)
			assertThat(executor.requests).isEmpty()

			// And nothing lingers: the next save's build is a plain routed one, not forced and
			// not the user's ask.
			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			assertThat(executor.requests.single().forced).isFalse()
			assertThat(executor.requests.single().userInitiated).isFalse()
		}

	@Test
	fun `a tap that wrote something arms on the incoming batch instead of forcing a build`() =
		runTest {
			// The F7 root fix's other half: the tap's save-all wrote files whose batch is still
			// inside the coalescer window. The batch, not the tap, drives the one build - so it
			// is routed off the real changed-set instead of a forced blind NoOp.
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			val outcome = orchestrator.onLiveReloadRequested(userInitiated = true, expectChanges = true)
			runCurrent()
			assertThat(outcome).isEqualTo(LiveReloadRequestOutcome.AWAITS_CHANGES)
			assertThat(executor.requests).isEmpty()

			// The save-all's batch lands; its build carries the tap's ask.
			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			val request = executor.requests.single()
			assertThat(request.route).isEqualTo(BuildRoute.CodeOnly)
			assertThat(request.forced).isFalse()
			assertThat(request.userInitiated).isTrue()

			executor.finish(0, success(generation = 1))
			runCurrent()
			val succeeded = events.filterIsInstance<OrchestratorEvent.BuildSucceeded>().single()
			assertThat(succeeded.userInitiated).isTrue()

			// The batch already answered the tap, so the deadline fallback must find nothing.
			assertThat(orchestrator.consumeUnansweredTap()).isFalse()
		}

	@Test
	fun `an armed tap whose batch never comes is consumed by the deadline exactly once`() =
		runTest {
			// The .md-save edge: every written file was watcher-irrelevant, so no batch ever
			// arrives and the deadline is the only thing left to answer the tap.
			val executor = GatedExecutor()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) {}

			orchestrator.onLiveReloadRequested(userInitiated = true, expectChanges = true)
			runCurrent()

			assertThat(orchestrator.consumeUnansweredTap()).isTrue()
			// Exactly once: a second fallback (two taps racing) must not switch again.
			assertThat(orchestrator.consumeUnansweredTap()).isFalse()

			// The expired tap leaves nothing behind: a later save's build is not the user's ask.
			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			assertThat(executor.requests.single().forced).isFalse()
			assertThat(executor.requests.single().userInitiated).isFalse()
		}

	@Test
	fun `a build a save triggered is never tagged as user-initiated`() =
		runTest {
			// Behaviour 3, at the source: nothing about a watcher batch may set the flag that
			// pulls the user out of the editor.
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			executor.finish(0, success(generation = 1))
			runCurrent()

			val succeeded = events.filterIsInstance<OrchestratorEvent.BuildSucceeded>().single()
			assertThat(succeeded.userInitiated).isFalse()
		}

	@Test
	fun `a non-user request must not tag its build, even though it is forced`() =
		runTest {
			// The reconnect catch-up is forced exactly like a tap, which is why "forced" is not
			// a usable stand-in for "the user asked".
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			orchestrator.onFilesChanged(known(srcA))
			orchestrator.onLiveReloadRequested(userInitiated = false)
			runCurrent()
			executor.finish(0, success(generation = 1))
			runCurrent()

			val succeeded = events.filterIsInstance<OrchestratorEvent.BuildSucceeded>().single()
			assertThat(succeeded.result.generation).isEqualTo(1)
			assertThat(succeeded.userInitiated).isFalse()
		}

	@Test
	fun `a failed user-initiated build does not re-tag the save that retries it`() =
		runTest {
			// The tap was already answered - with the compile error. The save that fixes the
			// code is not a new ask, so it must not yank the user out of the editor.
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			// Hold the batch so the tap lands BEFORE the build starts and really tags it.
			orchestrator.onProxyAppRebuildStarted()
			orchestrator.onFilesChanged(known(srcA))
			orchestrator.onLiveReloadRequested(userInitiated = true)
			orchestrator.onBaselineReset()
			runCurrent()
			assertThat(executor.requests).hasSize(1)

			// A save lands mid-build so the failure triggers an immediate follow-up.
			orchestrator.onFilesChanged(known(srcB))
			runCurrent()
			executor.finish(0, compileError())
			runCurrent()
			assertThat(executor.requests).hasSize(2)
			executor.finish(1, success(generation = 1))
			runCurrent()

			val succeeded = events.filterIsInstance<OrchestratorEvent.BuildSucceeded>().single()
			assertThat(succeeded.userInitiated).isFalse()
			// A tap no longer forces anything, so there is no forced flag to survive either;
			// forced-survives-failure is pinned on the reconnect path, the one caller left
			// that sets it (see `a failed forced catch-up build retries forced`).
			assertThat(executor.requests[1].forced).isFalse()
		}

	@Test
	fun `marking an in-flight build carries the ask without starting a second build`() =
		runTest {
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			orchestrator.onFilesChanged(known(srcA))
			runCurrent()

			assertThat(orchestrator.markInFlightUserInitiated()).isTrue()
			executor.finish(0, success(generation = 1))
			runCurrent()

			assertThat(executor.requests).hasSize(1)
			val succeeded = events.filterIsInstance<OrchestratorEvent.BuildSucceeded>().single()
			assertThat(succeeded.userInitiated).isTrue()
			// The request left before the tap arrived, so unless the executor is told
			// separately this build's deploy still refuses to open a closed app - and the tap
			// silently does nothing.
			assertThat(executor.promotions).isEqualTo(1)
		}

	@Test
	fun `a save's build is not user-initiated, so its deploy may not take the screen`() =
		runTest {
			val executor = GatedExecutor()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) {}

			orchestrator.onFilesChanged(known(srcA))
			runCurrent()

			assertThat(executor.requests.single().userInitiated).isFalse()
			assertThat(executor.promotions).isEqualTo(0)
		}

	@Test
	fun `a tap's build is user-initiated, so its deploy may open the app`() =
		runTest {
			val executor = GatedExecutor()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) {}

			// A tap only arms the flag when there is real work to wait for; a tap with nothing
			// pending is answered by the caller itself. So park a build in flight, save again
			// so the next batch is pending, then tap.
			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			orchestrator.onFilesChanged(known(srcB))
			orchestrator.onLiveReloadRequested(userInitiated = true)
			runCurrent()

			executor.finish(0, success(generation = 1))
			runCurrent()

			assertThat(executor.requests).hasSize(2)
			assertThat(executor.requests[1].userInitiated).isTrue()
			// The tap arms the NEXT request only: the build already in flight left before the
			// tap and stays untagged, or its deploy would take the screen for work nobody asked
			// about. No promotion either - that is markInFlightUserInitiated's job, not a tap's.
			assertThat(executor.requests[0].userInitiated).isFalse()
			assertThat(executor.promotions).isEqualTo(0)
		}

	@Test
	fun `a reconnect catch-up is never user-initiated, so a stale reconnect cannot steal the screen`() =
		runTest {
			val executor = GatedExecutor()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) {}

			orchestrator.onFilesChanged(known(srcA))
			orchestrator.onLiveReloadRequested(userInitiated = false)
			runCurrent()

			assertThat(executor.requests.single().userInitiated).isFalse()
		}

	@Test
	fun `marking refuses when there is no build to carry the ask`() =
		runTest {
			// Nothing in flight, and a warm compile in flight, both have to say no: a warm compile deploys
			// nothing, so it can never be a tap's answer. The caller then falls back to a real
			// request instead of dropping the tap.
			val executor = GatedExecutor()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) {}

			assertThat(orchestrator.markInFlightUserInitiated()).isFalse()

			orchestrator.onWarmCompileRequested()
			runCurrent()
			assertThat(executor.requests.single().route).isEqualTo(BuildRoute.WarmCompile)
			assertThat(orchestrator.markInFlightUserInitiated()).isFalse()
			// Refusing has to be total: a promotion that escaped ahead of the guard would tag
			// the warm compile - or whatever starts next - with an ask it cannot answer.
			assertThat(executor.promotions).isEqualTo(0)
		}

	@Test
	fun `a cancelled build reports nothing and returns its batch to pending`() =
		runTest {
			// Behaviour 5, and the never-lose-pending invariant it must not break: the stopped
			// edit is still owed a build, so the next save carries it too.
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			orchestrator.onFilesChanged(known(srcA))
			runCurrent()

			assertThat(orchestrator.onCancelRequested()).isTrue()
			runCurrent()

			// The abandoned build produced no outcome event at all: not a success, and not a
			// failure either - a cancellation is neither.
			assertThat(events.filterIsInstance<OrchestratorEvent.BuildSucceeded>()).isEmpty()
			assertThat(events.filterIsInstance<OrchestratorEvent.BuildFailed>()).isEmpty()
			assertThat(executor.cancellations).isEqualTo(1)

			orchestrator.onFilesChanged(known(srcB))
			runCurrent()
			assertThat(executor.requests).hasSize(2)
			assertThat(executor.requests[1].changes).isEqualTo(known(srcA, srcB))
		}

	@Test
	fun `a cancelled tap is withdrawn - the rebuild is not forced`() =
		runTest {
			// The user asked, then unasked. A forced flag surviving the cancel would make the
			// next save redeploy at a fresh generation as if the tap still stood.
			val executor = GatedExecutor()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) {}

			orchestrator.onFilesChanged(known(srcA))
			orchestrator.onLiveReloadRequested(userInitiated = true)
			runCurrent()
			orchestrator.onCancelRequested()
			runCurrent()

			orchestrator.onFilesChanged(known(srcB))
			runCurrent()
			assertThat(executor.requests).hasSize(2)
			assertThat(executor.requests[1].forced).isFalse()
		}

	@Test
	fun `cancelling refuses when nothing is running, and never touches the warm compile`() =
		runTest {
			val executor = GatedExecutor()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) {}

			assertThat(orchestrator.onCancelRequested()).isFalse()

			orchestrator.onWarmCompileRequested()
			runCurrent()
			assertThat(orchestrator.onCancelRequested()).isFalse()
			// The warm compile keeps running: it is the daemon warm-up the next real save needs.
			assertThat(executor.cancellations).isEqualTo(0)
			executor.finish(0, success(generation = 0))
			runCurrent()
		}

	@Test
	fun `a build that is not stopped still deploys after a cancel of an earlier one`() =
		runTest {
			// The cancel must not wedge the orchestrator: clearing inFlight is what lets the
			// next build start at all. Without it every later build would be suspended forever.
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			orchestrator.onCancelRequested()
			runCurrent()

			orchestrator.onFilesChanged(known(srcC))
			runCurrent()
			executor.finish(1, success(generation = 1))
			runCurrent()

			assertThat(events.filterIsInstance<OrchestratorEvent.BuildSucceeded>()).hasSize(1)
		}

	@Test
	fun `a relink failure that repeats identically escalates to a proxy app rebuild`() =
		runTest {
			// The stuck-relink gap: the failed batch returns to pending, so the broken resource
			// is dragged into every later build and re-fails - including builds whose own edit
			// was pure code. Nothing on the live reload path can clear it.
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			orchestrator.onFilesChanged(known(resLayout))
			runCurrent()
			executor.finish(0, relinkFailure())
			runCurrent()
			// One failure is not evidence: it may have been transient.
			assertThat(events.filterIsInstance<OrchestratorEvent.InvalidationRequired>()).isEmpty()

			// A later code save drags the still-pending resource back in and fails identically.
			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			assertThat(executor.requests[1].route).isEqualTo(BuildRoute.CodeAndResources)
			executor.finish(1, relinkFailure())
			runCurrent()

			assertThat(events.filterIsInstance<OrchestratorEvent.InvalidationRequired>())
				.containsExactly(
					OrchestratorEvent.InvalidationRequired(InvalidationReason.RELOAD_PIPELINE_FAILED),
				)
			// The failure is still reported: the fallback is visible, not a silent swallow.
			assertThat(events.filterIsInstance<OrchestratorEvent.BuildFailed>()).hasSize(2)
			// Nothing else was launched to be superseded by the rebuild.
			assertThat(executor.requests).hasSize(2)

			// Never-stale: the whole batch is still pending, so the rebuild absorbs it - and a
			// save landing before the rebuild starts still carries both earlier edits.
			orchestrator.onFilesChanged(known(srcB))
			runCurrent()
			assertThat(executor.requests[2].changes).isEqualTo(known(resLayout, srcA, srcB))
		}

	@Test
	fun `a proxy app rebuild that fails is not requested again - no rebuild-fail-rebuild loop`() =
		runTest {
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			orchestrator.onFilesChanged(known(resLayout))
			runCurrent()
			executor.finish(0, relinkFailure())
			runCurrent()
			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			executor.finish(1, relinkFailure())
			runCurrent()
			assertThat(events.filterIsInstance<OrchestratorEvent.InvalidationRequired>()).hasSize(1)

			// The rebuild ran and failed; the batch comes back and quick builds resume.
			orchestrator.onProxyAppRebuildStarted()
			orchestrator.onProxyAppRebuildFailed()
			runCurrent()

			// Two more identical failures must NOT ask for another rebuild.
			orchestrator.onFilesChanged(known(srcB))
			runCurrent()
			executor.finish(2, relinkFailure())
			runCurrent()
			orchestrator.onFilesChanged(known(srcC))
			runCurrent()
			executor.finish(3, relinkFailure())
			runCurrent()

			assertThat(events.filterIsInstance<OrchestratorEvent.InvalidationRequired>()).hasSize(1)
		}

	@Test
	fun `a successful rebuild re-arms the escalation`() =
		runTest {
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			orchestrator.onFilesChanged(known(resLayout))
			runCurrent()
			executor.finish(0, relinkFailure())
			runCurrent()
			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			executor.finish(1, relinkFailure())
			runCurrent()
			orchestrator.onProxyAppRebuildStarted()
			orchestrator.onBaselineReset()
			runCurrent()

			// A fresh baseline, and the pipeline breaks again: that deserves its own rebuild.
			orchestrator.onFilesChanged(known(resLayout))
			runCurrent()
			executor.finish(2, relinkFailure())
			runCurrent()
			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			executor.finish(3, relinkFailure())
			runCurrent()

			assertThat(events.filterIsInstance<OrchestratorEvent.InvalidationRequired>()).hasSize(2)
		}

	@Test
	fun `a repeated compile error never escalates - it is the user's code, not the pipeline`() =
		runTest {
			// Escalating here would run a ~200s Gradle build that rejects the same code, and a
			// failed proxy app rebuild drops the session to Idle - worse than the compile error.
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			orchestrator.onFilesChanged(known(resLayout))
			runCurrent()
			executor.finish(0, compileError())
			runCurrent()
			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			executor.finish(1, compileError())
			runCurrent()
			orchestrator.onFilesChanged(known(srcB))
			runCurrent()
			executor.finish(2, compileError())
			runCurrent()

			assertThat(events.filterIsInstance<OrchestratorEvent.InvalidationRequired>()).isEmpty()
		}

	@Test
	fun `a daemon death does not escalate - it has its own respawn recovery`() =
		runTest {
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			val died = BuildOutcome.InfrastructureFailure("daemon exited", daemonDied = true)
			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			executor.finish(0, died)
			runCurrent()
			orchestrator.onFilesChanged(known(srcB))
			runCurrent()
			executor.finish(1, died)
			runCurrent()

			assertThat(events.filterIsInstance<OrchestratorEvent.InvalidationRequired>()).isEmpty()
		}

	@Test
	fun `two different pipeline failures do not escalate - only an identical repeat is evidence`() =
		runTest {
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			orchestrator.onFilesChanged(known(resLayout))
			runCurrent()
			executor.finish(0, BuildOutcome.InfrastructureFailure("aapt2 link: broken pipe"))
			runCurrent()
			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			executor.finish(1, BuildOutcome.InfrastructureFailure("scratch dir is full"))
			runCurrent()

			assertThat(events.filterIsInstance<OrchestratorEvent.InvalidationRequired>()).isEmpty()
		}

	@Test
	fun `a failed warm compile never escalates`() =
		runTest {
			// A warm compile's failure is not user-visible, so it must not drag the user into a
			// full Gradle build either.
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			orchestrator.onWarmCompileRequested()
			runCurrent()
			executor.finish(0, relinkFailure())
			runCurrent()
			orchestrator.onWarmCompileRequested()
			runCurrent()
			executor.finish(1, relinkFailure())
			runCurrent()

			assertThat(events.filterIsInstance<OrchestratorEvent.InvalidationRequired>()).isEmpty()
		}

	@Test
	fun `a repeating aapt2 rejection is flagged as blocking every build`() =
		runTest {
			// The other half of the stuck-relink gap. aapt2 links the whole res/ tree from disk,
			// not the changed set, so an unlinkable resource fails every later build whatever the
			// user saves next - and the one they cannot fix by editing (a reference the proxy
			// app build's resource snapshot lacks) leaves the session dead with no explanation.
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			orchestrator.onFilesChanged(known(resLayout))
			runCurrent()
			executor.finish(0, resourceError())
			runCurrent()
			// One rejection is an ordinary compile error - the user is looking at the file.
			assertThat(events.filterIsInstance<OrchestratorEvent.BuildFailed>().map { it.relinkStuck })
				.containsExactly(false)

			// A pure-code save drags the still-pending resource back in and fails identically.
			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			assertThat(executor.requests[1].route).isEqualTo(BuildRoute.CodeAndResources)
			executor.finish(1, resourceError())
			runCurrent()

			assertThat(events.filterIsInstance<OrchestratorEvent.BuildFailed>().map { it.relinkStuck })
				.containsExactly(false, true)
			// Saying it is all this does: no escalation, so a resource typo never costs a ~200s
			// Gradle build and a failed one can never drop the session to Idle.
			assertThat(events.filterIsInstance<OrchestratorEvent.InvalidationRequired>()).isEmpty()
			// Never-stale is untouched - the whole batch is still pending.
			orchestrator.onFilesChanged(known(srcB))
			runCurrent()
			assertThat(executor.requests[2].changes).isEqualTo(known(resLayout, srcA, srcB))
		}

	@Test
	fun `a repeating kotlinc error is not flagged as blocking - it names the file being edited`() =
		runTest {
			// Same shape as the aapt2 case and deliberately not flagged: the error names the file
			// the user is working in, so nothing about it is surprising, and there is no variant
			// of it that no edit can fix. Flagging it would fire on ordinary mid-typing saves.
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			executor.finish(0, compileError())
			runCurrent()
			orchestrator.onFilesChanged(known(srcB))
			runCurrent()
			executor.finish(1, compileError())
			runCurrent()
			orchestrator.onFilesChanged(known(srcC))
			runCurrent()
			executor.finish(2, compileError())
			runCurrent()

			assertThat(events.filterIsInstance<OrchestratorEvent.BuildFailed>().map { it.relinkStuck })
				.containsExactly(false, false, false)
		}

	@Test
	fun `the blocking flag is raised once per streak and re-armed by a success`() =
		runTest {
			// The message asks the user to do something, so repeating it on every save would
			// train them to dismiss it. A success means the resources link again, which makes a
			// later stuck relink a genuinely new situation.
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			orchestrator.onFilesChanged(known(resLayout))
			runCurrent()
			executor.finish(0, resourceError())
			runCurrent()
			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			executor.finish(1, resourceError())
			runCurrent()
			orchestrator.onFilesChanged(known(srcB))
			runCurrent()
			executor.finish(2, resourceError())
			runCurrent()
			orchestrator.onFilesChanged(known(srcC))
			runCurrent()
			executor.finish(3, success(generation = 1))
			runCurrent()
			orchestrator.onFilesChanged(known(resLayout))
			runCurrent()
			executor.finish(4, resourceError())
			runCurrent()
			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			executor.finish(5, resourceError())
			runCurrent()

			assertThat(events.filterIsInstance<OrchestratorEvent.BuildFailed>().map { it.relinkStuck })
				.containsExactly(false, true, false, false, true)
		}

	@Test
	fun `a second not-connected deploy running is flagged as the proxy app not staying up`() =
		runTest {
			// The baseline-crash trap: provisioning captured a startup crash, so the app dies
			// before it can receive anything. Every save compiles and dexes fine and then has
			// nowhere to land, and the failure's own "relaunch to reconnect" restarts the crash.
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			executor.finish(0, notConnected())
			runCurrent()
			// One is ordinary: the app may simply have been closed, and the deploy relaunches it.
			assertThat(events.filterIsInstance<OrchestratorEvent.BuildFailed>().map { it.proxyAppWontStayUp })
				.containsExactly(false)

			orchestrator.onFilesChanged(known(srcB))
			runCurrent()
			executor.finish(1, notConnected())
			runCurrent()

			assertThat(events.filterIsInstance<OrchestratorEvent.BuildFailed>().map { it.proxyAppWontStayUp })
				.containsExactly(false, true)
		}

	@Test
	fun `the not-staying-up report fires once per streak, not on every later save`() =
		runTest {
			// The message asks the user to restart the session; repeating it on every save would
			// train them to dismiss it.
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			listOf(srcA, srcB, srcA, srcB).forEachIndexed { i, file ->
				orchestrator.onFilesChanged(known(file))
				runCurrent()
				executor.finish(i, notConnected())
				runCurrent()
			}

			assertThat(
				events.filterIsInstance<OrchestratorEvent.BuildFailed>().count { it.proxyAppWontStayUp },
			).isEqualTo(1)
		}

	@Test
	fun `a deploy failure that is not the not-connected one never claims it`() =
		runTest {
			// Typed, not message-matched: only the path that already tried a launch counts.
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			repeat(2) { i ->
				orchestrator.onFilesChanged(known(if (i == 0) srcA else srcB))
				runCurrent()
				executor.finish(i, BuildOutcome.DeployFailure("Proxy app disconnected during deploy"))
				runCurrent()
			}

			assertThat(events.filterIsInstance<OrchestratorEvent.BuildFailed>().map { it.proxyAppWontStayUp })
				.containsExactly(false, false)
		}

	@Test
	fun `a pending manifest edit survives a daemon replacement collapsing the set to Unknown`() =
		runTest {
			// The silent-staleness path: pending + Unknown discards the manifest path, and
			// Unknown classifies as the FAST route, so the next build compiles, relinks, deploys
			// and reports Success with the manifest change never absorbed. Worse, the
			// invalidation was already reported, so the parked state converts to a quiet success.
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			orchestrator.onFilesChanged(known("app/src/main/AndroidManifest.xml"))
			runCurrent()
			assertThat(events.filterIsInstance<OrchestratorEvent.InvalidationRequired>())
				.containsExactly(OrchestratorEvent.InvalidationRequired(InvalidationReason.MANIFEST_CHANGED))

			// A low-memory teardown respawns the daemon before the rebuild runs.
			orchestrator.onDaemonReplaced()
			runCurrent()
			orchestrator.onFilesChanged(known(srcA))
			runCurrent()

			// Still parked: no quick build may run until Gradle absorbs the manifest.
			assertThat(executor.requests).isEmpty()
			assertThat(events.filterIsInstance<OrchestratorEvent.InvalidationRequired>()).hasSize(1)
		}

	@Test
	fun `a pending gradle edit survives an untrusted baseline collapsing the set to Unknown`() =
		runTest {
			// Same collapse, reached the other way: an external Standard Run hands back and marks
			// the baseline untrusted while a build.gradle.kts edit is still pending.
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			orchestrator.onFilesChanged(known("app/build.gradle.kts"))
			runCurrent()
			orchestrator.onBaselineUntrusted()
			runCurrent()
			orchestrator.onFilesChanged(known(srcA))
			runCurrent()

			assertThat(executor.requests).isEmpty()
			assertThat(events.filterIsInstance<OrchestratorEvent.InvalidationRequired>())
				.containsExactly(OrchestratorEvent.InvalidationRequired(InvalidationReason.GRADLE_CONFIG_CHANGED))
		}

	@Test
	fun `an invalidation latched through a collapse is re-reported after a failed proxy app rebuild`() =
		runTest {
			// The rebuild absorbed nothing, so the manifest edit is still unabsorbed - the next
			// save must re-report rather than quietly take the fast path.
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			orchestrator.onFilesChanged(known("app/src/main/AndroidManifest.xml"))
			runCurrent()
			orchestrator.onDaemonReplaced()
			runCurrent()
			orchestrator.onProxyAppRebuildStarted()
			orchestrator.onProxyAppRebuildFailed()
			runCurrent()
			orchestrator.onFilesChanged(known(srcA))
			runCurrent()

			assertThat(executor.requests).isEmpty()
			assertThat(events.filterIsInstance<OrchestratorEvent.InvalidationRequired>()).hasSize(2)
		}

	@Test
	fun `a latched invalidation clears once a proxy app rebuild absorbs it`() =
		runTest {
			// The latch must not park the session forever: a completed rebaseline releases it.
			val executor = GatedExecutor()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) {}

			orchestrator.onFilesChanged(known("app/src/main/AndroidManifest.xml"))
			runCurrent()
			orchestrator.onDaemonReplaced()
			runCurrent()
			orchestrator.onProxyAppRebuildStarted()
			orchestrator.onBaselineReset()
			runCurrent()
			orchestrator.onFilesChanged(known(srcA))
			runCurrent()

			assertThat(executor.requests.single().changes).isEqualTo(known(srcA))
			assertThat(executor.requests.single().route).isEqualTo(BuildRoute.CodeOnly)
		}

	@Test
	fun `a collapse with no invalidating path pending still takes the fast daemon path`() =
		runTest {
			// The latch must not turn every Unknown into a Gradle build - that would make an
			// external Standard Run's hand-back cost a full rebaseline every time.
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			executor.finish(0, compileError())
			runCurrent()
			orchestrator.onBaselineUntrusted()
			orchestrator.onFilesChanged(known(srcB))
			runCurrent()

			assertThat(executor.requests.last().changes).isEqualTo(ChangedFiles.Unknown)
			assertThat(executor.requests.last().route).isEqualTo(BuildRoute.CodeAndResources)
			assertThat(events.filterIsInstance<OrchestratorEvent.InvalidationRequired>()).isEmpty()
		}
}
