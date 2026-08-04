package org.appdevforall.cotg.quickbuild.service

import android.os.ParcelFileDescriptor
import android.os.RemoteException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Sends deploy payloads to the proxy app and awaits its verdict.
 *
 * An interface so the executor is unit-testable: the real channel touches
 * [ParcelFileDescriptor] and binder, which only exist on device.
 */
interface DeploySender {
	/**
	 * Delivers one payload to the connected proxy app and waits for it to reload or fail.
	 *
	 * All file params are optional per the AIDL contract; [metadataJson] follows the
	 * schema in quickbuild/README.md.
	 *
	 * @param generation the payload's generation; the runtime accepts only strictly newer
	 *   ones, so this must come from the generation tracker and never be replayed
	 * @param dexFile the payload's classes, or null when the build changed no code
	 * @param arscFile the relinked resource APK, or null when resources did not move
	 * @param assetsZip the changed-assets archive, or null when no asset changed
	 * @param metadataJson entry activity, changed-asset paths, reason, and restart flag
	 * @return the proxy app's verdict; every bounded wait surfaces here rather than throwing
	 */
	suspend fun deploy(
		generation: Long,
		dexFile: File?,
		arscFile: File?,
		assetsZip: File?,
		metadataJson: String,
	): DeployResult

	/**
	 * Tells the running proxy app a build failed or succeeded, when there is no payload
	 * to send.
	 *
	 * Fire-and-forget: no verdict, never throws. A disconnected proxy app, or one whose
	 * stub predates onBuildStatus, simply misses the message.
	 *
	 * @param statusJson built by [BuildStatusJson]
	 */
	fun notifyBuildStatus(statusJson: String)

	/**
	 * Waits until no proxy app is bound, so the restart path can confirm the runtime
	 * exited before relaunching. Relaunching a still-alive process would resume the old
	 * code.
	 *
	 * @param timeoutMillis upper bound on the wait, sized for a runtime exit rather than a
	 *   process launch
	 * @return true when disconnected within [timeoutMillis]
	 */
	suspend fun awaitDisconnect(timeoutMillis: Long): Boolean

	/**
	 * Waits for a proxy app to reconnect, so the restart path can check which generation
	 * actually booted rather than assume the deployed one.
	 *
	 * @param timeoutMillis upper bound on the wait, sized for a cold app start on low-end
	 *   hardware
	 * @return the generation the app reports running, or null on timeout
	 */
	suspend fun awaitReconnect(timeoutMillis: Long): Long?
}

/** Terminal outcome of one deploy attempt. */
sealed interface DeployResult {
	/**
	 * The payload is live: the app loaded it and reported back.
	 *
	 * @property reloadMillis the app's own measure of the reload, from payload receipt to
	 *   the recreated activity's onResume; the only span the host cannot time itself
	 */
	data class Reloaded(
		val reloadMillis: Long,
	) : DeployResult

	/**
	 * The payload reached the app but crashed in render/lifecycle.
	 *
	 * @property stackSummary the runtime's one-line summary of the throwable, shown to the
	 *   user as the deploy failure
	 */
	data class Crashed(
		val stackSummary: String,
	) : DeployResult

	/**
	 * No proxy app was bound, so nothing was sent and nothing is stale. The caller may
	 * launch the app once and retry (see [PayloadDeployer]'s deploy-recovering path).
	 */
	data object NotConnected : DeployResult

	/**
	 * The proxy app disconnected while the deploy waited for its verdict. Fatal for a
	 * hot-swap deploy; for a restart deploy it is the expected process exit, which
	 * relaunch and binder catch-up then reconcile.
	 */
	data object Disconnected : DeployResult

	/**
	 * No verdict arrived in time, so whether the payload landed is unknown.
	 *
	 * @property timeoutMillis the bound that elapsed, echoed into the user-facing message
	 */
	data class TimedOut(
		val timeoutMillis: Long,
	) : DeployResult

	/**
	 * The payload never reached the app: the binder call threw, or a payload file could
	 * not be opened as a read-only fd.
	 *
	 * @property message the binder or IO failure text, shown as the deploy failure
	 */
	data class Failed(
		val message: String,
	) : DeployResult
}

