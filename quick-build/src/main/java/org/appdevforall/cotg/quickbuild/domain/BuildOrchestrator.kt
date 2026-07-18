package org.appdevforall.cotg.quickbuild.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

/**
 * The quick-build concurrency model (plan sections 2.3 and 1.4). Pure JVM — no Android
 * imports — so the whole model is unit-testable off-device.
 *
 * Invariant: **the pending changed-set is never lost** — not by a save landing mid-build,
 * not by a failed compile, not by a re-baseline, not by a crash. Concretely:
 * - At most one build is in flight; saves that land mid-build coalesce into a pending set.
 * - Starting a build MOVES the pending set into the build; it is cleared only when that
 *   build succeeds. A failed batch is unioned back into pending. (The prototype cleared
 *   before compiling and silently dropped edits on failure — regression-tested.)
 * - An empty known changed-set is not "unknown": no-op saves never trigger a recompile.
 * - A running compile is never cancelled; new work waits and coalesces.
 * - Results are tagged with their build id; a result for a superseded build is discarded,
 *   never rendered.
 *
 * After a FAILED build the orchestrator only rebuilds immediately when new saves arrived
 * mid-build (they may contain the fix). Retrying the same failed batch with no new edits
 * would fail identically — it waits for the next save instead. When that immediate
 * follow-up fails with a diagnostic set identical to the build it followed, the failure
 * event carries [OrchestratorEvent.BuildFailed.diagnosticsUnchanged] so the status
 * surface doesn't re-render the same errors.
 *
 * Re-baseline protocol (full Gradle fallback): the session manager calls
 * [onRebaselineStarted] when it kicks the Gradle build and [onBaselineReset] /
 * [onRebaselineFailed] when it finishes. Only the changes that existed when the Gradle
 * build STARTED are treated as absorbed — a save landing mid-rebaseline stays pending
 * and quick-builds right after the reset (over-building is safe; dropping an edit is not).
 *
 * Threading: events are delivered via [onEvent] outside the internal lock, on the
 * caller's context, so handlers may call back into the orchestrator. Event ORDER is
 * guaranteed only when the public API and [scope] share a single-threaded dispatcher —
 * on a multithreaded dispatcher a fast build could report Finished before the caller's
 * thread delivers Started. Wire it single-threaded.
 */
