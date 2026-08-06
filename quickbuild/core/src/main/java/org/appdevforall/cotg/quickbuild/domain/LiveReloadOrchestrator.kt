package org.appdevforall.cotg.quickbuild.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.io.File

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
 * re-render. A pipeline failure (not a compile error) that repeats identically escalates to a
 * proxy app rebuild once - see [recordFailureLocked]. A repeating aapt2 rejection does not
 * escalate but is flagged as blocking on [OrchestratorEvent.BuildFailed.relinkStuck], so the
 * host can name the recovery instead of leaving the user to guess - see [blocksEveryBuild].
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
	/** Runs each build; called with at most one request in flight, and never for Gradle routes. */
	private val executor: LiveReloadExecutor,
	/** Routes each pending set. Its [BuildRoute.FullGradleBuild] verdicts are escalated, not run. */
	private val classifier: ChangeClassifier,
	/**
	 * Where builds are launched. Cancelling it abandons an in-flight build without returning its
	 * batch to pending, so prefer [onCancelRequested] for a user stop.
	 */
	private val scope: CoroutineScope,
	/**
	 * Monotonic clock for the e2e timeline's t0, the trigger stamp threaded into each
	 * [BuildRequest]. Wired to `SystemClock.elapsedRealtime` on device so it shares the
	 * executor's timebase; the default suits callers that do not measure timing.
	 */
	private val now: () -> Long = System::currentTimeMillis,
	/**
	 * Receives every event, delivered outside the internal lock on the caller's context, so a
	 * handler may call back in. It must not throw - an exception propagates into the caller.
	 */
	private val onEvent: (OrchestratorEvent) -> Unit,
) {
	private val log = LoggerFactory.getLogger("QB-Orchestrator")

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

	/**
	 * The previous surfaced build's failure, for the repeat-failure escalation. Cleared by any
	 * success, so only a consecutive run of failures counts.
	 */
	private var lastFailure: BuildOutcome? = null

	/** How many surfaced builds in a row have failed with exactly [lastFailure]. */
	private var identicalFailures = 0

	/**
	 * Spent once the repeat-failure escalation has asked for a proxy app rebuild, and cleared
	 * only by a success or a completed rebuild.
	 *
	 * This is the loop guard. A failed proxy app rebuild leaves the latch spent, so the next
	 * identical failure escalates nothing: the session degrades to plain build failures rather
	 * than rebuilding on every save forever.
	 */
	private var repeatFailureEscalated = false

	/**
	 * Spent once a repeating aapt2 rejection has been reported as blocking, and cleared by the
	 * same things that clear the failure tally - a success or a fresh baseline.
	 *
	 * One report per streak, because the message asks the user to do something: repeating it on
	 * every save would train them to dismiss it.
	 */
	private var relinkStuckReported = false

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

	/**
	 * A watcher/editor save event. [ChangedFiles.Unknown] forces a full recompile.
	 *
	 * @param changes the coalesced batch; it is unioned onto whatever is already pending, so a
	 *   save landing mid-build is never lost.
	 */
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
	 * Abandons the in-flight build on a stop tap, so nothing it produces is deployed or rendered, and
	 * returns its batch to [pending] for the next save or tap to rebuild.
	 *
	 * Two limits: the daemon has no cancel op, so the compile runs to completion unheard and may
	 * delay the next build; and a stop in the deploy's own scheduler turn can report a cancel for a
	 * payload the proxy app already took, leaving the status line one generation behind.
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
	 * deliberate restart). Its caches are empty, but the watcher never stopped, so the pending set
	 * is still trustworthy.
	 *
	 * With nothing pending it re-warms the daemon without deploying - the proxy app already runs the
	 * last generation. With work pending the whole baseline goes dirty and the next build deploys.
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
	 * Hands the pending set over to a full Gradle proxy app rebuild the session manager just started.
	 *
	 * Everything pending, plus any in-flight build's batch (those files are on disk, so Gradle reads
	 * them), is marked absorbed-in-progress, and the in-flight build is superseded so its late result
	 * is discarded. Saves arriving after this call count as not absorbed, since Gradle may already
	 * have read those files.
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
			// A fresh baseline is a genuinely new situation, so a later stuck relink gets its
			// own escalation. Deliberately NOT cleared by onProxyAppRebuildFailed, which is
			// what keeps a failing rebuild from being re-requested.
			clearFailureTallyLocked()
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

	/**
	 * Starts a build when one can run now: nothing in flight, no Gradle rebuild, work to do.
	 *
	 * @param events sink for events to emit once the lock is released; this call appends a
	 *   BuildStarted or an InvalidationRequired, or nothing when no build can start.
	 * @param autoFollowUp true when chaining off a build that just finished rather than off a
	 *   user save, which is what lets a repeat failure be reported as diagnostics-unchanged.
	 */
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
	 *
	 * @param events sink for the one BuildStarted this always appends, drained after the lock.
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

	/**
	 * Reports one build's outcome and either follows it up or returns its batch to pending.
	 *
	 * @param buildId which build is reporting; when it no longer matches the in-flight build a
	 *   baseline reset superseded it, and the result is discarded instead of rendered.
	 * @param outcome what the executor returned, or a synthesized InfrastructureFailure when it
	 *   threw instead of reporting one.
	 */
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
					clearFailureTallyLocked()
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
					val relinkStuck =
						flight.route !is BuildRoute.WarmCompile &&
							diagnostics != null &&
							diagnostics == lastCompileDiagnostics &&
							!relinkStuckReported &&
							blocksEveryBuild(diagnostics)
					if (relinkStuck) relinkStuckReported = true
					// A warm compile's failure is never surfaced, so priming
					// lastCompileDiagnostics from it would make the next real build's identical
					// failure report diagnosticsUnchanged for an error the user never saw.
					if (diagnostics != null && flight.route !is BuildRoute.WarmCompile) {
						lastCompileDiagnostics = diagnostics
					}
					events +=
						OrchestratorEvent.BuildFailed(buildId, outcome, unchanged, flight.route, relinkStuck)

					if (recordFailureLocked(flight.route, outcome)) {
						// The live reload path has proved it cannot clear this on its own, and
						// the batch has just been returned to pending - so every later save
						// would re-fail identically. Hand the pending set to Gradle instead.
						// Starting no follow-up build here: the session manager is about to
						// call onProxyAppRebuildStarted, and a build launched now would only
						// be superseded. invalidationReported keeps a classifier verdict
						// landing in the same window from launching a second rebuild over it.
						repeatFailureEscalated = true
						identicalFailures = 0
						invalidationReported = true
						log.warn(
							"Quick build #{} failed identically twice running ({}); escalating to a proxy app rebuild",
							buildId,
							outcome,
						)
						events +=
							OrchestratorEvent.InvalidationRequired(InvalidationReason.RELOAD_PIPELINE_FAILED)
					} else if (newSavesArrivedMidBuild) {
						// A mid-build save may be the fix; rebuild from the accumulated set.
						maybeStartBuildLocked(events, autoFollowUp = true)
					}
				}
			}
		}
	}

	/**
	 * Tallies one failed build and says whether the live reload path has proved it cannot
	 * recover from this failure on its own.
	 *
	 * Only a failure that is NOT the user's own code counts:
	 * - A compile error - kotlinc's or aapt2's - is the user's to fix, and its diagnostics are
	 *   already on screen. Gradle would reject it just as surely, ~200s slower, and a proxy app
	 *   rebuild that fails drops the whole session to Idle - so auto-escalating a resource typo
	 *   would be a worse defect than the stuck state it was meant to clear.
	 * - A daemon death has its own respawn recovery ([onDaemonReplaced]).
	 * - [BuildOutcome.RequiresProxyAppRebuild] already escalates by itself.
	 *
	 * What is left is the pipeline failing for a reason no edit can reach - a relink that
	 * cannot resolve the baseline's library resource snapshot, a broken tool invocation. The
	 * same failure twice running is the evidence: the second build ran against whatever the
	 * user changed in between and failed identically anyway.
	 *
	 * @param route the failed build's route; a warm compile is never surfaced, so it never
	 *   escalates and never contributes to the tally.
	 * @param outcome how the build failed; compared whole, so any difference restarts the tally.
	 * @return true when this failure should escalate to a proxy app rebuild - at most once,
	 *   until a success or a completed rebuild clears the latch.
	 */
	private fun recordFailureLocked(
		route: BuildRoute,
		outcome: BuildOutcome,
	): Boolean {
		if (route is BuildRoute.WarmCompile) return false
		identicalFailures = if (outcome == lastFailure) identicalFailures + 1 else 1
		lastFailure = outcome
		val pipelineFault = outcome is BuildOutcome.InfrastructureFailure && !outcome.daemonDied
		return pipelineFault &&
			identicalFailures >= ESCALATE_AFTER_IDENTICAL_FAILURES &&
			!repeatFailureEscalated
	}

	/**
	 * Whether these diagnostics will fail every later build until the user fixes them, whatever
	 * they save next - the "stuck relink" shape.
	 *
	 * True only for an aapt2 rejection, recognised by every error naming a resource file. That
	 * is sound because the relink links the project's whole `res/` tree from disk rather than
	 * the changed set: once a resource is unlinkable, the pending set is irrelevant and even a
	 * pure-code save takes the relink path and fails identically. It is also why a *kotlinc*
	 * error is excluded despite persisting the same way - the file the user is editing is the
	 * file the error names, so nothing about it is surprising, and there is no case where no
	 * edit could fix it.
	 *
	 * @param diagnostics the failed build's diagnostics, warnings included.
	 * @return true when there is at least one error and every error names a resource path.
	 */
	private fun blocksEveryBuild(diagnostics: List<BuildDiagnostic>): Boolean {
		val errors = diagnostics.filter { it.severity == BuildDiagnostic.Severity.ERROR }
		return errors.isNotEmpty() &&
			errors.all { it.file != null && ChangeClassifier.namesResource(File(it.file)) }
	}

	/** Forgets the failure streak and re-arms the escalation; the pipeline works again. */
	private fun clearFailureTallyLocked() {
		lastFailure = null
		identicalFailures = 0
		repeatFailureEscalated = false
		relinkStuckReported = false
	}

	private companion object {
		/**
		 * How many identical consecutive pipeline failures escalate to a proxy app rebuild.
		 * Two, so a one-off (a dropped RPC, a transient IO error) costs a retry rather than a
		 * full Gradle build.
		 */
		const val ESCALATE_AFTER_IDENTICAL_FAILURES = 2
	}
}

