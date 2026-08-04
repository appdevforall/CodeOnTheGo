package org.appdevforall.cotg.quickbuild.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

/**
 * Schedules quick builds: at most one in flight, with everything else coalesced into a
 * pending changed-set that is never lost. Pure JVM, so the model is unit-testable off-device.
 *
 * It runs the live-reload path only. A [BuildRoute.FullGradleBuild] verdict is escalated as
 * [OrchestratorEvent.InvalidationRequired] and never executed here - the session manager owns
 * Gradle, the install prompts, and the device's single Gradle slot.
 *
 * What "never lost" means:
 * - Saves landing mid-build coalesce into the pending set.
 * - Starting a build MOVES the pending set into it; it clears only on success, and a failed
 *   batch is unioned back.
 * - An empty known changed-set is not [ChangedFiles.Unknown]: no-op saves never recompile.
 * - New work never cancels a running compile; it waits and coalesces. Only
 *   [onCancelRequested] abandons a build, and even then the batch returns to pending.
 * - Every result carries its build id, so a superseded build's result is discarded.
 *
 * After a FAILED build it rebuilds immediately only if new saves arrived mid-build, since
 * they may carry the fix; retrying an unchanged batch would fail identically, so it waits for
 * the next save. A follow-up failing with identical diagnostics sets
 * [OrchestratorEvent.BuildFailed.diagnosticsUnchanged] so the status surface can skip a
 * re-render.
 *
 * Proxy-app-rebuild protocol: the session manager calls [onProxyAppRebuildStarted] when it
 * kicks off Gradle, then [onBaselineReset] or [onProxyAppRebuildFailed]. Only changes that
 * existed at the start count as absorbed; a save landing mid-rebuild stays pending.
 *
 * Threading: [onEvent] is delivered outside the internal lock on the caller's context, so
 * handlers may call back in. Event ORDER holds only when the public API and [scope] share a
 * single-threaded dispatcher - wire it that way.
 */
