package org.appdevforall.cotg.quickbuild.service.deploy

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import org.slf4j.LoggerFactory

/**
 * Keeps the connected proxy app answerable for as long as a session can deploy to it.
 *
 * The proxy app is in the background for the whole edit loop - the developer is typing in
 * CoGo - so Android caches it and its freezer SIGSTOPs it after about a minute. A frozen
 * process runs no binder threads, so it never answers the reload handshake and every save
 * from then on fails the deploy timeout. The app's own outward binding to CoGo cannot
 * prevent this: a binding raises the priority of the process hosting the *service*, and the
 * `IQuickBuildTarget` callback CoGo holds is a plain binder object, which confers nothing.
 *
 * An interface so the lifecycle in [ProxyAppConnections] is unit-testable without binder.
 */
interface ProxyAppPriorityHold {
	/**
	 * Holds [packageName] out of the cached-app freezer, replacing any previous hold.
	 *
	 * Idempotent per package, so it is safe to call on every reconnect.
	 *
	 * @param packageName the installed proxy app to protect. Must be the package
	 *   PackageManager reported at session start, never one a caller sent over the binder -
	 *   this starts and keeps alive a process by name.
	 */
	fun hold(packageName: String)

	/** Drops the hold, letting the app be cached and frozen again. Idempotent. */
	fun release()
}

/**
 * The on-device [ProxyAppPriorityHold]: binds CoGo into the proxy app's keep-alive service, making
 * that process bound-service rather than cached so Android does not freeze it (measured on a
 * Galaxy A56, `freezer_cutoff_adj` 850; unbound, it is frozen ~66 s after losing the foreground).
 *
 * Taken only once the app has connected, so it never starts an app the user did not run, and
 * dropped on disconnect and at session end. Deliberately plain [Context.BIND_AUTO_CREATE] -
 * `BIND_ABOVE_CLIENT` or `BIND_IMPORTANT` would rank a background app over the IDE being typed in.
 *
 * @property bind binds CoGo into the named package's keep-alive service, returning what
 *   `bindService` returned; failures must surface as false rather than throw.
 * @property unbind tears the current binding down; must tolerate being called after a failed
 *   [bind], which is required to clear the framework's `ServiceConnection` registration.
 */
class BoundServicePriorityHold internal constructor(
	private val bind: (String) -> Boolean,
	private val unbind: () -> Unit,
) : ProxyAppPriorityHold {
	/** The package currently held, or null when nothing is. Guarded by `this`. */
	private var heldPackage: String? = null

	@Synchronized
	override fun hold(packageName: String) {
		if (heldPackage == packageName) return
		// A hold on a different package can only mean a new session's app; drop the old one
		// rather than stacking bindings.
		if (heldPackage != null) release()

		if (bind(packageName)) {
			heldPackage = packageName
			log.info("Holding proxy app {} out of the cached-app freezer", packageName)
			return
		}
		// bindService returning false still leaves the ServiceConnection registered, so the
		// unbind is required here or the framework reports a leaked connection and the next
		// hold binds a second time.
		unbind()
		log.warn(
			"Could not bind the keep-alive service of {}; it will be frozen ~1 min after it " +
				"leaves the foreground and saves will then time out",
			packageName,
		)
	}

	@Synchronized
	override fun release() {
		val held = heldPackage ?: return
		heldPackage = null
		unbind()
		log.info("Released the freezer hold on proxy app {}", held)
	}

	companion object {
		private val log = LoggerFactory.getLogger("QB-PriorityHold")

		/**
		 * The proxy app's keep-alive component, declared by the runtime AAR that every proxy
		 * app bakes in. Must match `QuickBuildKeepAliveService` and the Gradle plugin's
		 * `UNPROXIABLE_BY_NAME` entry that keeps the manifest transform from renaming it.
		 */
		const val KEEP_ALIVE_SERVICE = "com.itsaky.androidide.quickbuild.runtime.QuickBuildKeepAliveService"

		/**
		 * Builds a hold that binds from [context].
		 *
		 * @param context any CoGo context; only its application context is retained.
		 * @return a hold whose bind/unbind go to the real framework.
		 */
		fun forContext(context: Context): BoundServicePriorityHold {
			val appContext = context.applicationContext
			// One connection object for the lifetime of this hold: unbindService is keyed on
			// it, so a per-bind connection would make release() unable to match the bind.
			val connection =
				object : ServiceConnection {
					override fun onServiceConnected(
						name: ComponentName?,
						service: IBinder?,
					) {
						log.debug("Keep-alive connected: {}", name?.flattenToShortString())
					}

					override fun onServiceDisconnected(name: ComponentName?) {
						// The app's process died. The binding stays valid and the framework
						// reconnects if it comes back; the session's own disconnect handling is
						// what decides whether the hold is still wanted.
						log.debug("Keep-alive disconnected: {}", name?.flattenToShortString())
					}
				}
			return BoundServicePriorityHold(
				bind = { packageName ->
					val intent = Intent().setComponent(ComponentName(packageName, KEEP_ALIVE_SERVICE))
					runCatching { appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE) }
						.onFailure { log.warn("bindService to {} threw", packageName, it) }
						.getOrDefault(false)
				},
				unbind = {
					// Not-registered is the normal outcome after a failed bind, and is not worth
					// a warning.
					runCatching { appContext.unbindService(connection) }
						.onFailure { log.debug("unbindService: {}", it.toString()) }
					Unit
				},
			)
		}
	}
}