/** What the orchestrator tells its host about a build. */
sealed interface OrchestratorEvent {
	/**
	 * A build just started; [changes] is the batch it took.
	 *
	 * @property buildId identifies this build in the later succeeded/failed event.
	 * @property route what the classifier decided, which says which steps will run.
	 * @property changes the batch moved out of pending into this build. A warm compile reports
	 *   [ChangedFiles.Unknown] here even though it carries no user changes.
	 */
	data class BuildStarted(
		val buildId: Long,
		val route: BuildRoute,
		val changes: ChangedFiles,
	) : OrchestratorEvent

	/**
	 * A build deployed successfully.
	 *
	 * @property buildId the id of the [BuildStarted] this closes.
	 * @property result the executor's outcome, carrying the generation now live.
	 */
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
	 * @property buildId the id of the [BuildStarted] this closes.
	 * @property outcome how it failed; the batch has already returned to pending, so the next
	 *   save rebuilds it.
	 * @property diagnosticsUnchanged true when this was an automatic follow-up that failed
	 *   with exactly the diagnostics of the build it followed, so the status surface can keep
	 *   its existing rendering. Contract only - no production consumer reads it yet.
	 * @property relinkStuck true when a repeating aapt2 rejection is now blocking every build,
	 *   whatever the user saves - the host should say so, once per streak. Set at most once
	 *   until a success or a fresh baseline; see `LiveReloadOrchestrator.blocksEveryBuild`.
	 */
	data class BuildFailed(
		val buildId: Long,
		val outcome: BuildOutcome,
		val diagnosticsUnchanged: Boolean = false,
		/** What the build was for - a [BuildRoute.WarmCompile] failure is not user-visible. */
		val route: BuildRoute,
		val relinkStuck: Boolean = false,
	) : OrchestratorEvent

	/**
	 * The changed-set needs a real Gradle build; the session manager owns the fallback.
	 *
	 * @property reason why the live reload path cannot absorb it. Emitted once per pending set,
	 *   so a second save of the same kind does not re-report it.
	 */
	data class InvalidationRequired(
		val reason: InvalidationReason,
	) : OrchestratorEvent
}
