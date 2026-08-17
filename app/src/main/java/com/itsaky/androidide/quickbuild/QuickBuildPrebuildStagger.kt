package com.itsaky.androidide.quickbuild

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Holds the eager Quick Build prebuild out of the project-open contention spike (ADFA-4128).
 *
 * Project open already saturates a low-end device without Quick Build's help: the Gradle sync,
 * both language servers' setup (the Kotlin analysis session alone allocates heavily) and source
 * indexing all start within the same seconds, and none of them publishes a completion signal the
 * host could key on. Firing the eager proxy app build into that spike put a whole Gradle
 * assemble on the daemon at the worst moment; on-device QA (2026-08-13) caught the editor's
 * input dispatch starving for 10 s under the combined load. So the warm-up waits out a fixed
 * stagger window instead - it is purely opportunistic, and nothing breaks by starting it late.
 *
 * What is deliberately NOT deferred:
 * - A user tap. Taps never route through this class: from Idle a tap provisions immediately
 *   (SessionReducer: Idle + QuickBuildTapped -> Provisioning), so during the window the user is
 *   strictly better off than under the old eager prebuild, where a tap queued behind the
 *   in-flight warm build until PrebuildFinished.
 * - A re-sync while a session is live. The session manager's `onProjectSynced` doubles as the
 *   variant-switch reprovision check, and delaying that leaves a live session hot-reloading
 *   into the wrong variant's app - so a non-idle session fires through immediately (where the
 *   embedded PrebuildRequested is a reducer no-op anyway).
 *
 * A later sync replaces a still-pending window rather than stacking a second one, and the scope
 * dying (project closed, activity destroyed) drops the pending fire outright - the next open
 * schedules its own.
 *
 * @property scope where the stagger window runs; cancel it and a pending prebuild is dropped.
 * @property staggerMillis how long after a sync settles the warm-up may start. The default is a
 *   judgment call sized to outlast the open-time burst on the devices QA runs on, not a measured
 *   settle point - there is no host-side signal for "the language servers are done".
 */
class QuickBuildPrebuildStagger(
	private val scope: CoroutineScope,
	private val staggerMillis: Long = DEFAULT_STAGGER_MILLIS,
) {
	private val lock = Any()
	private var scheduled: Job? = null

	/**
	 * The editor's project-sync-completed hook, wrapping the session manager's own.
	 *
	 * @param sessionIsLive whether a session (or an earlier prebuild) currently exists, sampled
	 *   under the decision - live fires now, idle waits out the window.
	 * @param fire forwards to the session manager; called at most once per sync, either
	 *   immediately or after [staggerMillis].
	 */
	fun onProjectSynced(
		sessionIsLive: () -> Boolean,
		fire: () -> Unit,
	) {
		val fireNow: Boolean
		synchronized(lock) {
			scheduled?.cancel()
			scheduled = null
			fireNow = sessionIsLive()
			if (!fireNow) {
				log.info("Deferring the eager Quick Build prebuild by {} ms to stay off the project-open spike", staggerMillis)
				scheduled =
					scope.launch {
						delay(staggerMillis)
						synchronized(lock) { scheduled = null }
						fire()
					}
			}
		}
		if (fireNow) {
			fire()
		}
	}

	companion object {
		private val log = LoggerFactory.getLogger("QB-PrebuildStagger")

		/**
		 * Long enough for the sync + LSP-setup burst to pass on the A56 before the proxy app
		 * build claims the daemon. Unmeasured on the low-end tier; tune against device evidence.
		 */
		const val DEFAULT_STAGGER_MILLIS = 30_000L
	}
}
