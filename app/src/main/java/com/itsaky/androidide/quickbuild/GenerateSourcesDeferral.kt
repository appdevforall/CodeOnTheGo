package com.itsaky.androidide.quickbuild

import com.itsaky.androidide.projects.ProjectManagerImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildSessionState
import org.koin.core.context.GlobalContext
import org.slf4j.LoggerFactory

/**
 * Defers the resource-save `generateSources()` Gradle build while a Quick Build session is live
 * (ADFA-4128, quickbuild/docs/resource-updates.md "Defer the build while a Quick Build session
 * is live").
 *
 * The Gradle build exists to keep the Java language server's R symbols fresh: a successful
 * `generateSources` regenerates the intermediates R.jar and posts the `ProjectInitializedEvent`
 * that makes the Java LSP re-read it. Quick Build's reload pipeline never consumes that output -
 * the proxy app gets its resources from Quick Build's own aapt2 relink - so deferring the build
 * costs nothing but a few seconds of editor symbol freshness, and removes the CPU contention
 * between Gradle and the reload plus the single-Gradle-slot contention with the user's own
 * builds.
 *
 * A save-time "is Quick Build building?" check cannot work: at save time the Quick Build
 * pipeline has not started yet (the watcher batch is still inside its 150 ms debounce), so
 * sampling status at that moment misses the primary case. Instead the request keys on session
 * state: with no session it runs immediately (today's behavior); while a session is live it
 * parks, coalescing any number of saves into one pending request, and the one build runs when
 * the pipeline settles - an active-but-idle state held for [idleGraceMillis], long enough to
 * outlast the watcher's debounce and its 2 s mtime-poll fallback so the build does not launch
 * right under an incoming reload. A session that ends with a request still parked runs it
 * rather than dropping it.
 *
 * Releasing a request is not the same as running one. [ProjectManagerImpl.generateSources]
 * early-returns silently when a Gradle build is already in progress, and the session state this
 * class keys off cannot see that: a project sync or the user's own Run occupies the same single
 * Gradle slot while the session sits in a state this class reads as settled. So a release keys
 * off what the build request reports, and re-parks the request when it reports that nothing
 * started.
 *
 * @property scope where the state collection, the grace timers and every deferred build run.
 * @property runBuild the actual build request; asynchronous in production
 *   ([ProjectManagerImpl.generateSources] hands the tasks to the tooling server and returns).
 *   Reports whether the tasks were dispatched - false means the request was refused and still
 *   owes a retry.
 * @property idleGraceMillis how long an active session must sit outside its busy states before
 *   a parked request is released, and how long a refused request waits before trying again.
 */
