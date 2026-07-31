@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.appdevforall.cotg.quickbuild.domain

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Pins the concurrency model from plan sections 2.3 and 1.4. The invariant under test:
 * the pending changed-set is never lost — not by a save landing mid-build, not by a
 * failed compile, not by a superseded build.
 *
 * Three tests are regressions for exact prototype bugs:
 * - "multi-file batch survives a failed compile" — the prototype cleared changedSrc
 *   BEFORE compiling, so a failed compile silently dropped edits;
 * - "no-op save does not trigger a build" — the prototype conflated empty-changed-set
 *   with unknown and ran spurious full recompiles;
 * - "result of a superseded build is discarded" — generation/build-id tagged results.
 */
class LiveReloadOrchestratorTest {
	private class GatedExecutor : LiveReloadExecutor {
		val requests = mutableListOf<BuildRequest>()
		val gates = mutableListOf<CompletableDeferred<BuildOutcome>>()
		var cancellations = 0
		var throwOnNext: Throwable? = null

		override suspend fun execute(request: BuildRequest): BuildOutcome {
			requests += request
			throwOnNext?.let { error ->
				throwOnNext = null
				throw error
			}
			val gate = CompletableDeferred<BuildOutcome>()
			gates += gate
			try {
				return gate.await()
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
	fun `a forced tap on an empty queue is stamped at the tap time`() =
		runTest {
			var nowMs = 500L
			val executor = GatedExecutor()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope, now = { nowMs }) {}

			orchestrator.onLiveReloadRequested()
			runCurrent()

			assertThat(executor.requests.single().triggeredAtMillis).isEqualTo(500L)
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
	fun `multi-file batch survives a failed compile — nothing is dropped`() =
		runTest {
			// Regression for the prototype bug: changedSrc was cleared before the compile,
			// so a failed compile silently dropped every file in the batch.
			val executor = GatedExecutor()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) {}

			orchestrator.onFilesChanged(known(srcA, srcB))
			runCurrent()
			executor.finish(0, compileError())
			runCurrent()

			// No new saves arrived mid-build: the orchestrator waits (retrying the identical
			// batch would fail identically). The failed batch is back in pending.
			assertThat(executor.requests).hasSize(1)

			// The user fixes B — the next build carries the WHOLE failed batch, not just B.
			orchestrator.onFilesChanged(known(srcB))
			runCurrent()

			assertThat(executor.requests).hasSize(2)
			assertThat(executor.requests[1].changes).isEqualTo(known(srcA, srcB))
		}

	@Test
	fun `plan 1-4 sequence — failed batch unions with mid-build save, fix rebuilds everything`() =
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
			// for the next save — documented in the ticket status doc, wrapper repo.)
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
			// Regression for the prototype bug: empty changed-set was conflated with
			// unknown, so a no-op save ran a spurious full recompile.
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
			// Regression (review F1): the Gradle build only absorbs what existed when it
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

			// The late result must produce no events — its diagnostics/success are stale.
			assertThat(events).isEmpty()
		}

	@Test
	fun `immediate follow-up failing with identical diagnostics is flagged unchanged`() =
		runTest {
			// Review F2 / planning-session ruling: the wasted-build case must stay invisible —
			// the same error must not flash twice at the user.
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			orchestrator.onFilesChanged(known(srcA, srcB))
			runCurrent()
			orchestrator.onFilesChanged(known(srcC)) // mid-build save (not the fix)
			runCurrent()
			executor.finish(0, compileError())
			runCurrent()
			// Follow-up #2 fired immediately and fails with the SAME diagnostics.
			executor.finish(1, compileError())
			runCurrent()

			val failures = events.filterIsInstance<OrchestratorEvent.BuildFailed>()
			assertThat(failures).hasSize(2)
			assertThat(failures[0].diagnosticsUnchanged).isFalse()
			assertThat(failures[1].diagnosticsUnchanged).isTrue()
		}

