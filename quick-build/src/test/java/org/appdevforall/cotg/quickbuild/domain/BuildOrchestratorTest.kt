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
class BuildOrchestratorTest {
	private class GatedExecutor : QuickBuildExecutor {
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
			val orchestrator = BuildOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

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
	fun `save during in-flight build coalesces and never cancels the running compile`() =
		runTest {
			val executor = GatedExecutor()
			val orchestrator = BuildOrchestrator(executor, ChangeClassifier(), backgroundScope) {}

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
			val orchestrator = BuildOrchestrator(executor, ChangeClassifier(), backgroundScope) {}

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
			val orchestrator = BuildOrchestrator(executor, ChangeClassifier(), backgroundScope) {}

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
			val orchestrator = BuildOrchestrator(executor, ChangeClassifier(), backgroundScope) {}

			orchestrator.onFilesChanged(ChangedFiles.Known.EMPTY)
			orchestrator.onFilesChanged(ChangedFiles.Known.EMPTY)
			runCurrent()

			assertThat(executor.requests).isEmpty()
		}

	@Test
	fun `unknown changes force a full recompile on the quick path`() =
		runTest {
			val executor = GatedExecutor()
			val orchestrator = BuildOrchestrator(executor, ChangeClassifier(), backgroundScope) {}

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
			val orchestrator = BuildOrchestrator(executor, ChangeClassifier(), backgroundScope) {}

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
			val orchestrator = BuildOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

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
			val orchestrator = BuildOrchestrator(executor, ChangeClassifier(), backgroundScope) {}

			orchestrator.onFilesChanged(known("app/src/main/AndroidManifest.xml"))
			runCurrent()
			orchestrator.onRebaselineStarted()
			orchestrator.onBaselineReset()
			runCurrent()

			// The manifest edit was absorbed by the re-baseline; a fresh code save builds.
			orchestrator.onFilesChanged(known(srcA))
			runCurrent()

			assertThat(executor.requests).hasSize(1)
			assertThat(executor.requests[0].changes).isEqualTo(known(srcA))
		}

	@Test
	fun `save landing mid-rebaseline is kept and quick-built right after the reset`() =
		runTest {
			// Regression (review F1): the Gradle build only absorbs what existed when it
			// STARTED; a save landing while it runs must not be dropped with the batch.
			val executor = GatedExecutor()
			val orchestrator = BuildOrchestrator(executor, ChangeClassifier(), backgroundScope) {}

			orchestrator.onFilesChanged(known("app/src/main/AndroidManifest.xml"))
			runCurrent()
			orchestrator.onRebaselineStarted()
			orchestrator.onFilesChanged(known(srcA)) // mid-rebaseline save
			runCurrent()
			assertThat(executor.requests).isEmpty() // still invalidated: no quick build yet

			orchestrator.onBaselineReset()
			runCurrent()

			assertThat(executor.requests).hasSize(1)
			assertThat(executor.requests[0].changes).isEqualTo(known(srcA))
		}

	@Test
	fun `failed rebaseline returns the held batch to pending and re-reports on next save`() =
		runTest {
			val executor = GatedExecutor()
			val events = mutableListOf<OrchestratorEvent>()
			val orchestrator = BuildOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			orchestrator.onFilesChanged(known("app/src/main/AndroidManifest.xml"))
			runCurrent()
			assertThat(events.filterIsInstance<OrchestratorEvent.InvalidationRequired>()).hasSize(1)

			orchestrator.onRebaselineStarted()
			orchestrator.onRebaselineFailed()
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
			val orchestrator = BuildOrchestrator(executor, ChangeClassifier(), backgroundScope) {}

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
			val orchestrator = BuildOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			events.clear()

			// A full Gradle build re-baselined the session while build #1 was in flight.
			orchestrator.onRebaselineStarted()
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
			val orchestrator = BuildOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

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
			val orchestrator = BuildOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

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
			val orchestrator = BuildOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

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
			val orchestrator = BuildOrchestrator(executor, ChangeClassifier(), backgroundScope) {}

			orchestrator.onQuickBuildRequested()
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
			val orchestrator = BuildOrchestrator(executor, ChangeClassifier(), backgroundScope) {}

			orchestrator.onFilesChanged(known(srcA))
			runCurrent()
			orchestrator.onQuickBuildRequested()
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
			val orchestrator = BuildOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

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
	fun `crash recovery — seeding with unknown yields one slow-but-correct first build`() =
		runTest {
			// After a CoGo restart the watcher history is gone; the session manager seeds
			// the fresh orchestrator with Unknown. First build is full, nothing is lost.
			val executor = GatedExecutor()
			val orchestrator = BuildOrchestrator(executor, ChangeClassifier(), backgroundScope) {}

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
			val orchestrator = BuildOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

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
			val orchestrator = BuildOrchestrator(executor, ChangeClassifier(), backgroundScope) { events += it }

			orchestrator.onBaselineUntrusted()
			runCurrent()

			// Deferred re-seed: no build, no events, until the next save or tap.
			assertThat(executor.requests).isEmpty()
			assertThat(events).isEmpty()

			orchestrator.onFilesChanged(known(srcA))
			runCurrent()

			// The next build recompiles everything from current disk.
			assertThat(executor.requests.single().changes).isEqualTo(ChangedFiles.Unknown)
			assertThat(executor.requests.single().route).isEqualTo(BuildRoute.CodeAndResources)
		}

	@Test
	fun `onBaselineUntrusted during an in-flight build re-seeds the coalesced follow-up`() =
		runTest {
			val executor = GatedExecutor()
			val orchestrator = BuildOrchestrator(executor, ChangeClassifier(), backgroundScope) {}

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
}
