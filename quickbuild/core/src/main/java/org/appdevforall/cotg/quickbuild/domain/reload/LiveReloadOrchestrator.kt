package org.appdevforall.cotg.quickbuild.domain.reload

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.appdevforall.cotg.quickbuild.domain.classify.BuildRoute
import org.appdevforall.cotg.quickbuild.domain.classify.ChangeClassifier
import org.appdevforall.cotg.quickbuild.domain.classify.InvalidationReason
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Schedules quick builds: at most one in flight, everything else coalesced into a pending set that
 * is never lost - a failed build's batch is unioned back, and a Gradle verdict outlives the paths
 * that proved it ([stickyInvalidation]). Runs the live-reload path only, escalating anything that
 * needs Gradle as [OrchestratorEvent.InvalidationRequired]. Event ORDER holds only when the public
 * API and [scope] share a single-threaded dispatcher - wire it that way.
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
	 * Monotonic clock for the e2e timeline's t0, wired to `SystemClock.elapsedRealtime` on device
	 * so it shares the executor's timebase.
	 */
	private val now: () -> Long = System::currentTimeMillis,
	/**
	 * Wall clock for the mid-rebuild echo split, epoch millis so it shares a timebase with
	 * [fileLastModified] - deliberately separate from [now], which is elapsedRealtime on device
	 * and compares to no file mtime.
	 */
	private val wallClock: () -> Long = System::currentTimeMillis,
	/** Reads a file's mtime (epoch millis, 0 when missing or unreadable); injectable for tests. */
	private val fileLastModified: (File) -> Long = File::lastModified,
	/**
	 * Receives every event, delivered outside the internal lock on the caller's context so a
	 * handler may call back in; it must not throw, as an exception propagates into the caller.
	 */
	private val onEvent: (OrchestratorEvent) -> Unit,
) {
	private val log = LoggerFactory.getLogger("QB-Orchestrator")

	private val mutex = Mutex()
	private var pending: ChangedFiles = ChangedFiles.Known.EMPTY
	private var pendingForced = false

	/**
	 * Set by a Quick Build tap and nothing else, because it decides whether the user is pulled out
	 * of the editor into the proxy app - unlike [pendingForced], which the reconnect catch-up also
	 * sets and a failed build re-arms. A failed build does NOT re-arm this one: the tap was already
	 * answered, with an error. It never outlives the pending set it asked about, or a later
	 * automatic save would be reported as something the user asked for.
	 */
	private var pendingUserInitiated = false

	/**
	 * A user tap whose save-all wrote something, waiting for the watcher batch those writes will
	 * produce. Consumed by the first non-empty batch (which then carries the ask as
	 * [pendingUserInitiated]) or by [consumeUnansweredTap]'s deadline, whichever comes first -
	 * never both, so the tap is answered exactly once. Cleared wherever [pendingUserInitiated]
	 * is force-cleared, for the same reason: the ask must not outlive the work it was about.
	 */
	private var tapAwaitingChanges = false
	private var inFlight: InFlightBuild? = null
	private var nextBuildId = 1L
	private var invalidationReported = false

	/**
	 * A Gradle verdict the enumerated part of the pending set already demanded, latched before a
	 * [ChangedFiles.Unknown] collapse erased the paths that proved it.
	 *
	 * [ChangedFiles.Unknown] classifies as the FAST daemon path, so a pending `AndroidManifest.xml`
	 * edit plus a daemon replacement would otherwise compile, relink, deploy and report success
	 * with the manifest change never absorbed. Cleared only by [onBaselineReset], the build that
	 * really absorbs it.
	 */
	private var stickyInvalidation: InvalidationReason? = null

	/**
	 * When the current pending batch began WAITING for a build - t0 for the build it becomes; null
	 * means nothing is waiting, so the next arrival stamps it and a later one coalescing in keeps
	 * the earliest stamp. Null rather than "pending is empty" because a failed build's returned
	 * batch sits in [pending] waiting on the user, not queueing - charging that think-and-fix time
	 * to the next build reported a 2.25s save as 197.3s (T16).
	 */
	private var pendingSince: Long? = null

	/** Changes a running Gradle proxy app rebuild will absorb; restored if it fails. */
	private var awaitingAbsorption: ChangedFiles? = null

	/**
	 * When the running proxy app rebuild started, epoch millis from [wallClock], for the echo
	 * split in [absorbEchoesLocked]; meaningful only while [awaitingAbsorption] is non-null.
	 */
	private var absorptionStartedAtMillis = 0L

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

	/**
	 * Spent once "the proxy app will not stay up" has been reported, and cleared by the same
	 * things that clear the failure tally.
	 *
	 * One report per streak: the message asks the user to restart the session, so repeating it
	 * on every save would train them to dismiss it.
	 */
	private var proxyAppWontStayUpReported = false

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
		/**
		 * Cancellation handle. [onCancelRequested] leaves a warm compile alone, since the user
		 * never asked for it; a proxy app rebuild supersedes and cancels any route.
		 */
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
			val remainder = absorbEchoesLocked(changes)
			// A batch the rebuild fully absorbed queues nothing, so it must not stamp the
			// queue clock - the rebuild's minutes are not the next build's wait.
			if (awaitingAbsorption != null && remainder.isEmpty) return@withEvents
			markBatchArrivalLocked()
			pending = unionPendingLocked(pending, remainder)
			if (tapAwaitingChanges && !pending.isEmpty) {
				// The batch the tap's save-all promised has arrived; the build it produces
				// answers the tap, so its deploy may bring the proxy app forward.
				tapAwaitingChanges = false
				pendingUserInitiated = true
			}
			maybeStartBuildLocked(events)
		}
	}

	/**
	 * An explicit Quick Build tap, or the reconnect catch-up: decide how the ask is answered.
	 *
	 * A user tap never forces a blind rebuild (the F7 echo fix): with work already pending it
	 * starts a correctly-routed build whose deploy answers the tap; with nothing pending and
	 * [expectChanges] set it arms the tap on the watcher batch the save-all's writes will
	 * deliver; with nothing pending and nothing written the caller switches immediately - the
	 * deployed app is already current. Only the non-user reconnect catch-up still forces
	 * ([BuildRequest.forced]): the app is provably behind and there is no changed-set to route,
	 * and a failed forced build re-arms the flag so the eventual retry is forced too.
	 *
	 * @param userInitiated whether a human asked - only a tap passes true, since the reconnect
	 *   catch-up would otherwise drag the user into the proxy app unprompted.
	 * @param expectChanges tap-only: the tap's save-all wrote at least one file, so a watcher
	 *   batch is expected within the coalescer window.
	 * @return how the ask gets answered; see [LiveReloadRequestOutcome].
	 */
	suspend fun onLiveReloadRequested(
		userInitiated: Boolean = true,
		expectChanges: Boolean = false,
	): LiveReloadRequestOutcome {
		var outcome = LiveReloadRequestOutcome.SWITCH_NOW
		withEvents { events ->
			when {
				!userInitiated -> {
					// Reconnect catch-up: the app runs an old generation and no changed-set
					// names why, so only a forced blind rebuild repairs it.
					markBatchArrivalLocked()
					pendingForced = true
					outcome = LiveReloadRequestOutcome.AWAITS_DEPLOY
					maybeStartBuildLocked(events)
				}

				!pending.isEmpty -> {
					// Accumulated work: build it now, routed by the classifier as any save
					// would be; the deploy answers the tap.
					markBatchArrivalLocked()
					pendingUserInitiated = true
					outcome = LiveReloadRequestOutcome.AWAITS_DEPLOY
					maybeStartBuildLocked(events)
				}

				expectChanges -> {
					// The tap's save-all wrote something, so its watcher batch is already on
					// the way (the coalescer emits within 250 ms of the last event). Arm the
					// tap on that batch instead of building an empty set behind it; the
					// caller runs the deadline fallback for the case where every written
					// file was watcher-irrelevant and no batch ever comes. Deliberately no
					// queue-clock stamp: if no batch comes, a stamp here would charge the
					// dead wait to the next unrelated save's build (the T16 shape).
					tapAwaitingChanges = true
					outcome = LiveReloadRequestOutcome.AWAITS_CHANGES
				}

				else -> {
					// Nothing written and nothing pending: the deployed app is current, so
					// the tap is answered by switching to it and no build runs at all.
					outcome = LiveReloadRequestOutcome.SWITCH_NOW
				}
			}
		}
		return outcome
	}

	/**
	 * Disarms a tap still waiting for its save-all's watcher batch and says whether it was
	 * waiting - the deadline half of the arm-on-batch tap protocol.
	 *
	 * Called by the session manager's fallback timer. True means no batch arrived (the save-all
	 * wrote only watcher-irrelevant files, e.g. a `.md`), so the caller answers the tap by
	 * switching now; false means a batch already consumed the tap and its build's deploy
	 * answers it, so the caller must do nothing - either way, exactly once.
	 */
	suspend fun consumeUnansweredTap(): Boolean =
		mutex.withLock {
			val wasArmed = tapAwaitingChanges
			tapAwaitingChanges = false
			wasArmed
		}

	/**
	 * Makes the in-flight build the answer to a Quick Build tap that landed while it was
	 * already running, instead of queueing a second build behind the same work.
	 *
	 * @return false when there is nothing to mark - no build in flight, or a warm compile, which
	 *   deploys nothing, so the caller must issue a real request rather than let the tap vanish.
	 */
	suspend fun markInFlightUserInitiated(): Boolean =
		mutex.withLock {
			val flight = inFlight
			if (flight == null || flight.route is BuildRoute.WarmCompile) {
				false
			} else {
				flight.userInitiated = true
				// The request already left with userInitiated false, so the executor has to
				// hear about the promotion separately or this build's deploy would still
				// refuse to open a closed app - and the tap would do nothing at all.
				executor.markCurrentBuildUserInitiated()
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
	 * @return true when a build was abandoned; false when there was nothing to cancel or it was a
	 *   warm compile the user never asked for, on which the caller must report no cancellation.
	 */
	suspend fun onCancelRequested(): Boolean {
		var cancelled = false
		mutex.withLock {
			val flight = inFlight ?: return@withLock
			if (flight.route is BuildRoute.WarmCompile) return@withLock
			inFlight = null
			// A stop withdraws the ask, so neither the abandoned build's forced flag nor a tap
			// queued behind it - answered or still armed - may survive to redeploy later.
			pendingForced = false
			pendingUserInitiated = false
			tapAwaitingChanges = false
			// And the abandoned build's t0 goes with it: the returning batch now waits on the
			// user, not on a queue, so the next arrival stamps its own. A mid-build save already
			// owns the clock and keeps it - that save really did queue behind this build.
			if (pending.isEmpty) pendingSince = null
			pending = unionPendingLocked(flight.batch, pending)
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
				pending = unionPendingLocked(pending, ChangedFiles.Unknown)
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
			// Deliberately does not stamp the queue clock: nothing is waiting for a build here,
			// so a clock started now would charge the gap until the user's next save to that
			// save's queue. Whatever was already queueing keeps its own stamp.
			pending = unionPendingLocked(pending, ChangedFiles.Unknown)
		}
	}

	/**
	 * Hands the pending set over to a full Gradle proxy app rebuild the session manager just started.
	 *
	 * Everything pending, plus any in-flight build's batch (those files are on disk, so Gradle reads
	 * them), is marked absorbed-in-progress and the in-flight build is cancelled. A batch arriving
	 * after this call is split by mtime against the rebuild's start ([absorbEchoesLocked]): files
	 * already on disk when Gradle read the tree are absorbed too, newer ones count as not absorbed.
	 * Unlike a stop, this emits nothing - a rebuild superseded the work rather than the user asking
	 * for a cancellation.
	 */
	suspend fun onProxyAppRebuildStarted() {
		mutex.withLock {
			val superseded = inFlight
			absorptionStartedAtMillis = wallClock()
			awaitingAbsorption = unionPendingLocked(superseded?.batch ?: ChangedFiles.Known.EMPTY, pending)
			pending = ChangedFiles.Known.EMPTY
			// Gradle owns this batch now, and a rebuild runs for minutes. Keeping the clock would
			// charge all of it to whichever build picked the batch back up if the rebuild failed.
			pendingSince = null
			pendingForced = false
			// The tap this recorded asked about the very set Gradle is now absorbing, so that
			// build answers it. Left armed, it would tag some later unrelated save as the user's
			// ask and pull them out of the editor into the proxy app. Same for a tap still
			// waiting on its batch: the rebuild reads the tap's saves off disk anyway.
			pendingUserInitiated = false
			tapAwaitingChanges = false
			inFlight = null
			// Nulling inFlight only discards the late RESULT; the coroutine runs on and would
			// deploy a payload compiled against the old baseline into an app Gradle is
			// reinstalling. State settles first, then the job dies - as in onCancelRequested.
			superseded?.job?.cancel()
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
				val superseded = inFlight
				pending = ChangedFiles.Known.EMPTY
				pendingSince = null
				pendingForced = false
				// Dropped with the set it asked about; see onProxyAppRebuildStarted.
				pendingUserInitiated = false
				tapAwaitingChanges = false
				inFlight = null
				superseded?.job?.cancel()
			}
			awaitingAbsorption = null
			invalidationReported = false
			// The Gradle build the latch demanded has now run and absorbed the change.
			stickyInvalidation = null
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
				pending = unionPendingLocked(held, pending)
			}
			awaitingAbsorption = null
			invalidationReported = false
			// stickyInvalidation deliberately survives: nothing was absorbed, so the change that
			// demanded Gradle is still unabsorbed and must not fall back to the fast path.
		}
	}

	/**
	 * Splits a batch arriving while a proxy app rebuild is absorbing the pending set.
	 *
	 * A file whose mtime predates the rebuild's start was on disk before Gradle read the tree,
	 * so the rebuild absorbs it - typically the tap's own save echo, whose debounce lands it
	 * just after [onProxyAppRebuildStarted]; stranded in [pending] instead, [onBaselineReset]
	 * would resurface it as a spurious invalidation. Everything else stays pending, because a
	 * real mid-rebuild edit must still build once the baseline lands: files modified after the
	 * start, files with no readable mtime (nothing proves they predate the read), removals (no
	 * mtime left to date them), and [ChangedFiles.Unknown].
	 *
	 * The absorbed part joins [awaitingAbsorption] via [ChangedFiles.plus], NOT
	 * [unionPendingLocked]: this rebuild IS the Gradle build these files would demand, so
	 * latching a sticky verdict from them would re-report the invalidation it is resolving.
	 * A failed rebuild restores them with the rest of the held set ([onProxyAppRebuildFailed]).
	 *
	 * @param changes the arriving batch.
	 * @return what is left for [pending]; [changes] unchanged when no rebuild is running.
	 */
	private fun absorbEchoesLocked(changes: ChangedFiles): ChangedFiles {
		val held = awaitingAbsorption ?: return changes
		if (changes !is ChangedFiles.Known) return changes
		val absorbed =
			changes.files.filterTo(mutableSetOf()) { file ->
				fileLastModified(file) in 1..absorptionStartedAtMillis
			}
		if (absorbed.isEmpty()) return changes
		awaitingAbsorption = held + ChangedFiles.Known(absorbed)
		return ChangedFiles.Known(changes.files - absorbed, changes.removed)
	}

	/**
	 * Stamps [pendingSince] only when nothing is already waiting, so a coalesced build's t0 is
	 * its earliest still-waiting change - the latency the user actually waits.
	 *
	 * Called from the paths that give a build something to wait for. A path that only marks work
	 * stale without making anything queue must not call it; see [onBaselineUntrusted].
	 */
	private fun markBatchArrivalLocked() {
		if (pendingSince == null) pendingSince = now()
	}

	/**
	 * Unions two changed-sets, latching into [stickyInvalidation] any Gradle verdict an
	 * enumerated side already demanded when the result collapses to [ChangedFiles.Unknown].
	 *
	 * Preserving the verdict rather than re-routing Unknown keeps the fast daemon path intact for a
	 * plain Unknown - "recompile everything from current disk", which is what an untrusted baseline
	 * means and why it must not become a full Gradle build.
	 *
	 * @param older the batch already held.
	 * @param newer the arriving batch, whose per-path verdict wins - see [ChangedFiles.plus].
	 * @return the reconciled union, unchanged from [ChangedFiles.plus].
	 */
	private fun unionPendingLocked(
		older: ChangedFiles,
		newer: ChangedFiles,
	): ChangedFiles {
		val union = older + newer
		if (union is ChangedFiles.Unknown) {
			latchInvalidationLocked(older)
			latchInvalidationLocked(newer)
		}
		return union
	}

	/**
	 * Latches [side]'s Gradle verdict, if it has one, so the collapse cannot hide it.
	 *
	 * The first reason latched wins: every [BuildRoute.FullGradleBuild] reason drives the same
	 * proxy app rebuild, so a later one would only change the message.
	 *
	 * @param side one operand of a union that collapsed to Unknown, skipped unless it is a
	 *   non-empty enumerated set, since only those name paths a classifier can read.
	 */
	private fun latchInvalidationLocked(side: ChangedFiles) {
		if (stickyInvalidation != null) return
		if (side !is ChangedFiles.Known || side.isEmpty) return
		val route = classifier.classify(side)
		if (route is BuildRoute.FullGradleBuild) stickyInvalidation = route.reason
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
		val latched = stickyInvalidation
		if (latched == null && pending.isEmpty && !pendingForced) {
			if (pendingWarmCompile) startWarmCompileLocked(events)
			return
		}
		// Real work makes a still-pending warm compile redundant, because a code-bearing route
		// compiles the full source set on its first run. A resources-only route does not
		// actually warm the compiler, so clearing the flag here costs that project one cold
		// compile on a later save - a missed optimization, not a correctness problem.
		pendingWarmCompile = false

		// A latched verdict outranks the pending set's own route: the paths that proved it were
		// erased by an Unknown collapse, so classify() can no longer see them and would pick the
		// fast path for a change the live reload path cannot absorb.
		val route = latched?.let { BuildRoute.FullGradleBuild(it) } ?: classifier.classify(pending)
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
		// A batch with no clock was not queueing - it is a failed build's batch that has been
		// sitting on the user, picked up by a path that starts a build without an arrival of its
		// own. Its t0 is this build's own start, which reports the wait as the zero it was.
		val triggeredAtMillis = pendingSince ?: now()
		pending = ChangedFiles.Known.EMPTY
		pendingSince = null
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
				userInitiated = userInitiated,
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
					// The dead attempt's t0 must not outlive it: its batch is back in pending but
					// waiting on the user, not queueing, which is not latency this loop owes. A
					// save that landed MID-build did genuinely queue behind this one, so its
					// stamp is already the pending clock and wins.
					if (!newSavesArrivedMidBuild) pendingSince = null
					pendingForced = pendingForced || flight.forced
					// pendingUserInitiated is deliberately NOT re-armed: the tap was already
					// answered, with the failure. The save that fixes the code is not a new
					// ask, so it must not drag the user out of the editor (see the field).

					val diagnostics = (outcome as? BuildOutcome.CompileError)?.diagnostics
					val relinkStuck =
						flight.route !is BuildRoute.WarmCompile &&
							diagnostics != null &&
							diagnostics == lastCompileDiagnostics &&
							!relinkStuckReported &&
							blocksEveryBuild(diagnostics)
					if (relinkStuck) relinkStuckReported = true
					// The same shape as relinkStuck, for the deploy half: a second not-connected
					// deploy running proves the proxy app cannot stay up long enough to receive
					// anything. No edit reaches it (the fix compiles fine and has nowhere to
					// land) and "relaunch to reconnect" just restarts the crash, so the only
					// true remedy is a fresh proxy app build.
					val proxyAppWontStayUp =
						flight.route !is BuildRoute.WarmCompile &&
							(outcome as? BuildOutcome.DeployFailure)?.proxyAppNotConnected == true &&
							outcome == lastFailure &&
							!proxyAppWontStayUpReported
					if (proxyAppWontStayUp) proxyAppWontStayUpReported = true
					// A warm compile's failure is never surfaced, so priming
					// lastCompileDiagnostics from it would let the next real build's identical
					// failure count as a repeat of an error the user never saw.
					if (diagnostics != null && flight.route !is BuildRoute.WarmCompile) {
						lastCompileDiagnostics = diagnostics
					}
					events +=
						OrchestratorEvent.BuildFailed(
							buildId,
							outcome,
							flight.route,
							relinkStuck,
							proxyAppWontStayUp,
						)

					if (recordFailureLocked(flight.route, outcome)) {
						// The live reload path cannot clear this on its own and the batch is back
						// in pending, so every later save would re-fail identically - hand the
						// set to Gradle instead. No follow-up build starts here: the session
						// manager is about to call onProxyAppRebuildStarted, and
						// invalidationReported stops a classifier verdict landing in the same
						// window from launching a second rebuild over it.
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
	 * Tallies one failed build and says whether the live reload path cannot recover on its own.
	 *
	 * Only a failure that is NOT the user's own code counts: a compile error is theirs to fix and
	 * auto-escalating one would drop the whole session to Idle, a daemon death has its own respawn
	 * recovery ([onDaemonReplaced]), and [BuildOutcome.RequiresProxyAppRebuild] escalates itself.
	 * What is left fails for a reason no edit can reach, so the same failure twice running is the
	 * evidence - the second build ran against whatever changed in between and failed anyway.
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
	 * they save next - the "stuck relink" shape. True only for an aapt2 rejection, recognised by
	 * every error naming a resource file: the relink links the whole `res/` tree from disk rather
	 * than the changed set, so once a resource is unlinkable even a pure-code save fails
	 * identically. A kotlinc error names the file the user is editing, so it is excluded.
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
		proxyAppWontStayUpReported = false
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

/** How [LiveReloadOrchestrator.onLiveReloadRequested] answers the ask it was handed. */
enum class LiveReloadRequestOutcome {
	/** Nothing to build: the caller answers a tap itself, immediately. */
	SWITCH_NOW,

	/** A build owns the ask; its deploy (or its failure) answers it. */
	AWAITS_DEPLOY,

	/**
	 * The tap is armed on the save-all's incoming watcher batch; the caller must run the
	 * deadline fallback via [LiveReloadOrchestrator.consumeUnansweredTap].
	 */
	AWAITS_CHANGES,
}

/** What the orchestrator tells its host about a build. */
sealed interface OrchestratorEvent {
	/**
	 * A build just started; [changes] is the batch it took.
	 *
	 * @property buildId identifies this build in the later succeeded/failed event.
	 * @property route what the classifier decided, which says which steps will run.
	 * @property changes the batch moved out of pending into this build, reported as
	 *   [ChangedFiles.Unknown] for a warm compile even though it carries no user changes.
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
		 * forward as the deploy lands; false for a build a file write triggered.
		 */
		val userInitiated: Boolean = false,
	) : OrchestratorEvent

	/**
	 * A build did not deploy.
	 *
	 * @property buildId the id of the [BuildStarted] this closes.
	 * @property outcome how it failed; the batch has already returned to pending, so the next
	 *   save rebuilds it.
	 * @property relinkStuck true when a repeating aapt2 rejection is now blocking every build
	 *   whatever the user saves, so the host should say so - set at most once per streak, until a
	 *   success or a fresh baseline (see `LiveReloadOrchestrator.blocksEveryBuild`).
	 * @property proxyAppWontStayUp true when a second not-connected deploy running proves the proxy
	 *   app cannot stay alive to receive a payload, which no edit reaches and no relaunch clears,
	 *   so the host should offer Restart session - set at most once per streak.
	 */
	data class BuildFailed(
		val buildId: Long,
		val outcome: BuildOutcome,
		/** What the build was for - a [BuildRoute.WarmCompile] failure is not user-visible. */
		val route: BuildRoute,
		val relinkStuck: Boolean = false,
		val proxyAppWontStayUp: Boolean = false,
	) : OrchestratorEvent

	/**
	 * The changed-set needs a real Gradle build; the session manager owns the fallback.
	 *
	 * @property reason why the live reload path cannot absorb it, emitted once per pending set so
	 *   that a second save of the same kind does not re-report it.
	 */
	data class InvalidationRequired(
		val reason: InvalidationReason,
	) : OrchestratorEvent
}
