package org.appdevforall.cotg.quickbuild.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.itsaky.androidide.quickbuild.IQuickBuildHost
import com.itsaky.androidide.quickbuild.IQuickBuildTarget
import org.slf4j.LoggerFactory

/**
 * CoGo side of the deploy channel (plan 2.4, LogSender bind pattern): the generated
 * test app binds on launch and registers its [IQuickBuildTarget]; deploys travel back
 * over that callback as fds.
 *
 * Security: every inbound call is checked against the uid PackageManager reported for
 * the installed test app at session start ([TestAppConnections.beginSession]). No
 * session, or any other caller, is rejected with a logged SecurityException - the
 * service is exported, so the uid gate is the whole trust boundary.
 */
class QuickBuildHostService : Service() {
	private val binder = HostBinder(TestAppConnections.INSTANCE)

	override fun onBind(intent: Intent?): IBinder? {
		if (intent?.action != ACTION_QUICK_BUILD) {
			log.debug("Rejecting bind request: action={}", intent?.action)
			return null
		}
		return binder
	}

	internal class HostBinder(
		private val connections: TestAppConnections,
	) : IQuickBuildHost.Stub() {
		override fun connect(
			target: IQuickBuildTarget?,
			packageName: String?,
			runningGeneration: Long,
		) {
			enforceCaller("connect")
			if (target == null || packageName == null) {
				throw SecurityException("connect() with null target or packageName")
			}

			// Clear the registration if this test app process dies so deploys fail
			// fast as NotConnected instead of timing out on a dead binder.
			runCatching {
				target.asBinder().linkToDeath(
					{ connections.onDisconnected() },
					0,
				)
			}

			log.info("Test app {} connected at generation {}", packageName, runningGeneration)
			connections.onConnected(ConnectedTarget(target, packageName, runningGeneration))
		}

		override fun reportReloaded(
			generation: Long,
			reloadMillis: Long,
		) {
			enforceCaller("reportReloaded")
			connections.report(TargetReport.Reloaded(generation, reloadMillis))
		}

		override fun reportCrash(
			generation: Long,
			stackSummary: String?,
		) {
			enforceCaller("reportCrash")
			connections.report(TargetReport.Crashed(generation, stackSummary ?: "unknown crash"))
		}

		override fun disconnect(packageName: String?) {
			enforceCaller("disconnect")
			log.info("Test app {} disconnected", packageName)
			connections.onDisconnected()
		}

		private fun enforceCaller(op: String) {
			val expected = connections.expectedUid
			val calling = Binder.getCallingUid()
			if (expected == null || calling != expected) {
				val error =
					SecurityException(
						"Rejected $op from uid $calling (expected ${expected ?: "no live session"})",
					)
				log.warn("Quick-build host rejected a call", error)
				throw error
			}
		}
	}

	companion object {
		private val log = LoggerFactory.getLogger(QuickBuildHostService::class.java)

		/** Matches the manifest intent-filter and the runtime's bind intent. */
		const val ACTION_QUICK_BUILD = "com.itsaky.androidide.QUICK_BUILD_ACTION"
	}
}
