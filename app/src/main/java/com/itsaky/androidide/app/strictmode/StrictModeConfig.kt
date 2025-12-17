package com.itsaky.androidide.app.strictmode

import com.itsaky.androidide.utils.FeatureFlags
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Configuration for strict mode.
 *
 * @property enabled Whether strict mode should be enabled or not.
 * @property isReprieveEnabled Whether to enable reprieve or not.
 * @property executorService The executor service on which the violation listener is invoked.
 * @author Akash Yadav
 */
data class StrictModeConfig(
	val enabled: Boolean,
	val isReprieveEnabled: Boolean = FeatureFlags.isReprieveEnabled,
	val executorService: ExecutorService = Executors.newSingleThreadExecutor(),
) {
	companion object {
		/**
		 * The default strict mode configuration.
		 */
		val DEFAULT = StrictModeConfig(enabled = true)
	}
}