/**
 * The on-device [DeploySender]: passes payload files as read-only fds over the oneway
 * [com.itsaky.androidide.quickbuild.IQuickBuildTarget.onPayload] and awaits the matching
 * report.
 *
 * Every wait is bounded, so a hung proxy app surfaces as [DeployResult.TimedOut] instead
 * of a stuck build.
 *
 * @property connections the registry the bound proxy app and its reports arrive on
 * @property timeoutMillis bound on one deploy round trip, from the oneway call to the
 *   matching report
 */
class DeployChannel(
	private val connections: ProxyAppConnections,
	private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : DeploySender {
	override suspend fun deploy(
		generation: Long,
		dexFile: File?,
		arscFile: File?,
		assetsZip: File?,
		metadataJson: String,
	): DeployResult {
		val connection = connections.target.value ?: return DeployResult.NotConnected

		return withTimeoutOrNull(timeoutMillis) {
			coroutineScope {
				// Subscribe BEFORE the oneway call: UNDISPATCHED runs until the flow
				// collection suspends, so a fast report cannot slip past us.
				val verdict =
					async(start = CoroutineStart.UNDISPATCHED) {
						connections.reports.first { report ->
							when (report) {
								is TargetReport.Reloaded -> report.generation == generation
								is TargetReport.Crashed -> report.generation == generation
								TargetReport.Disconnected -> true
							}
						}
					}

				try {
					openReadOnly(dexFile).use { dexFd ->
						openReadOnly(arscFile).use { arscFd ->
							openReadOnly(assetsZip).use { assetsFd ->
								connection.target.onPayload(
									generation,
									dexFd,
									arscFd,
									assetsFd,
									metadataJson,
								)
							}
						}
					}
				} catch (e: RemoteException) {
					verdict.cancel()
					log.error("Deploy of generation {} failed at the binder", generation, e)
					return@coroutineScope DeployResult.Failed("Binder call failed: ${e.message}")
				} catch (e: java.io.IOException) {
					verdict.cancel()
					log.error("Deploy of generation {} could not open a payload fd", generation, e)
					return@coroutineScope DeployResult.Failed("Cannot open payload: ${e.message}")
				}

				when (val report = verdict.await()) {
					is TargetReport.Reloaded -> DeployResult.Reloaded(report.reloadMillis)
					is TargetReport.Crashed -> DeployResult.Crashed(report.stackSummary)
					TargetReport.Disconnected -> DeployResult.Disconnected
				}
			}
		} ?: DeployResult.TimedOut(timeoutMillis)
	}

	override fun notifyBuildStatus(statusJson: String) {
		val connection = connections.target.value ?: return
		try {
			connection.target.onBuildStatus(statusJson)
		} catch (e: Exception) {
			// Best-effort by contract (binder proxies can throw beyond RemoteException);
			// the failure surface for builds is CoGo's own UI.
			log.warn("Build-status message to the proxy app failed", e)
		}
	}

	override suspend fun awaitDisconnect(timeoutMillis: Long): Boolean =
		// The awaited value is null by construction, so the block must yield its own
		// non-null sentinel: returning `first { it == null }` would make a real
		// disconnect indistinguishable from a timeout.
		withTimeoutOrNull(timeoutMillis) {
			connections.target.first { it == null }
			true
		} == true

	override suspend fun awaitReconnect(timeoutMillis: Long): Long? =
		withTimeoutOrNull(timeoutMillis) {
			connections.target.first { it != null }?.runningGeneration
		}

	/**
	 * Opens one payload file as a read-only fd for the binder call.
	 *
	 * @param file the payload file, or null for an omitted payload slot
	 * @return the fd the caller must close, or null when [file] was null
	 */
	private fun openReadOnly(file: File?): ParcelFileDescriptor? =
		file?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }

	companion object {
		private val log = LoggerFactory.getLogger(DeployChannel::class.java)

		/** Reload itself is ~40ms; the margin covers a cold proxy-app relaunch. */
		const val DEFAULT_TIMEOUT_MILLIS = 15_000L
	}
}
