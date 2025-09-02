package moe.shizuku.manager

import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.buildinfo.BuildInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import moe.shizuku.manager.model.ServiceStatus
import moe.shizuku.manager.utils.ShizukuSystemApis
import org.slf4j.LoggerFactory
import rikka.shizuku.Shizuku
import kotlin.coroutines.cancellation.CancellationException

class ShizukuViewModel : ViewModel() {
	companion object {
		private val logger = LoggerFactory.getLogger(ShizukuViewModel::class.java)
	}

	private val _serviceStatus = MutableStateFlow(ServiceStatus.EMPTY)

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
		viewModelScope.async(Dispatchers.IO) {
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

		// Before a526d6bb, server will not exit on uninstall, manager installed later will get not permission
		// Run a random remote transaction here, report no permission as not running
		ShizukuSystemApis.checkPermission(
			Manifest.permission.API_V23,
			BuildInfo.PACKAGE_NAME,
			0,
		)
		return ServiceStatus(uid, apiVersion, patchVersion, seContext, permissionTest)
	}
}
