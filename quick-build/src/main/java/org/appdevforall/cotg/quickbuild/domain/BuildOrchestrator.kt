package org.appdevforall.cotg.quickbuild.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
 * - A running compile is never cancelled by NEW WORK; it waits and coalesces. The one
 *   exception is an explicit stop ([onCancelRequested]), which abandons the build but
 *   still returns its batch to pending.
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
	/**
	 * Monotonic clock for the e2e timeline's t0 (the trigger stamp threaded into each
	 * [BuildRequest]). Wired to `SystemClock.elapsedRealtime` on device so it shares the
	 * executor's timebase; the default keeps pure-JVM callers and tests that don't measure
	 * timing working unchanged.
	 */
	private val now: () -> Long = System::currentTimeMillis,
	private val onEvent: (OrchestratorEvent) -> Unit,
) {
	private val log = LoggerFactory.getLogger(BuildOrchestrator::class.java)

	private val mutex = Mutex()
	private var pending: ChangedFiles = ChangedFiles.Known.EMPTY
	private var pendingForced = false

	/**
	 * Set by a Quick Build TAP, and by nothing else. Deliberately NOT folded into
	 * [pendingForced]: `forced` is also set by the reconnect catch-up and is re-armed after
	 * a failed build, so a stale reconnect or a save that retried a failed tap would carry
	 * it - and this flag decides whether the user is yanked out of the editor into the test
	 * app (Bryan's behaviours 2/3). A failed build therefore does NOT re-arm it: the tap was
	 * answered (with an error), and the save that fixes the code is not a new ask.
	 */
	private var pendingUserInitiated = false
	private var inFlight: InFlightBuild? = null
	private var nextBuildId = 1L
	private var invalidationReported = false

	/**
	 * When the current pending batch began accumulating — t0 for the build it becomes.
	 * Set only when a batch STARTS (empty + unforced -> something queued), so coalescing
	 * keeps the earliest change's stamp; reset implicitly when the batch is moved into a
	 * build (pending returns to empty, so the next arrival re-stamps).
	 */
	private var pendingSince: Long = 0L

	/** Changes a running Gradle re-baseline will absorb; restored if it fails. */
	private var awaitingAbsorption: ChangedFiles? = null

	/** Diagnostics of the last CompileError, for the duplicate-follow-up guard. */
	private var lastCompileDiagnostics: List<BuildDiagnostic>? = null

	/** Requested background IC seed (post-provisioning); dropped once any real build runs. */
	private var pendingSeed = false

	private data class InFlightBuild(
		val buildId: Long,
		val batch: ChangedFiles,
		val forced: Boolean,
		val autoFollowUp: Boolean,
		val route: BuildRoute,
		/**
		 * Mutable because a tap landing MID-BUILD is satisfied by this build's deploy
		 * rather than by a second one: the tap has nothing to add except the ask itself.
		 */
		var userInitiated: Boolean = false,
		/** Cancellation handle for [onCancelRequested]; null for a seed (never cancelled). */
		var job: Job? = null,
	)

	/** A watcher/editor save event. [ChangedFiles.Unknown] forces a full recompile. */
	suspend fun onFilesChanged(changes: ChangedFiles) {
		withEvents { events ->
			markBatchArrivalLocked()
			pending = pending + changes
			maybeStartBuildLocked(events)
		}
	}

	/**
	 * An explicit Quick Build tap: build now even if nothing changed (redeploy).
	 * A failed forced build re-arms the flag, so the eventual retry is also forced.
	 *
	 * @param userInitiated whether a HUMAN asked. The default is true because a tap is the
	 *   only caller that should ever say so; the reconnect catch-up (a stale test app
	 *   reporting an old generation) must pass false or it would drag the user into the
	 *   test app for something they did not do.
	 * @return true when the tap's answer is a deploy the caller should wait for (there are
	 *   real changed files pending). False means this is a pure forced redeploy with nothing
	 *   pending: the caller may act on the tap NOW rather than after a full recompile of
	 *   everything (Bryan's behaviour 4).
	 */
	suspend fun onQuickBuildRequested(userInitiated: Boolean = true): Boolean {
		var awaitsDeploy = false
		withEvents { events ->
			markBatchArrivalLocked()
			pendingForced = true
			// Only a tap with real work to wait for arms the on-deploy switch. With nothing
			// pending the build is a pure forced redeploy, which the caller answers
			// immediately instead (behaviour 4) - arming it here too would foreground the
			// test app a second time when that redeploy landed.
			awaitsDeploy = !pending.isEmpty
			if (userInitiated && awaitsDeploy) pendingUserInitiated = true
			maybeStartBuildLocked(events)
		}
		return awaitsDeploy
	}

	/**
	 * A Quick Build tap landed while a REAL build was already in flight. That build deploys
	 * anyway, so it satisfies the tap's build - but not its ask: without this the tap would
	 * be dropped outright and a user who tapped during a save-triggered build would see
	 * nothing happen. Marks the in-flight build as the tap's answer instead of forcing a
	 * second full rebuild behind a build that was about to do the same work.
	 *
	 * @return false when there is nothing to mark - no build in flight (it finished between
	 *   the decision and this call), or the in-flight build is the background seed, which
	 *   deploys nothing and therefore cannot answer a tap. The caller must then fall back to
	 *   a real request rather than let the tap vanish.
	 */
	suspend fun markInFlightUserInitiated(): Boolean =
		mutex.withLock {
			val flight = inFlight
			if (flight == null || flight.route is BuildRoute.Seed) {
				false
			} else {
				flight.userInitiated = true
				true
			}
		}

	/**
	 * The user tapped the stop button (Bryan's behaviour 5). Abandons the in-flight
	 * incremental build so nothing it produces is deployed or rendered, and returns its
	 * batch to [pending] so the never-lose-pending invariant holds - the next save (or tap)
	 * builds those files again.
	 *
	 * @return true when a build was actually abandoned; false when there was nothing to
	 *   cancel (already finished, or the in-flight build is the background seed, which the
	 *   user never asked for). The caller must not report a cancellation on false.
	 *
	 * Two honest limits, both narrow and both deliberate:
	 * - The compile itself keeps running in the daemon: the daemon protocol has no cancel
	 *   op, and the only real kill is destroying the process - which costs a respawn plus a
	 *   full re-seed for a build the user is done with anyway. Cancelling the coroutine is
	 *   what guarantees nothing DEPLOYS; the abandoned compile finishes unheard, so the
	 *   next build may queue behind it.
	 * - A stop that lands in the same scheduler turn as the deploy can report a cancel for
	 *   a payload the test app already took. Nothing wrong is deployed either way; the
	 *   status line is one generation behind until the next build.
	 */
	suspend fun onCancelRequested(): Boolean {
		var cancelled = false
		mutex.withLock {
			val flight = inFlight ?: return@withLock
			if (flight.route is BuildRoute.Seed) return@withLock
			inFlight = null
			// A stop withdraws the ask, so neither the abandoned build's forced flag nor a tap
			// queued behind it may survive to redeploy on the user's behalf later. Cleared
			// BEFORE the batch goes back, so the returning batch is stamped as the fresh batch
			// it now is.
			pendingForced = false
			pendingUserInitiated = false
			// The batch itself is NOT lost - it goes back so a later save rebuilds it.
			markBatchArrivalLocked()
			pending = flight.batch + pending
			flight.job?.cancel()
			cancelled = true
		}
		if (cancelled) log.info("Quick build cancelled by the user")
		return cancelled
	}

	/**
	 * Background IC seed (session manager, right after provisioning goes live): build the
	 * daemon's incremental universe now so the first save doesn't pay the compiler
	 * warm-up. Lowest priority by construction: any real pending work (or a save that
	 * lands before the seed starts) makes the seed redundant - the daemon's first real
	 * build compiles the full source set anyway - so it is silently dropped, never queued
	 * behind user work.
	 */
	suspend fun onSeedRequested() {
		withEvents { events ->
			// A build already in flight makes the seed moot (the daemon's first real
			// build compiles the full source set) - drop it now rather than queue it
			// behind work it can only duplicate.
			if (inFlight != null) return@withEvents
			pendingSeed = true
			maybeStartBuildLocked(events)
		}
	}

	/**
	 * A fresh daemon process replaced a dead one (crash, trim-memory teardown, or a
	 * deliberate restart). Its IC universe is empty, but the WATCHER never stopped, so
	 * the pending set is still trustworthy. With nothing pending, a [BuildRoute.Seed]
	 * re-warms the new daemon without deploying - the test app already runs the last
	 * deployed generation, and a deploy would restart it for no visible change (the
	 * pre-seed recovery did exactly that). With real work pending (or a superseded
	 * in-flight build whose batch is about to union back), the whole baseline is marked
	 * dirty instead: the next build recompiles everything AND deploys, as before.
	 */
	suspend fun onDaemonReplaced() {
		withEvents { events ->
			if (inFlight == null && pending.isEmpty && !pendingForced) {
				pendingSeed = true
			} else {
				markBatchArrivalLocked()
				pending = pending + ChangedFiles.Unknown
			}
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
			markBatchArrivalLocked()
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

	/**
	 * Stamps [pendingSince] iff this is the first signal of a new batch (nothing was
	 * queued). A signal landing on an already-non-empty batch keeps the earlier stamp, so
	 * a coalesced build's t0 is its EARLIEST change — the latency the user actually waits.
	 */
	private fun markBatchArrivalLocked() {
		if (pending.isEmpty && !pendingForced) pendingSince = now()
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
		if (pending.isEmpty && !pendingForced) {
			if (pendingSeed) startSeedBuildLocked(events)
			return
		}
		// Any real build compiles the daemon's full source set on its first run, so a
		// still-pending seed is redundant the moment user work exists.
		pendingSeed = false

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
		val userInitiated = pendingUserInitiated
		val triggeredAtMillis = pendingSince
		pending = ChangedFiles.Known.EMPTY
		pendingForced = false
		pendingUserInitiated = false
		val buildId = nextBuildId++
		val flight =
			InFlightBuild(buildId, batch, forced, autoFollowUp, route, userInitiated = userInitiated)
		inFlight = flight
		events += OrchestratorEvent.BuildStarted(buildId, route, batch)

		val request =
			BuildRequest(
				buildId = buildId,
				changes = batch,
				route = route,
				forced = forced,
				triggeredAtMillis = triggeredAtMillis,
			)
		// Assigned while still holding the lock, so a cancel can never see a null handle for
		// a build that is already running. Safe from here: nothing suspends in between, and
		// the launched coroutine cannot run before this frame yields.
		flight.job = launchBuild(buildId, request)
	}

	/**
	 * The seed's batch is EMPTY (it represents no user changes): a failed seed unions
	 * nothing back into pending, and the next real save recovers naturally because the
	 * daemon's first real build compiles the full source set regardless. The request's
	 * changes are [ChangedFiles.Unknown] so the executor compiles everything.
	 */
	private fun startSeedBuildLocked(events: MutableList<OrchestratorEvent>) {
		pendingSeed = false
		val buildId = nextBuildId++
		val route = BuildRoute.Seed
		val flight =
			InFlightBuild(buildId, ChangedFiles.Known.EMPTY, forced = false, autoFollowUp = false, route = route)
		inFlight = flight
		events += OrchestratorEvent.BuildStarted(buildId, route, ChangedFiles.Known.EMPTY)
		val request =
			BuildRequest(
				buildId = buildId,
				changes = ChangedFiles.Unknown,
				route = route,
				forced = false,
				triggeredAtMillis = now(),
			)
		flight.job = launchBuild(buildId, request)
	}

	private fun launchBuild(
		buildId: Long,
		request: BuildRequest,
	): Job =
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
					events +=
						OrchestratorEvent.BuildSucceeded(
							buildId,
							outcome,
							flight.route,
							userInitiated = flight.userInitiated,
						)
					// Saves that landed mid-build start the coalesced follow-up now.
					maybeStartBuildLocked(events, autoFollowUp = true)
				}

				else -> {
					val newSavesArrivedMidBuild = !pending.isEmpty || pendingForced
					pending = flight.batch + pending
					pendingForced = pendingForced || flight.forced
					// pendingUserInitiated is deliberately NOT re-armed: the tap was already
					// answered, with the failure. The save that fixes the code is not a new
					// ask, so it must not drag the user out of the editor (see the field).

					val diagnostics = (outcome as? BuildOutcome.CompileError)?.diagnostics
					val unchanged =
						flight.autoFollowUp && diagnostics != null && diagnostics == lastCompileDiagnostics
					if (diagnostics != null) {
						lastCompileDiagnostics = diagnostics
					}
					events += OrchestratorEvent.BuildFailed(buildId, outcome, unchanged, flight.route)

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
		/** What the build was for — a [BuildRoute.Seed] success deployed nothing. */
		val route: BuildRoute,
		/**
		 * True when a Quick Build TAP is what this build answers, so the deploy landing is
		 * where the test app should be brought forward (Bryan's behaviour 2). False for a
		 * build a file write triggered (behaviour 3).
		 */
		val userInitiated: Boolean = false,
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
		/** What the build was for — a [BuildRoute.Seed] failure is not user-visible. */
		val route: BuildRoute,
	) : OrchestratorEvent

	/** The changed-set needs a real Gradle build; the session manager owns the fallback. */
	data class InvalidationRequired(
		val reason: InvalidationReason,
	) : OrchestratorEvent
}