class BuildOrchestrator(
	private val executor: QuickBuildExecutor,
	private val classifier: ChangeClassifier,
	private val scope: CoroutineScope,
	private val onEvent: (OrchestratorEvent) -> Unit,
) {
	private val log = LoggerFactory.getLogger(BuildOrchestrator::class.java)

	private val mutex = Mutex()
	private var pending: ChangedFiles = ChangedFiles.Known.EMPTY
	private var pendingForced = false
	private var inFlight: InFlightBuild? = null
	private var nextBuildId = 1L
	private var invalidationReported = false

	/** Changes a running Gradle re-baseline will absorb; restored if it fails. */
	private var awaitingAbsorption: ChangedFiles? = null

	/** Diagnostics of the last CompileError, for the duplicate-follow-up guard. */
	private var lastCompileDiagnostics: List<BuildDiagnostic>? = null

	private data class InFlightBuild(
		val buildId: Long,
		val batch: ChangedFiles,
		val forced: Boolean,
		val autoFollowUp: Boolean,
	)

	/** A watcher/editor save event. [ChangedFiles.Unknown] forces a full recompile. */
	suspend fun onFilesChanged(changes: ChangedFiles) {
		withEvents { events ->
			pending = pending + changes
			maybeStartBuildLocked(events)
		}
	}

	/**
	 * An explicit Quick Build tap: build now even if nothing changed (redeploy).
	 * A failed forced build re-arms the flag, so the eventual retry is also forced.
	 */
	suspend fun onQuickBuildRequested() {
		withEvents { events ->
			pendingForced = true
			maybeStartBuildLocked(events)
		}
	}

	/**
	 * An external full Gradle build (a Standard Run) completed while this session is
	 * live: generated inputs and classpath jars under build/ may have moved beneath the
	 * daemon, which the watcher cannot see. Marks the whole baseline dirty WITHOUT
	 * starting a build - the next build (save or tap) recompiles everything from current
	 * disk, so the hand-back can never serve code compiled against the old baseline.
	 */
	suspend fun onBaselineUntrusted() {
		mutex.withLock {
			pending = pending + ChangedFiles.Unknown
		}
	}

	/**
	 * The session manager kicked off the full Gradle re-baseline build. Everything
	 * currently pending (and any in-flight quick build's batch — those files are on disk,
	 * so Gradle reads them) is marked as absorbed-in-progress; the in-flight build is
	 * superseded so its late result is discarded. Saves arriving after this call
	 * accumulate as NOT absorbed — the Gradle build may have already read those files.
	 */
	suspend fun onRebaselineStarted() {
		mutex.withLock {
			awaitingAbsorption = (inFlight?.batch ?: ChangedFiles.Known.EMPTY) + pending
			pending = ChangedFiles.Known.EMPTY
			pendingForced = false
			inFlight = null
		}
	}

	/**
	 * The re-baseline completed: drop the absorbed changes, keep (and immediately build)
	 * anything that arrived mid-rebaseline. Calling this without [onRebaselineStarted]
	 * is a protocol violation; the orchestrator then falls back to dropping everything
	 * pending, which risks a stale test app — hence the warning.
	 */
	suspend fun onBaselineReset() {
		withEvents { events ->
			if (awaitingAbsorption == null) {
				log.warn("onBaselineReset without onRebaselineStarted; dropping pending set")
				pending = ChangedFiles.Known.EMPTY
				pendingForced = false
				inFlight = null
			}
			awaitingAbsorption = null
			invalidationReported = false
			lastCompileDiagnostics = null
			maybeStartBuildLocked(events)
		}
	}

	/**
	 * The re-baseline build failed (e.g. the manifest edit that forced it doesn't
	 * compile). Nothing was absorbed: the held batch returns to pending. No event is
	 * emitted here — re-reporting invalidation would loop the failing fallback; the
	 * next save re-triggers it once the user has fixed the problem.
	 */
	suspend fun onRebaselineFailed() {
		mutex.withLock {
			awaitingAbsorption?.let { held ->
				pending = held + pending
			}
			awaitingAbsorption = null
			invalidationReported = false
		}
	}

	private suspend inline fun withEvents(block: (MutableList<OrchestratorEvent>) -> Unit) {
		val events = mutableListOf<OrchestratorEvent>()
		mutex.withLock { block(events) }
		events.forEach(onEvent)
	}

	private fun maybeStartBuildLocked(
		events: MutableList<OrchestratorEvent>,
		autoFollowUp: Boolean = false,
	) {
		if (inFlight != null) return
		// Quick builds are suspended while a re-baseline runs: they would race the
		// Gradle build against a half-reseeded baseline. Saves keep accumulating and
		// build on onBaselineReset.
		if (awaitingAbsorption != null) return
		if (pending.isEmpty && !pendingForced) return

		val route = classifier.classify(pending)
		if (route is BuildRoute.FullGradleBuild) {
			// The quick path can't absorb this; hand off to the session manager once.
			// Pending is kept: it documents what the re-baseline will absorb.
			if (!invalidationReported) {
				invalidationReported = true
				events += OrchestratorEvent.InvalidationRequired(route.reason)
			}
			return
		}

		val batch = pending
		val forced = pendingForced
		pending = ChangedFiles.Known.EMPTY
		pendingForced = false
		val buildId = nextBuildId++
		inFlight = InFlightBuild(buildId, batch, forced, autoFollowUp)
		events += OrchestratorEvent.BuildStarted(buildId, route, batch)

		val request = BuildRequest(buildId = buildId, changes = batch, route = route, forced = forced)
		scope.launch {
			val outcome =
				try {
					executor.execute(request)
				} catch (e: CancellationException) {
					throw e
				} catch (e: Throwable) {
					log.error("Quick build #{} threw instead of reporting an outcome", buildId, e)
					BuildOutcome.InfrastructureFailure(e.message ?: e.javaClass.name)
				}
			onBuildFinished(buildId, outcome)
		}
	}

	private suspend fun onBuildFinished(
		buildId: Long,
		outcome: BuildOutcome,
	) {
		withEvents { events ->
			val flight = inFlight
			if (flight == null || flight.buildId != buildId) {
				// Superseded (e.g. baseline reset raced this build) — discard, never render.
				log.info("Discarding stale result of superseded quick build #{}", buildId)
				return@withEvents
			}
			inFlight = null

			when (outcome) {
				is BuildOutcome.Success -> {
					lastCompileDiagnostics = null
					events += OrchestratorEvent.BuildSucceeded(buildId, outcome)
					// Saves that landed mid-build start the coalesced follow-up now.
					maybeStartBuildLocked(events, autoFollowUp = true)
				}
				else -> {
					val newSavesArrivedMidBuild = !pending.isEmpty || pendingForced
					pending = flight.batch + pending
					pendingForced = pendingForced || flight.forced

					val diagnostics = (outcome as? BuildOutcome.CompileError)?.diagnostics
					val unchanged =
						flight.autoFollowUp && diagnostics != null && diagnostics == lastCompileDiagnostics
					if (diagnostics != null) {
						lastCompileDiagnostics = diagnostics
					}
					events += OrchestratorEvent.BuildFailed(buildId, outcome, unchanged)

					if (newSavesArrivedMidBuild) {
						// A mid-build save may be the fix; rebuild from the accumulated set.
						maybeStartBuildLocked(events, autoFollowUp = true)
					}
				}
			}
		}
	}
}

sealed interface OrchestratorEvent {
	data class BuildStarted(
		val buildId: Long,
		val route: BuildRoute,
		val changes: ChangedFiles,
	) : OrchestratorEvent

	data class BuildSucceeded(
		val buildId: Long,
		val result: BuildOutcome.Success,
	) : OrchestratorEvent

	/**
	 * @property diagnosticsUnchanged true when this build was an automatic follow-up
	 *   that failed with exactly the diagnostics of the build it followed — the status
	 *   surface should keep the existing rendering instead of re-notifying.
	 */
	data class BuildFailed(
		val buildId: Long,
		val outcome: BuildOutcome,
		val diagnosticsUnchanged: Boolean = false,
	) : OrchestratorEvent

	/** The changed-set needs a real Gradle build; the session manager owns the fallback. */
	data class InvalidationRequired(
		val reason: InvalidationReason,
	) : OrchestratorEvent
}
