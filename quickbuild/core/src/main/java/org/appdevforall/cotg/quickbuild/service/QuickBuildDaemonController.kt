package org.appdevforall.cotg.quickbuild.service

import android.content.ComponentCallbacks2
import org.appdevforall.cotg.quickbuild.data.DaemonConfig
import org.appdevforall.cotg.quickbuild.data.DaemonReply
import org.appdevforall.cotg.quickbuild.data.ProxyAppInfo
import org.appdevforall.cotg.quickbuild.data.QuickBuildDaemon
import org.appdevforall.cotg.quickbuild.data.QuickBuildPaths
import org.appdevforall.cotg.quickbuild.data.QuickBuildProjectLayout
import org.appdevforall.cotg.quickbuild.data.QuickBuildScratch
import org.slf4j.LoggerFactory

/**
 * Owns the compile daemon's lifecycle protocol: the daemon-epoch rule, the
 * respawn-supersession cleanup, and the low-memory shrink policy.
 *
 * The epoch counts intentional daemon transitions - every start or shutdown the session
 * manager initiates outside the respawn path. [start] and [shutdown] deliberately do not
 * bump it, because the session teardown must bump synchronously before it suspends, and
 * because [respawn]'s cleanup rule counts exactly one transition, which an auto-bump would
 * break. Call only on the session dispatcher; this class holds no scope of its own.
 */
