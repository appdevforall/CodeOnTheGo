package org.appdevforall.cotg.quickbuild.service

import org.appdevforall.cotg.quickbuild.data.DaemonReply
import org.appdevforall.cotg.quickbuild.data.ProxyAppInfo
import org.appdevforall.cotg.quickbuild.data.QuickBuildProjectLayout
import org.appdevforall.cotg.quickbuild.data.QuickBuildScratch
import org.appdevforall.cotg.quickbuild.domain.GenerationStore
import org.appdevforall.cotg.quickbuild.domain.GenerationTracker
import org.appdevforall.cotg.quickbuild.domain.QuickBuildMetricsSink
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Runs the Gradle proxy app builds - the first provision and the full-rebuild fallback -
 * and returns what happened as a verdict.
 *
 * Executes without owning state: it never reads the live session, touches the session
 * epoch, or dispatches. The manager does all of that with the returned result. Each call
 * takes a `superseded` closure, the manager's epoch check, which the runner probes at the
 * points that can be raced without ever seeing the epoch. Call only on the session
 * dispatcher; this class holds no scope of its own.
 */
internal class ProxyAppBuildRunner(
	private val provisioner: QuickBuildProvisioner,
	private val daemonController: QuickBuildDaemonController,
	private val connections: ProxyAppConnections,
	/** App-private scratch trees: disk-space guard plus the per-project tree. */
	private val scratch: QuickBuildScratch,
	private val sessionFactory: LiveSessionFactory,
	private val generationStoreFactory: (File) -> GenerationStore,
	private val metrics: QuickBuildMetricsSink,
) {
	/** What became of a [provision]. The manager dispatches on it; this class does not. */
	sealed interface ProvisionResult {
		/** The private volume was short before anything ran, so there is nothing to undo. */
		data class DiskSpaceShort(
			val message: String,
		) : ProvisionResult

		data class Failed(
			val message: String,
		) : ProvisionResult

		/** Outlived a session restart before any side effect went live; discard silently. */
		data object Superseded : ProvisionResult

		/**
		 * Outlived a session restart while the daemon start was in flight.
		 *
		 * The runner already ended the connection session it began. The manager must bump
		 * the daemon epoch and stop the zombie daemon on a fresh coroutine, since this one
		 * is already cancelled by the teardown that superseded it.
		 */
		data object SupersededDuringDaemonStart : ProvisionResult

		/** Everything is up; the manager installs the session and goes live. */
		data class Succeeded(
			val session: LiveSession,
			val tracker: GenerationTracker,
		) : ProvisionResult
	}

	/**
	 * Runs the one-time provision: disk-space guard, Gradle proxy app build and install,
	 * scratch tree, deploy-channel session, daemon start, and session assembly.
	 *
	 * @param superseded probed after the Gradle build and after the daemon start, the two
	 *   points a "Restart session" can land
	 */
	suspend fun provision(superseded: () -> Boolean): ProvisionResult {
		// Fail in seconds with a clear message rather than let a full private volume
		// ENOSPC minutes into the proxy app build or mid-quick-build.
		scratch.freeSpaceShortfall()?.let { message ->
			return ProvisionResult.DiskSpaceShort(message)
		}

		val outcome =
			try {
				provisioner.provision()
			} catch (e: kotlinx.coroutines.CancellationException) {
				throw e
			} catch (e: Throwable) {
				log.error("Provisioner threw instead of reporting an outcome", e)
				ProvisionOutcome.Failure(e.message ?: e.javaClass.name)
			}

		if (superseded()) {
			// "Restart session" landed while the proxy app build ran. The user asked for
			// a fresh start, so a late success must not resurrect and a late failure must
			// not surface.
			return ProvisionResult.Superseded
		}

		return when (outcome) {
			is ProvisionOutcome.Failure -> {
				ProvisionResult.Failed(outcome.message)
			}

			is ProvisionOutcome.Success -> {
				// Scratch tree on app-private storage: the executor and daemon dirs below
				// live here, never under the FUSE-backed project root.
				when (val prepared = scratch.prepare(outcome.layout.projectRoot)) {
					is QuickBuildScratch.Preparation.Failed -> {
						return ProvisionResult.Failed(prepared.message)
					}

					is QuickBuildScratch.Preparation.Ready -> {
						Unit
					}
				}

				// Error boundary over the whole session assembly: a throw past this point
				// would escape to a session scope with no CoroutineExceptionHandler and
				// crash CoGo with a uid session already registered.
				var sessionBegun = false
				var daemonStarted = false
				try {
					connections.beginSession(outcome.proxyApp.proxyAppPackage, outcome.proxyAppUid)
					sessionBegun = true

					daemonController.markIntentionalTransition()
					when (val started = daemonController.start(outcome.layout, outcome.proxyApp)) {
						is DaemonReply.Ok -> {
							daemonStarted = true
							if (superseded()) {
								// Restart raced the daemon start: undo what began here; the
								// manager stops the zombie daemon.
								connections.endSession()
								return ProvisionResult.SupersededDuringDaemonStart
							}
							val tracker =
								GenerationTracker(generationStoreFactory(outcome.layout.projectRoot))
							ProvisionResult.Succeeded(sessionFactory.create(outcome, tracker), tracker)
						}

						is DaemonReply.BuildFailed -> {
							ProvisionResult.Failed("Daemon rejected configuration")
						}

						is DaemonReply.Failed -> {
							ProvisionResult.Failed(started.message)
						}
					}
				} catch (e: kotlinx.coroutines.CancellationException) {
					// A real teardown superseded this provision; its epoch bump already ran
					// (or is about to run) endSession + shutdown for us.
					throw e
				} catch (e: Throwable) {
					log.error("Session assembly threw after the proxy app build; unwinding", e)
					if (sessionBegun) connections.endSession()
					if (daemonStarted) {
						// Same intentional-transition mark the teardown path uses, so the
						// death listener never respawns a daemon shut down on purpose.
						daemonController.markIntentionalTransition()
						daemonController.shutdown()
					}
					ProvisionResult.Failed(e.message ?: e.javaClass.name)
				}
			}
		}
	}

	/** What became of a [rebuildProxyApp]. */
	sealed interface ProxyAppRebuildResult {
		/** The Gradle slot was taken so nothing ran. The manager decides park versus fail. */
		data object BuildSlotBusy : ProxyAppRebuildResult

		/** Outlived a session restart; the manager discards without touching the session. */
		data object Superseded : ProxyAppRebuildResult

		data class Failed(
			val message: String,
		) : ProxyAppRebuildResult

		/** The Gradle build was fine; only the reinstall confirmation is missing. */
		data class InstallNotConfirmed(
			val message: String,
		) : ProxyAppRebuildResult

		/** The rebuild succeeded but the daemon refused to come back up on the new config. */
		data class DaemonRestartFailed(
			val message: String,
		) : ProxyAppRebuildResult

		/**
		 * Rebuilt, reinstalled, and the daemon restarted against the new setup's config.
		 * The manager moves the live session's ProxyAppInfo-derived pieces to this
		 * baseline.
		 */
		data class Succeeded(
			val proxyApp: ProxyAppInfo,
			val layout: QuickBuildProjectLayout,
		) : ProxyAppRebuildResult
	}

	/**
	 * Runs the full-Gradle proxy app rebuild: daemon teardown, Gradle build and
	 * reinstall, the rebuild metric, then on success the daemon restart against the new
	 * setup's config.
	 *
	 * @param parkedRetry true when this retries an unconfirmed reinstall from the parked
	 *   state. A [ProxyAppRebuildResult.BuildSlotBusy] then books no metric, since the
	 *   build never ran; a first rebuild losing the slot does surface to the user as a
	 *   failed rebuild, so it books like one.
	 * @param superseded the manager's epoch check, probed once the Gradle build and its
	 *   metric are done
	 */
	suspend fun rebuildProxyApp(
		parkedRetry: Boolean,
		superseded: () -> Boolean,
	): ProxyAppRebuildResult {
		// Free the daemon's memory for the Gradle build about to peak; on a 3-4GB device
		// the two must not coexist. Nothing is lost: the daemon's incremental state is
		// stale after a rebuild anyway, and on success it restarts below against the new
		// config - a surviving daemon would keep serving the old configure's classpath.
		daemonController.markIntentionalTransition()
		daemonController.shutdown()

		val startedAtNanos = System.nanoTime()
		val outcome =
			try {
				provisioner.rebuildProxyApp()
			} catch (e: kotlinx.coroutines.CancellationException) {
				throw e
			} catch (e: Throwable) {
				log.error("Proxy app rebuild threw instead of reporting an outcome", e)
				ProxyAppRebuildOutcome.Failure(e.message ?: e.javaClass.name)
			}
		if (outcome !is ProxyAppRebuildOutcome.BuildSlotBusy || !parkedRetry) {
			// Only a deferred retry that lost the slot skips metrics; see [parkedRetry].
			report {
				metrics.onProxyAppRebuild(
					isSuccess = outcome is ProxyAppRebuildOutcome.Success,
					durationMillis = (System.nanoTime() - startedAtNanos) / 1_000_000,
				)
			}
		}

		if (superseded()) return ProxyAppRebuildResult.Superseded

		return when (outcome) {
			is ProxyAppRebuildOutcome.BuildSlotBusy -> {
				ProxyAppRebuildResult.BuildSlotBusy
			}

			is ProxyAppRebuildOutcome.Failure -> {
				ProxyAppRebuildResult.Failed(outcome.message)
			}

			is ProxyAppRebuildOutcome.InstallNotConfirmed -> {
				ProxyAppRebuildResult.InstallNotConfirmed(outcome.message)
			}

			is ProxyAppRebuildOutcome.Success -> {
				// Restart the daemon torn down above, against the new proxy app's config.
				daemonController.markIntentionalTransition()
				when (val started = daemonController.start(outcome.layout, outcome.proxyApp)) {
					is DaemonReply.Ok -> {
						ProxyAppRebuildResult.Succeeded(outcome.proxyApp, outcome.layout)
					}

					else -> {
						ProxyAppRebuildResult.DaemonRestartFailed(
							(started as? DaemonReply.Failed)?.message ?: "daemon rejected configuration",
						)
					}
				}
			}
		}
	}

	/**
	 * True when the proxy app build artifacts the daemon compiles against are still on
	 * disk.
	 *
	 * Used on hand-back: an external clean that wiped `build/` forces a rebuild, while
	 * anything less only needs a baseline refresh.
	 */
	fun proxyAppArtifactsIntact(proxyApp: ProxyAppInfo): Boolean =
		proxyApp.classpath.all { it.exists() } &&
			proxyApp.proxyClassesDir?.isDirectory != false &&
			proxyApp.transformedManifest?.isFile != false

	private companion object {
		private val log = LoggerFactory.getLogger(ProxyAppBuildRunner::class.java)
	}
}
