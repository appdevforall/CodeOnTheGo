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
 * Runs the Gradle proxy app builds (first provision + the full-rebuild fallback) and
 * reports what happened as a verdict. This is the orchestrate-vs-execute cut: the
 * runner never reads the live session, never touches the session epoch, and never
 * dispatches - the manager keeps the epoch guard, installs the returned session,
 * starts the watcher, re-surfaces the parked retry message, pokes the orchestrator,
 * and dispatches, because all of that is state ownership.
 *
 * The [superseded] probe each call takes is the manager's epoch check handed in as a
 * closure: the runner asks "was I outlived?" at exactly the points the inlined code
 * used to check, without ever seeing the epoch itself.
 *
 * Call only on the session dispatcher; this class holds no scope of its own.
 */
internal class ProxyAppBuildRunner(
	private val provisioner: QuickBuildProvisioner,
	private val daemonController: QuickBuildDaemonController,
	private val connections: ProxyAppConnections,
	/** App-private scratch trees (ADFA-4930): disk-space guard + per-project tree. */
	private val scratch: QuickBuildScratch,
	private val sessionFactory: LiveSessionFactory,
	private val generationStoreFactory: (File) -> GenerationStore,
	private val metrics: QuickBuildMetricsSink,
) {
	/** What became of a [provision]; the manager dispatches, this class doesn't. */
	sealed interface ProvisionResult {
		/** The private volume is short before anything ran; nothing to undo. */
		data class DiskSpaceShort(
			val message: String,
		) : ProvisionResult

		data class Failed(
			val message: String,
		) : ProvisionResult

		/** Outlived a session restart before any side effect went live; discard silently. */
		data object Superseded : ProvisionResult

		/**
		 * Outlived a session restart while the daemon start was in flight. The runner
		 * already ended the connection session it began; the manager must stop the
		 * zombie daemon (on a fresh coroutine - this one is already cancelled by the
		 * teardown that superseded it) after bumping the daemon epoch.
		 */
		data object SupersededDuringDaemonStart : ProvisionResult

		/** Everything is up; the manager installs the session and goes live. */
		data class Succeeded(
			val session: LiveSession,
			val tracker: GenerationTracker,
		) : ProvisionResult
	}

	/**
	 * Runs the one-time provision: disk-space guard, the Gradle proxy app build +
	 * install, the scratch tree, the deploy-channel session, the daemon start, and the
	 * session assembly. [superseded] is probed after the Gradle build returns and
	 * after the daemon start returns - the two points a "Restart session" can land.
	 */
	suspend fun provision(superseded: () -> Boolean): ProvisionResult {
		// Disk-space guard (ADFA-4930): fail in seconds with a clear message rather than
		// let a full private volume ENOSPC minutes into the proxy app build or mid-quick-build.
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
			// "Restart session" landed while the proxy app build ran; the user asked for a
			// fresh start, so a late success must not resurrect (and a late failure must
			// not surface) - see the zombie-session scenario in the manager's teardown KDoc.
			return ProvisionResult.Superseded
		}

		return when (outcome) {
			is ProvisionOutcome.Failure -> {
				ProvisionResult.Failed(outcome.message)
			}

			is ProvisionOutcome.Success -> {
				// Scratch tree on app-private storage (ADFA-4930): the executor and daemon
				// dirs below live here, never under the FUSE-backed project root.
				when (val prepared = scratch.prepare(outcome.layout.projectRoot)) {
					is QuickBuildScratch.Preparation.Failed -> {
						return ProvisionResult.Failed(prepared.message)
					}

					is QuickBuildScratch.Preparation.Ready -> {
						Unit
					}
				}

				// Error boundary over the whole session assembly: a throw past this point
				// (the daemon start, the tracker construction, sessionFactory.create) would
				// otherwise escape to a session scope with no CoroutineExceptionHandler and
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

	/** What became of a [rebuildProxyApp]; covers all five real rebuild outcomes. */
	sealed interface ProxyAppRebuildResult {
		/** The Gradle slot was taken; nothing ran. The manager decides park-vs-fail. */
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
		 * Rebuilt, reinstalled, daemon restarted against the NEW setup's config. The
		 * manager moves the live session's ProxyAppInfo-derived pieces to this baseline.
		 */
		data class Succeeded(
			val proxyApp: ProxyAppInfo,
			val layout: QuickBuildProjectLayout,
		) : ProxyAppRebuildResult
	}

	/**
	 * Runs the full-Gradle proxy app rebuild: daemon teardown, the Gradle build +
	 * reinstall, the rebuild metric, and (on success) the daemon restart against the
	 * new setup's config.
	 *
	 * @param parkedRetry true when this rebuild retries an unconfirmed reinstall from
	 *   the parked state; a [ProxyAppRebuildResult.BuildSlotBusy] then books NO metric
	 *   (the build never ran - reporting it would charge a 0 ms failure against the
	 *   success rate for work that never happened). A FIRST rebuild losing the slot IS
	 *   surfaced to the user as a failed rebuild, so it books like one.
	 * @param superseded the manager's epoch check, probed once the Gradle build (and
	 *   its metric) is done.
	 */
	suspend fun rebuildProxyApp(
		parkedRetry: Boolean,
		superseded: () -> Boolean,
	): ProxyAppRebuildResult {
		// Free the daemon's ~0.5GB for the Gradle build that is about to peak - on the
		// 3-4GB target class the two must not coexist. Costless: the daemon's IC state
		// is untrustworthy after a proxy app rebuild anyway (regenerated inputs it never saw),
		// and it was going to be re-seeded from scratch regardless; on success it
		// restarts below with the NEW proxy app info's config (the survivor used to keep serving
		// the OLD configure's classpath - correct only via BTA's full-recompile
		// fallback). The epoch bump discards a daemon respawn still in flight (a
		// proxy app rebuild can start from Degraded); see [QuickBuildDaemonController].
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
			// Only a DEFERRED retry (slot busy while parked) skips metrics - see [parkedRetry].
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
				// Restart the daemon torn down above, against the NEW proxy app info's config.
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
	 * True when the proxy app build artifacts the daemon builds against are still on
	 * disk (the B3 hand-back probe: an external clean that wiped build/ forces a
	 * rebuild; anything less only needs a baseline refresh).
	 */
	fun proxyAppArtifactsIntact(proxyApp: ProxyAppInfo): Boolean =
		proxyApp.classpath.all { it.exists() } &&
			proxyApp.proxyClassesDir?.isDirectory != false &&
			proxyApp.transformedManifest?.isFile != false

	/** Metrics can never affect a build: a throwing sink degrades to a logged warning. */
	private inline fun report(block: () -> Unit) {
		try {
			block()
		} catch (e: Throwable) {
			log.warn("Quick Build metrics sink failed", e)
		}
	}

	private companion object {
		private val log = LoggerFactory.getLogger(ProxyAppBuildRunner::class.java)
	}
}