internal class QuickBuildDaemonController(
	private val daemon: QuickBuildDaemon,
	/** App-private scratch trees; the daemon's output dir lives here. */
	private val scratch: QuickBuildScratch,
	private val paths: QuickBuildPaths,
) {
	/**
	 * Count of intentional daemon transitions, used to detect that a respawn was
	 * superseded while its start was in flight. Only touched on the session dispatcher.
	 *
	 * Exactly one transition since a respawn captured the epoch means the superseding
	 * shutdown itself, so any daemon the stale start brought up is a zombie the respawn
	 * must stop. More than one means a successor flow already started a fresh daemon the
	 * stale respawn must leave alone.
	 */
	private var daemonEpoch = 0L

	/** Set only on the session dispatcher; a build in flight defers the teardown here. */
	private var pendingLowMemoryTeardown = false

	/**
	 * Records an intentional daemon lifecycle transition.
	 *
	 * Non-suspending on purpose: the session teardown must bump before its shutdown
	 * suspends, so a concurrent respawn can never observe the pre-teardown epoch after
	 * the teardown began.
	 */
	fun markIntentionalTransition() {
		daemonEpoch++
	}

	/** The current epoch, captured at effect time and passed back into [respawn]. */
	fun epochSnapshot(): Long = daemonEpoch

	/** Starts the daemon against [layout] + [proxyApp]'s config. Never bumps the epoch. */
	suspend fun start(
		layout: QuickBuildProjectLayout,
		proxyApp: ProxyAppInfo,
	): DaemonReply<Unit> = daemon.start(configFor(layout, proxyApp))

	/** Stops the daemon. Never bumps the epoch - see [markIntentionalTransition]. */
	suspend fun shutdown() {
		daemon.shutdown()
	}

	/** What became of a [respawn]. The manager dispatches on it; this class does not. */
	sealed interface RespawnOutcome {
		/** The daemon is up again; the manager re-seeds via the orchestrator. */
		data object Respawned : RespawnOutcome

		/**
		 * An intentional transition superseded the respawn, before or during its start.
		 * The successor flow owns the daemon lifecycle, and any zombie daemon the stale
		 * start brought up was already stopped.
		 */
		data object Superseded : RespawnOutcome

		data class Failed(
			val message: String,
		) : RespawnOutcome
	}

	/**
	 * Restarts a dead daemon unless an intentional transition superseded the attempt.
	 *
	 * @param startEpoch the [epochSnapshot] taken when the respawn effect fired
	 */
	suspend fun respawn(
		layout: QuickBuildProjectLayout,
		proxyApp: ProxyAppInfo,
		startEpoch: Long,
	): RespawnOutcome {
		if (startEpoch != daemonEpoch) {
			// An intentional daemon transition already superseded this respawn before it
			// even started; the successor flow owns the daemon lifecycle.
			log.info("Quick-build daemon respawn superseded before start; discarding")
			return RespawnOutcome.Superseded
		}
		val started = daemon.start(configFor(layout, proxyApp))
		if (startEpoch != daemonEpoch) {
			// An intentional shutdown landed while this respawn's start was in flight, so
			// the superseding flow owns the daemon lifecycle now. See daemonEpoch for the
			// exactly-one-transition cleanup rule.
			if (started is DaemonReply.Ok && daemonEpoch == startEpoch + 1) {
				log.info("Quick-build daemon respawn outlived an intentional shutdown; stopping its daemon")
				daemon.shutdown()
			} else {
				log.info("Quick-build daemon respawn outlived a daemon restart; discarding")
			}
			return RespawnOutcome.Superseded
		}
		return when (started) {
			is DaemonReply.Ok -> {
				RespawnOutcome.Respawned
			}

			else -> {
				RespawnOutcome.Failed(
					(started as? DaemonReply.Failed)?.message ?: "unknown failure",
				)
			}
		}
	}

	/**
	 * Tears the daemon down, but only when the system is genuinely short of memory:
	 * [ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL] and the cached-process levels
	 * above it.
	 *
	 * `RUNNING_MODERATE` and `RUNNING_LOW` fire on transient pressure the OS usually
	 * recovers from, and a teardown costs a respawn plus a re-seed on the next edit.
	 * [ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN] is excluded despite its higher number:
	 * it means the UI went away, and backgrounding CoGo is the middle of the Quick Build
	 * loop. A build in flight is never interrupted; the teardown defers and
	 * [shrinkIfPending] retries it later.
	 */
	suspend fun onTrimMemory(
		level: Int,
		buildInFlight: Boolean,
	) {
		if (level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
			log.debug("Quick Build: onTrimMemory({}) below the shrink threshold; no-op", level)
			return
		}
		if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
			// Not memory pressure: the user just switched away, typically to their own
			// proxy app mid-loop. Keep the daemon warm.
			log.debug("Quick Build: onTrimMemory(UI_HIDDEN); keeping the daemon warm")
			return
		}
		pendingLowMemoryTeardown = true
		shrinkIfPending(buildInFlight)
	}

	/**
	 * Carries out a deferred low-memory teardown once no build is in flight.
	 *
	 * A build in flight leaves the pending flag set for the manager's state collector to
	 * retry. Idempotent: with no pending request, or a daemon already down, this is a
	 * silent no-op.
	 */
	suspend fun shrinkIfPending(buildInFlight: Boolean) {
		if (buildInFlight) return
		if (!pendingLowMemoryTeardown) return
		pendingLowMemoryTeardown = false
		if (!daemon.isRunning) return
		log.info("Quick Build: tearing down the compile daemon for low memory; the next build re-warms it")
		markIntentionalTransition()
		daemon.shutdown()
	}

	/** Builds the daemon config for one project layout and proxy app baseline. */
	private fun configFor(
		layout: QuickBuildProjectLayout,
		proxyApp: ProxyAppInfo,
	): DaemonConfig =
		DaemonConfig(
			projectRoot = layout.projectRoot,
			classpath = layout.compileClasspath(),
			// App-private scratch: the daemon's output tree writes many small files and
			// is the biggest cost on FUSE. The daemon's scratchFsType reply reports
			// whichever filesystem this dir lands on.
			outDir = scratch.outDirFor(layout.projectRoot),
			aapt2 = paths.aapt2,
			d8Jar = paths.d8Jar,
			androidJar = paths.androidJar,
			compilerPlugins =
				if (proxyApp.composeEnabled) listOf(paths.composeCompilerPlugin) else emptyList(),
		)

	private companion object {
		private val log = LoggerFactory.getLogger(QuickBuildDaemonController::class.java)
	}
}
