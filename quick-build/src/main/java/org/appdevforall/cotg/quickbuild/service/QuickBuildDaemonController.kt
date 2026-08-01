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
 * One owner for the compile daemon's lifecycle protocol: the daemon-epoch rule, the
 * respawn-supersession cleanup, and the low-memory shrink policy. Extracted from
 * [QuickBuildSessionManager] so the epoch's bump sites and its two guard checks live
 * on one screen instead of 700 lines apart.
 *
 * The epoch counts intentional daemon lifecycle transitions: every `daemon.start` /
 * `daemon.shutdown` the session manager initiates outside the respawn path
 * (provisioning start + its undo, proxy-app-rebuild teardown + restart, session
 * teardown, low-memory shrink). [start] and [shutdown] deliberately do NOT bump it -
 * bumps stay explicit at the call sites via [markIntentionalTransition], for two
 * reasons: the session teardown bumps synchronously before it suspends (a bump inside
 * a suspending [shutdown] would let a concurrent respawn slip through the window), and
 * [respawn]'s cleanup rule counts EXACTLY one transition - an auto-bump inside
 * [shutdown] would make teardown two and silently break it.
 *
 * Call only on the session dispatcher; this class holds no scope of its own.
 */
internal class QuickBuildDaemonController(
	private val daemon: QuickBuildDaemon,
	/** App-private scratch trees (ADFA-4930); the daemon's output dir lives here. */
	private val scratch: QuickBuildScratch,
	private val paths: QuickBuildPaths,
) {
	/**
	 * [respawn] captures this at effect time and re-checks after its `daemon.start`
	 * returns: any change means an intentional shutdown superseded the respawn
	 * mid-flight - a proxy app rebuild's `daemon.shutdown()` racing an in-flight
	 * respawn - so the respawn discards its result instead of
	 * reporting [RespawnOutcome.Respawned]. EXACTLY one transition since capture is the
	 * superseding shutdown itself, so a daemon the stale start brought up is a zombie
	 * only the respawn knows about - it stops it (the daemon must not coexist with the
	 * proxy app rebuild's Gradle build, nor outlive a teardown). More than one means a
	 * successor flow already started a fresh daemon the stale respawn must not touch.
	 * Only touched on the session dispatcher.
	 */
	private var daemonEpoch = 0L

	/** Set only on the session dispatcher; a build in flight defers the teardown here. */
	private var pendingLowMemoryTeardown = false

	/**
	 * Records an intentional daemon lifecycle transition. Non-suspending on purpose:
	 * the session teardown must bump BEFORE its shutdown suspends, so a concurrent
	 * respawn can never observe the pre-teardown epoch after the teardown began.
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

	/** What became of a [respawn]; the manager dispatches/surfaces, this class doesn't. */
	sealed interface RespawnOutcome {
		/** The daemon is up again; the manager re-seeds via the orchestrator. */
		data object Respawned : RespawnOutcome

		/**
		 * An intentional transition superseded the respawn (before or during its
		 * start); the successor flow owns the daemon lifecycle and any zombie daemon
		 * the stale start brought up has already been stopped here.
		 */
		data object Superseded : RespawnOutcome

		data class Failed(
			val message: String,
		) : RespawnOutcome
	}

	/**
	 * Restarts a dead daemon, guarded by the epoch protocol: [startEpoch] is the
	 * [epochSnapshot] captured when the respawn effect fired. See [daemonEpoch] for
	 * the exactly-one-transition cleanup rule applied when the check fails mid-start.
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
			// An intentional shutdown (proxy-app-rebuild teardown, session teardown,
			// low-memory shrink) landed while this respawn's start was in flight.
			// The superseding flow owns the daemon
			// lifecycle now: reporting Respawned here would corrupt it. See
			// [daemonEpoch] for the exactly-one-transition cleanup rule.
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
	 * The low-memory shrink policy (P1a.1). Only
	 * [ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL] and above tear the daemon
	 * down: `RUNNING_MODERATE`/`RUNNING_LOW` fire on transient pressure the OS usually
	 * recovers from without killing anything, and a teardown pays a daemon respawn +
	 * re-seed on the next edit. [ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN] is
	 * explicitly NOT a teardown even though Android numbers it (20) above
	 * `RUNNING_CRITICAL` (15): it means "your UI went away", not "memory is short",
	 * and backgrounding CoGo is the MIDDLE of the Quick Build loop (the user is
	 * looking at their proxy app). The cached-process levels above it (`BACKGROUND`,
	 * `MODERATE`, `COMPLETE`) DO tear down - those only arrive when the system is
	 * genuinely short. Full policy rationale:
	 * [QuickBuildSessionManager.onTrimMemory].
	 *
	 * A build in flight ([buildInFlight]) is never interrupted: the teardown defers,
	 * and the manager's state collector retries via [shrinkIfPending] the moment the
	 * build's own transition lands.
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
			// Not memory pressure - the user just switched away (typically to their own
			// proxy app, mid-loop). Keep the daemon warm; see this function's KDoc.
			log.debug("Quick Build: onTrimMemory(UI_HIDDEN); keeping the daemon warm")
			return
		}
		pendingLowMemoryTeardown = true
		shrinkIfPending(buildInFlight)
	}

	/**
	 * Tears the daemon down for memory pressure, unless a build is in flight - then
	 * this is a no-op that leaves the pending flag set for the manager's state
	 * collector to retry once that build's own transition lands. Idempotent: a daemon
	 * already down, or no pending request at all, is a silent no-op either way - safe
	 * to call from a repeated `onTrimMemory(CRITICAL)` or from the retry collector
	 * alike.
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

	private fun configFor(
		layout: QuickBuildProjectLayout,
		proxyApp: ProxyAppInfo,
	): DaemonConfig =
		DaemonConfig(
			projectRoot = layout.projectRoot,
			classpath = layout.compileClasspath(),
			// App-private scratch (ADFA-4930): the daemon's per-file-heavy output tree is
			// the single biggest FUSE payer, and its scratchFsType reply (the bench-event
			// field) reports whatever filesystem THIS dir lands on.
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
