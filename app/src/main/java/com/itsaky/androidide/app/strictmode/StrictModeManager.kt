package com.itsaky.androidide.app.strictmode

import android.os.StrictMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * @author Akash Yadav
 */
object StrictModeManager {

	private val logger = LoggerFactory.getLogger(StrictModeManager::class.java)

	@Volatile
	private var _config = StrictModeConfig.DEFAULT
	private val mutex = Mutex()

	/**
	 * Get the current strict mode configuration.
	 */
	val config: StrictModeConfig
		get() = _config

	/**
	 * Install strict mode configuration in the current runtime.
	 *
	 * @param config The strict mode configuration to install.
	 */
	suspend fun install(config: StrictModeConfig) = mutex.withLock {
		if (_config !== StrictModeConfig.DEFAULT) {
			logger.warn("Attempt to re-install StrictMode configuration. Ignoring.")
			return@withLock
		}

		this._config = config

		if (!config.enabled) {
			// strict mode is disabled
			return@withLock
		}

		withContext(Dispatchers.Main.immediate) {
			// install strict mode on the main thread, so that the thread policy
			// applies to the main thread only

			StrictMode.setThreadPolicy(
				StrictMode.ThreadPolicy
					.Builder()
					.detectAll()
					.penaltyListener(config.executorService, ViolationDispatcher::onThreadViolation)
					.build(),
			)

			StrictMode.setVmPolicy(
				StrictMode.VmPolicy
					.Builder()
					.detectAll()
					.penaltyListener(config.executorService, ViolationDispatcher::onVmViolation)
					.build(),
			)
		}
	}
}