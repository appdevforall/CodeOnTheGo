package org.appdevforall.cotg.quickbuild.service.deploy

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.itsaky.androidide.quickbuild.IQuickBuildHost
import com.itsaky.androidide.quickbuild.IQuickBuildTarget
import org.slf4j.LoggerFactory

/**
 * CoGo side of the deploy channel: the proxy app binds on launch and registers its
 * [IQuickBuildTarget], and deploys travel back over that callback as fds.
 *
 * The service is exported, so the uid gate is the whole trust boundary: every inbound call must
 * come from the uid PackageManager reported for the installed proxy app at session start, and
 * anything else - including any call with no live session - is rejected with a SecurityException.
 */
class QuickBuildHostService : Service() {
	private val binder = HostBinder(ProxyAppConnections.INSTANCE)

	/**
	 * Gives the registry the freezer hold it drives. This service is the first object in the
	 * deploy path with a Context, and its lifetime already spans the binding it protects: the
	 * proxy app's own bind is what creates it, so it outlives every connect it will see.
	 */
	override fun onCreate() {
		super.onCreate()
		ProxyAppConnections.INSTANCE.installPriorityHold(BoundServicePriorityHold.forContext(this))
	}

	/** Drops the hold, so no binding outlives the service that owns its context. */
	override fun onDestroy() {
		ProxyAppConnections.INSTANCE.uninstallPriorityHold()
		super.onDestroy()
	}

	override fun onBind(intent: Intent?): IBinder? {
		if (intent?.action != ACTION_QUICK_BUILD) {
			log.debug("Rejecting bind request: action={}", intent?.action)
			return null
		}
		return binder
	}

	/**
	 * The AIDL surface the proxy app calls, publishing every accepted call to [connections].
	 *
	 * @property connections supplies the expected uid every inbound call is checked against,
	 *   and receives whatever survives that check
	 */
	internal class HostBinder(
		private val connections: ProxyAppConnections,
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

			// Clear the registration if this proxy app process dies so deploys fail
			// fast as NotConnected instead of timing out on a dead binder.
			runCatching {
				target.asBinder().linkToDeath(
					{ connections.onDisconnected() },
					0,
				)
			}

			log.info("Proxy app {} connected at generation {}", packageName, runningGeneration)
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
			log.info("Proxy app {} disconnected", packageName)
			connections.onDisconnected()
		}

		/**
		 * Throws unless the caller is the proxy app the live session accepts.
		 *
		 * @param op the AIDL method name, for the rejection log and message only
		 * @throws SecurityException when no session is live, or the calling uid is not the
		 *   one PackageManager reported for the installed proxy app
		 */
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
		private val log = LoggerFactory.getLogger("QB-HostService")

		/** Matches the manifest intent-filter and the runtime's bind intent. */
		const val ACTION_QUICK_BUILD = "com.itsaky.androidide.QUICK_BUILD_ACTION"
	}
}