class GenerateSourcesDeferral(
	private val scope: CoroutineScope,
	private val runBuild: () -> Boolean,
	private val idleGraceMillis: Long = DEFAULT_IDLE_GRACE_MILLIS,
) {
	private val lock = Any()
	private var sessionState: StateFlow<QuickBuildSessionState>? = null
	private var subscription: Job? = null
	private var pending = false
	private var graceJob: Job? = null
	private var refusals = 0

	/**
	 * Starts keying the deferral off a session manager's state stream.
	 *
	 * Idempotent for the same stream, so a second wiring pass cannot double-collect; a different
	 * stream replaces the old collection, so no subscription outlives the manager it watched.
	 *
	 * @param state the session state stream, collected until [scope] dies.
	 */
	fun attach(state: StateFlow<QuickBuildSessionState>) {
		synchronized(lock) {
			if (sessionState === state) return
			subscription?.cancel()
			sessionState = state
			subscription = scope.launch { state.collect { onSessionState(it) } }
		}
	}

	/**
	 * A resource file was saved: run `generateSources` now, or park it until the live session's
	 * pipeline settles. N saves park as one pending request.
	 */
	fun onResourceSaved() {
		val runNow =
			synchronized(lock) {
				pending = true
				refusals = 0
				val state = sessionState?.value
				if (state == null || state is QuickBuildSessionState.Idle) {
					// No session (or Quick Build never wired up): today's immediate call.
					true
				} else {
					reschedule(state)
					false
				}
			}
		if (runNow) release()
	}

	private fun onSessionState(state: QuickBuildSessionState) {
		val releaseNow =
			synchronized(lock) {
				if (!pending) return
				if (state is QuickBuildSessionState.Idle) {
					// The session ended with a request still parked: run it, don't drop it.
					graceJob?.cancel()
					graceJob = null
					true
				} else {
					reschedule(state)
					false
				}
			}
		if (releaseNow) release()
	}

	/**
	 * Runs the parked request, clearing it only once the build has actually been dispatched.
	 *
	 * The request survives a refusal because the refusal is transient and silent: whoever holds
	 * the single Gradle slot will release it. Clearing [pending] before the call - which is what
	 * this used to do - dropped the request with nothing left to retry it, so a resource save
	 * that happened to land during someone else's build left the Java LSP's R symbols stale
	 * until the next save.
	 */
	private fun release() {
		val dispatched = runBuild()
		synchronized(lock) {
			if (dispatched) {
				pending = false
				refusals = 0
				graceJob?.cancel()
				graceJob = null
				return
			}
			if (refusals >= MAX_REFUSALS) {
				// Durably refused rather than momentarily busy - no build service, or the
				// tooling server is down. Stop burning timers; the next save starts over.
				log.warn("generateSources refused {} times; dropping the parked request", refusals)
				pending = false
				refusals = 0
				graceJob?.cancel()
				graceJob = null
				return
			}
			refusals++
			val state = sessionState?.value
			if (state == null) arm() else reschedule(state)
		}
	}

	/** Callers hold [lock]. */
	private fun reschedule(state: QuickBuildSessionState) {
		if (state.isPipelineBusy()) {
			// A Gradle build or a compile is running; wait for the next transition. Running
			// generateSources now would either contend for CPU or be silently swallowed by
			// its own isBuildInProgress early return.
			graceJob?.cancel()
			graceJob = null
			return
		}
		arm()
	}

	/** Callers hold [lock]. */
	private fun arm() {
		graceJob?.cancel()
		graceJob =
			scope.launch {
				delay(idleGraceMillis)
				val run =
					synchronized(lock) {
						graceJob = null
						pending
					}
				if (run) release()
			}
	}

	private fun QuickBuildSessionState.isPipelineBusy(): Boolean =
		when (this) {
			// Prebuilding is not a session, but its proxy app build occupies the tooling
			// server, where generateSources' isBuildInProgress check would swallow the
			// request silently - so it parks like a session's own build.
			is QuickBuildSessionState.Prebuilding,
			is QuickBuildSessionState.Provisioning,
			is QuickBuildSessionState.Building,
			-> true

			// Ready/Deployed between builds, Invalidated parked on a stale baseline,
			// Degraded waiting on the daemon: nothing CPU-heavy owns the device, so a
			// parked request may release after the grace window.
			else -> false
		}

	companion object {
		private val log = LoggerFactory.getLogger("QB-GenerateSourcesDeferral")

		/** Longer than the watcher's 150 ms debounce and its 2 s mtime-poll fallback. */
		private const val DEFAULT_IDLE_GRACE_MILLIS = 3_000L

		/**
		 * How many refusals to sit through before giving up on a parked request. Covers a
		 * whole ordinary build at the default grace window; past that the refusal is durable
		 * (no build service, tooling server down) and retrying only burns timers.
		 */
		private const val MAX_REFUSALS = 5

		/**
		 * The save call sites' entry point: routes through the Koin singleton when the graph is
		 * up, and falls back to the direct call so a save never loses its build.
		 */
		fun notifyResourceSaved() {
			notifyResourceSaved { ProjectManagerImpl.getInstance().generateSources() }
		}

		/**
		 * [notifyResourceSaved] with the direct call injectable, so both directions are
		 * JVM-testable: with the graph up the request routes into the singleton's deferral
		 * logic; with it down (early startup, tests, a torn-down graph) the entry point must
		 * not throw and must still fire [directFallback] - a save never loses its build.
		 */
		internal fun notifyResourceSaved(directFallback: () -> Unit) {
			val deferral =
				runCatching { GlobalContext.get().get<GenerateSourcesDeferral>() }
					.onFailure { log.warn("Quick Build deferral unavailable; running generateSources directly", it) }
					.getOrNull()
			deferral?.onResourceSaved() ?: directFallback()
		}
	}
}