	@Test
	fun `follow-up failing with different diagnostics is not flagged`() =
		runTest {
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			orchestrator.onFilesChanged(known(srcC))
			runCurrent()
			executor.finish(0, compileError())
			runCurrent()
			executor.finish(
				1,
				BuildOutcome.CompileError(
					listOf(BuildDiagnostic(BuildDiagnostic.Severity.ERROR, "unresolved reference", "C.kt", 3, 1)),
				),
			)
			runCurrent()

			val failures = events.filterIsInstance<OrchestratorEvent.BuildFailed>()
			assertThat(failures).hasSize(2)
			assertThat(failures[1].diagnosticsUnchanged).isFalse()
		}

	@Test
	fun `user-triggered rebuild is never flagged unchanged even with identical diagnostics`() =
		runTest {
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			executor.finish(0, compileError())
			runCurrent()

			// The user saves again (same broken file) — a fresh attempt they asked for:
			// identical diagnostics must still be rendered.
			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			executor.finish(1, compileError())
			runCurrent()

			val failures = events.filterIsInstance<OrchestratorEvent.BuildFailed>()
			assertThat(failures).hasSize(2)
			assertThat(failures[1].diagnosticsUnchanged).isFalse()
		}

	@Test
	fun `forced tap with nothing changed still executes a redeploy build`() =
		runTest {
			val executor = GatedExecutor()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) {}

			orchestrator.onLiveReloadRequested()
			runCurrent()

			assertThat(executor.requests).hasSize(1)
			assertThat(executor.requests[0].forced).isTrue()
			assertThat(executor.requests[0].route).isEqualTo(BuildRoute.NoOp)
			assertThat(executor.requests[0].changes.isEmpty).isTrue()
		}

	@Test
	fun `forced tap during an in-flight build runs a follow-up after success`() =
		runTest {
			val executor = GatedExecutor()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) {}

			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			orchestrator.onLiveReloadRequested()
			runCurrent()
			assertThat(executor.requests).hasSize(1)

			executor.finish(0, success(generation = 1))
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
	fun `crash recovery — priming with unknown yields one slow-but-correct first build`() =
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
				// Unknown, not Known.EMPTY - a warm compile covers every source, not zero files
				// (2026-07-26 review nit: metrics must not read this as "0 files changed").
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
	fun `a warm compile's compile error does not prime diagnosticsUnchanged for the auto-follow-up that answers it`() =
		runTest {
			// 2026-07-26 review minor finding: a warm-compile failure is invisible to the user (the
			// session manager never surfaces it), so it must not silently flag the FIRST
			// real failure the user actually sees as "unchanged" just because a save that
			// landed mid-warm-compile produced an identical error.
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			orchestrator.onWarmCompileRequested()
			runCurrent()
			// A real save lands WHILE the warm compile runs - its build starts automatically
			// as this build's auto-follow-up once the warm compile's own result lands.
			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			executor.finish(0, compileError()) // the warm compile's (invisible) failure
			runCurrent()
			assertThat(executor.requests).hasSize(2)
			executor.finish(1, compileError()) // identical diagnostics to the warm compile's failure
			runCurrent()

			val realFailure = events.filterIsInstance<OrchestratorEvent.BuildFailed>().single { it.route != BuildRoute.WarmCompile }
			assertThat(realFailure.diagnosticsUnchanged).isFalse()
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

			val awaitsDeploy = orchestrator.onLiveReloadRequested(userInitiated = true)
			orchestrator.onBaselineReset()
			runCurrent()
			executor.finish(0, success(generation = 2))
			runCurrent()

			assertThat(awaitsDeploy).isTrue()
			val succeeded = events.filterIsInstance<OrchestratorEvent.BuildSucceeded>().single()
			assertThat(succeeded.userInitiated).isTrue()
		}

	@Test
	fun `a tap with nothing pending reports no deploy to wait for`() =
		runTest {
			// Behaviour 4's decision point. The build still runs (forced redeploy), but the
			// caller must be told it has nothing worth waiting for.
			val executor = GatedExecutor()
			val orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), backgroundScope) {}

			val awaitsDeploy = orchestrator.onLiveReloadRequested(userInitiated = true)
			runCurrent()

			assertThat(awaitsDeploy).isFalse()
			assertThat(executor.requests).hasSize(1)
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
			// The forced flag DOES survive a failure, unchanged - only the ask is one-shot.
			assertThat(executor.requests[1].forced).isTrue()
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
}