class LiveReloadOrchestrator(
	private val executor: LiveReloadExecutor,
	private val classifier: ChangeClassifier,
	private val scope: CoroutineScope,
	/**
	 * Monotonic clock for the e2e timeline's t0, the trigger stamp threaded into each
	 * [BuildRequest]. Wired to `SystemClock.elapsedRealtime` on device so it shares the
	 * executor's timebase; the default suits callers that do not measure timing.
	 */
	private val now: () -> Long = System::currentTimeMillis,
	private val onEvent: (OrchestratorEvent) -> Unit,
) {
	private val log = LoggerFactory.getLogger(LiveReloadOrchestrator::class.java)

	private val mutex = Mutex()
	private var pending: ChangedFiles = ChangedFiles.Known.EMPTY
	private var pendingForced = false

	/**
	 * Set by a Quick Build tap and nothing else, because it decides whether the user is
	 * pulled out of the editor into the proxy app.
	 *
	 * Kept apart from [pendingForced], which the reconnect catch-up also sets and a failed
	 * build re-arms. A failed build does NOT re-arm this one: the tap was already answered,
	 * with an error, and the save that fixes the code is not a new ask.
	 */
	private var pendingUserInitiated = false
	private var inFlight: InFlightBuild? = null
	private var nextBuildId = 1L
	private var invalidationReported = false

	/**
	 * When the current pending batch began accumulating - t0 for the build it becomes. Set
	 * only when a batch starts, so coalescing keeps the earliest change's stamp; moving the
	 * batch into a build empties pending, which re-stamps on the next arrival.
	 */
	private var pendingSince: Long = 0L

	/** Changes a running Gradle proxy app rebuild will absorb; restored if it fails. */
	private var awaitingAbsorption: ChangedFiles? = null

	/** Diagnostics of the last CompileError, for the duplicate-follow-up guard. */
	private var lastCompileDiagnostics: List<BuildDiagnostic>? = null

	/** Requested background warm compile (post-provisioning); dropped once any real build runs. */
	private var pendingWarmCompile = false

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
		/** Cancellation handle for [onCancelRequested]; null for a warm compile (never cancelled). */
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
	 * An explicit Quick Build tap: build now even if nothing changed. A failed forced build
	 * re-arms the flag, so the eventual retry is forced too.
	 *
	 * @param userInitiated whether a human asked. Only a tap should pass true; the reconnect
	 *   catch-up must pass false or it would drag the user into the proxy app unprompted.
	 * @return true when real changed files are pending, so the caller should wait for the
	 *   deploy. False means a pure forced redeploy, which the caller may act on immediately
	 *   rather than after a full recompile.
	 */
	suspend fun onLiveReloadRequested(userInitiated: Boolean = true): Boolean {
		var awaitsDeploy = false
		withEvents { events ->
			markBatchArrivalLocked()
			pendingForced = true
			// Only a tap with real work to wait for arms the on-deploy switch. With nothing
			// pending the caller answers the tap itself, so arming it here as well would
			// foreground the proxy app a second time when the redeploy landed.
			awaitsDeploy = !pending.isEmpty
			if (userInitiated && awaitsDeploy) pendingUserInitiated = true
			maybeStartBuildLocked(events)
		}
		return awaitsDeploy
	}

	/**
	 * Makes the in-flight build the answer to a Quick Build tap that landed while it was
	 * already running, instead of queueing a second build behind the same work.
	 *
	 * @return false when there is nothing to mark - no build in flight, or the in-flight
	 *   build is the warm compile, which deploys nothing and so cannot answer a tap. The
	 *   caller must then issue a real request rather than let the tap vanish.
	 */
	suspend fun markInFlightUserInitiated(): Boolean =
		mutex.withLock {
			val flight = inFlight
			if (flight == null || flight.route is BuildRoute.WarmCompile) {
				false
			} else {
				flight.userInitiated = true
				true
			}
		}

	/**
	 * Abandons the in-flight build on a stop tap, so nothing it produces is deployed or
	 * rendered, and returns its batch to [pending] for the next save or tap to rebuild.
	 *
	 * Two limits: the daemon protocol has no cancel op, so the compile itself runs to
	 * completion unheard and the next build may queue behind it; and a stop landing in the
	 * same scheduler turn as the deploy can report a cancel for a payload the proxy app
	 * already took, leaving the status line one generation behind.
	 *
	 * @return true when a build was abandoned; false when there was nothing to cancel, or the
	 *   in-flight build is the warm compile, which the user never asked for. The caller must
	 *   not report a cancellation on false.
	 */
	suspend fun onCancelRequested(): Boolean {
		var cancelled = false
		mutex.withLock {
			val flight = inFlight ?: return@withLock
			if (flight.route is BuildRoute.WarmCompile) return@withLock
			inFlight = null
			// A stop withdraws the ask, so neither the abandoned build's forced flag nor a tap
			// queued behind it may survive to redeploy later. Cleared before the batch goes
			// back, so the returning batch is stamped as the fresh batch it now is.
			pendingForced = false
			pendingUserInitiated = false
			markBatchArrivalLocked()
			pending = flight.batch + pending
			flight.job?.cancel()
			cancelled = true
		}
		if (cancelled) log.info("Quick build cancelled by the user")
		return cancelled
	}

	/**
	 * Requests a background warm compile, called by the session manager once provisioning
	 * goes live, so the first save does not pay the compiler warm-up.
	 *
	 * Lowest priority by construction: any real work makes it redundant, since the daemon's
	 * first real build compiles the full source set anyway, so it is dropped rather than
	 * queued behind user work.
	 */
	suspend fun onWarmCompileRequested() {
		withEvents { events ->
			if (inFlight != null) return@withEvents
			pendingWarmCompile = true
			maybeStartBuildLocked(events)
		}
	}

	/**
	 * Recovers from a fresh daemon process replacing a dead one (crash, trim-memory teardown,
	 * deliberate restart). Its incremental caches are empty, but the watcher never stopped,
	 * so the pending set is still trustworthy.
	 *
	 * With nothing pending it re-warms the new daemon without deploying, since the proxy app
	 * already runs the last deployed generation. With real work pending it marks the whole
	 * baseline dirty, so the next build recompiles everything and deploys.
	 */
	suspend fun onDaemonReplaced() {
		withEvents { events ->
			if (inFlight == null && pending.isEmpty && !pendingForced) {
				pendingWarmCompile = true
			} else {
				markBatchArrivalLocked()
				pending = pending + ChangedFiles.Unknown
			}
			maybeStartBuildLocked(events)
		}
	}

	/**
	 * Marks the whole baseline dirty after an external full Gradle build (a Standard Run)
	 * moved generated inputs and classpath jars under `build/`, which the watcher cannot see.
	 *
	 * Starts no build of its own: the next save or tap recompiles everything from current
	 * disk, so the hand-back can never serve code compiled against the old baseline.
	 */
	suspend fun onBaselineUntrusted() {
		mutex.withLock {
			markBatchArrivalLocked()
			pending = pending + ChangedFiles.Unknown
		}
	}

	/**
	 * Hands the pending set over to a full Gradle proxy app rebuild the session manager just
	 * started.
	 *
	 * Everything pending, plus any in-flight build's batch (those files are on disk, so
	 * Gradle reads them), is marked absorbed-in-progress, and the in-flight build is
	 * superseded so its late result is discarded. Saves arriving after this call count as not
	 * absorbed, since Gradle may already have read those files.
	 */
	suspend fun onProxyAppRebuildStarted() {
		mutex.withLock {
			awaitingAbsorption = (inFlight?.batch ?: ChangedFiles.Known.EMPTY) + pending
			pending = ChangedFiles.Known.EMPTY
			pendingForced = false
			inFlight = null
		}
	}

	/**
	 * Completes a proxy app rebuild: drops the absorbed changes and immediately builds
	 * anything that arrived mid-rebuild.
	 *
	 * Calling this without [onProxyAppRebuildStarted] is a protocol violation - the fallback
	 * drops everything pending, which risks a stale proxy app, hence the warning.
	 */
	suspend fun onBaselineReset() {
		withEvents { events ->
			if (awaitingAbsorption == null) {
				log.warn("onBaselineReset without onProxyAppRebuildStarted; dropping pending set")
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
	 * Returns the held batch to pending after a failed proxy app rebuild - nothing was
	 * absorbed.
	 *
	 * Emits no event: re-reporting invalidation would loop the failing fallback, so the next
	 * save re-triggers it once the user has fixed the problem.
	 */
	suspend fun onProxyAppRebuildFailed() {
		mutex.withLock {
			awaitingAbsorption?.let { held ->
				pending = held + pending
			}
			awaitingAbsorption = null
			invalidationReported = false
		}
	}

	/**
	 * Stamps [pendingSince] only on the first signal of a new batch, so a coalesced build's
	 * t0 is its earliest change - the latency the user actually waits.
	 */
	private fun markBatchArrivalLocked() {
		if (pending.isEmpty && !pendingForced) pendingSince = now()
	}

	private suspend inline fun withEvents(block: (MutableList<OrchestratorEvent>) -> Unit) {
		val events = mutableListOf<OrchestratorEvent>()
		mutex.withLock { block(events) }
		events.forEach(onEvent)
	}

	/** Starts a build when one can run now: nothing in flight, no Gradle rebuild, work to do. */
	private fun maybeStartBuildLocked(
		events: MutableList<OrchestratorEvent>,
		autoFollowUp: Boolean = false,
	) {
		if (inFlight != null) return
		// Quick builds are suspended while a proxy app rebuild runs, or they would race
		// Gradle against a half-reset baseline. Saves accumulate and build on onBaselineReset.
		if (awaitingAbsorption != null) return
		if (pending.isEmpty && !pendingForced) {
			if (pendingWarmCompile) startWarmCompileLocked(events)
			return
		}
		// Real work makes a still-pending warm compile redundant, because a code-bearing route
		// compiles the full source set on its first run. A resources-only route does not
		// actually warm the compiler, so clearing the flag here costs that project one cold
		// compile on a later save - a missed optimization, not a correctness problem.
		pendingWarmCompile = false

		val route = classifier.classify(pending)
		if (route is BuildRoute.FullGradleBuild) {
			// The live reload path can't absorb this; hand off to the session manager once.
			// Pending is kept: it documents what the proxy app rebuild will absorb.
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
		// Assigned while still holding the lock, so a cancel can never see a null handle for a
		// build that is already running. Nothing suspends in between, and the launched
		// coroutine cannot run before this frame yields.
		flight.job = launchBuild(buildId, request)
	}

	/**
	 * Starts the background warm compile.
	 *
	 * Its batch is empty because it represents no user changes, so a failed warm compile
	 * unions nothing back into pending; the request's changes are [ChangedFiles.Unknown] so
	 * the executor still compiles everything.
	 */
	private fun startWarmCompileLocked(events: MutableList<OrchestratorEvent>) {
		pendingWarmCompile = false
		val buildId = nextBuildId++
		val route = BuildRoute.WarmCompile
		val flight =
			InFlightBuild(buildId, ChangedFiles.Known.EMPTY, forced = false, autoFollowUp = false, route = route)
		inFlight = flight
		// The EVENT batch is Unknown, matching the request below, so a metrics sink reports
		// "unknown size" rather than zero files. It deliberately diverges from the flight's
		// empty batch above - don't assume the two match for a warm compile.
		events += OrchestratorEvent.BuildStarted(buildId, route, ChangedFiles.Unknown)
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

	/** Reports one build's outcome and either follows it up or returns its batch to pending. */
	private suspend fun onBuildFinished(
		buildId: Long,
		outcome: BuildOutcome,
	) {
		withEvents { events ->
			val flight = inFlight
			if (flight == null || flight.buildId != buildId) {
				// Superseded (a baseline reset raced this build) - discard, never render.
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
					// A warm compile's failure is never surfaced, so priming
					// lastCompileDiagnostics from it would make the next real build's identical
					// failure report diagnosticsUnchanged for an error the user never saw.
					if (diagnostics != null && flight.route !is BuildRoute.WarmCompile) {
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

/** What the orchestrator tells its host about a build. */
sealed interface OrchestratorEvent {
	/** A build just started; [changes] is the batch it took. */
	data class BuildStarted(
		val buildId: Long,
		val route: BuildRoute,
		val changes: ChangedFiles,
	) : OrchestratorEvent

	/** A build deployed successfully. */
	data class BuildSucceeded(
		val buildId: Long,
		val result: BuildOutcome.Success,
		/** What the build was for - a [BuildRoute.WarmCompile] success deployed nothing. */
		val route: BuildRoute,
		/**
		 * True when this build answers a Quick Build tap, so the proxy app should be brought
		 * forward as the deploy lands. False for a build a file write triggered.
		 */
		val userInitiated: Boolean = false,
	) : OrchestratorEvent

	/**
	 * A build did not deploy.
	 *
	 * @property diagnosticsUnchanged true when this was an automatic follow-up that failed
	 *   with exactly the diagnostics of the build it followed, so the status surface can keep
	 *   its existing rendering. Contract only - no production consumer reads it yet.
	 */
	data class BuildFailed(
		val buildId: Long,
		val outcome: BuildOutcome,
		val diagnosticsUnchanged: Boolean = false,
		/** What the build was for - a [BuildRoute.WarmCompile] failure is not user-visible. */
		val route: BuildRoute,
	) : OrchestratorEvent

	/** The changed-set needs a real Gradle build; the session manager owns the fallback. */
	data class InvalidationRequired(
		val reason: InvalidationReason,
	) : OrchestratorEvent
}
