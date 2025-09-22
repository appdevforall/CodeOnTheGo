package moe.shizuku.manager

import android.content.pm.PackageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import moe.shizuku.manager.model.ServiceStatus
import org.slf4j.LoggerFactory
import rikka.shizuku.Shizuku
import kotlin.coroutines.cancellation.CancellationException

/**
 * Helper to monitor state of the Shizuku service.
 *
 * This is a singleton and not a [ViewModel][androidx.lifecycle.ViewModel], because Shizuku is
 * independent of the activity/fragment lifecycle. Such Android components should still be
 * able to observe the state of the Shizuku service outside of their usual lifecycle.
 * See `WADBPermissionFragment` for an example.
 */
object ShizukuState {
	private val logger = LoggerFactory.getLogger(ShizukuState::class.java)

	private val _serviceStatus = MutableStateFlow(ServiceStatus.EMPTY)

	private val scope = CoroutineScope(Dispatchers.IO)

	/**
	 * The current status of the Shizuku service.
	 */
	val serviceStatus: StateFlow<ServiceStatus>
		get() = _serviceStatus.asStateFlow()

	val isRunning: Boolean
		get() = serviceStatus.value.isRunning

	/**
	 * Reload the current status of the Shizuku service.
	 */
	fun reload() =
		scope.async(Dispatchers.IO) {
			try {
				val status = loadServiceStatus()
				_serviceStatus.update { status }
				status
			} catch (_: CancellationException) {
				// ignored
				ServiceStatus.EMPTY
			} catch (tr: Throwable) {
				logger.warn("Failed to reload Shizuku server status", tr)
				ServiceStatus.EMPTY
			}
		}

	/**
	 * Load the current status of the Shizuku service.
	 */
	private fun loadServiceStatus(): ServiceStatus {
		if (!Shizuku.pingBinder()) {
			logger.debug("Shizuku service not running")
			return ServiceStatus.EMPTY
		}

		val uid = Shizuku.getUid()
		val apiVersion = Shizuku.getVersion()
		val patchVersion = Shizuku.getServerPatchVersion().let { if (it < 0) 0 else it }
		val seContext =
			if (apiVersion >= 6) {
				try {
					Shizuku.getSELinuxContext()
				} catch (tr: Throwable) {
					logger.warn("Failed to getSELinuxContext", tr)
					null
				}
			} else {
				null
			}

		val permissionTest =
			Shizuku.checkRemotePermission("android.permission.GRANT_RUNTIME_PERMISSIONS") == PackageManager.PERMISSION_GRANTED
		return ServiceStatus(uid, apiVersion, patchVersion, seContext, permissionTest)
	}
}
