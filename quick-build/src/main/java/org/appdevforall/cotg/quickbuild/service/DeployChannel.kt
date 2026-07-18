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
 * Sends one deploy payload to the test app and awaits its verdict. Behind an
 * interface so the executor is unit-testable; the real channel touches
 * [ParcelFileDescriptor] and binder, which only exist on device.
 */
interface DeploySender {
	/**
	 * Delivers [generation] to the connected test app. All file params optional per
	 * the AIDL contract; [metadataJson] follows the schema in quick-build/README.md.
	 */
	suspend fun deploy(
		generation: Long,
		dexFile: File?,
		arscFile: File?,
		assetsZip: File?,
		metadataJson: String,
	): DeployResult

	/**
	 * Best-effort build-status message (plan A1): tells the running test app a build
	 * failed CoGo-side (compile errors never produce a payload) or succeeded (clears a
	 * shown failure). Fire-and-forget - no verdict, never throws; a disconnected or
	 * older test app (whose stub predates onBuildStatus) simply misses the message.
	 * [statusJson] comes from [BuildStatusJson].
	 */
	fun notifyBuildStatus(statusJson: String)
}

/** Terminal outcome of one deploy attempt. */
sealed interface DeployResult {
	data class Reloaded(
		val reloadMillis: Long,
	) : DeployResult

	/** The payload reached the app but crashed in render/lifecycle. */
	data class Crashed(
		val stackSummary: String,
	) : DeployResult

	data object NotConnected : DeployResult

	data class TimedOut(
		val timeoutMillis: Long,
	) : DeployResult

	data class Failed(
		val message: String,
	) : DeployResult
}

/**
 * The real deploy channel: opens read-only fds per payload file, hands them across
 * the oneway [com.itsaky.androidide.quickbuild.IQuickBuildTarget.onPayload], and
 * awaits the matching reportReloaded/reportCrash with a timeout so a hung test app
 * degrades to a visible [DeployResult.TimedOut] rather than a stuck build.
 */
class DeployChannel(
	private val connections: TestAppConnections,
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
					TargetReport.Disconnected ->
						DeployResult.Failed("Test app disconnected during deploy")
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
			log.warn("Build-status message to the test app failed", e)
		}
	}

	private fun openReadOnly(file: File?): ParcelFileDescriptor? =
		file?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }

	companion object {
		private val log = LoggerFactory.getLogger(DeployChannel::class.java)

		/** Reload itself is ~40ms; the margin covers a cold test-app relaunch. */
		const val DEFAULT_TIMEOUT_MILLIS = 15_000L
	}
}
