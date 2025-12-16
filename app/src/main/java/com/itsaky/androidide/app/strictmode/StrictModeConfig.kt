package com.itsaky.androidide.app.strictmode

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Configuration for strict mode.
 *
 * @property enabled Whether strict mode should be enabled or not.
 * @property executorService The executor service on which the violation listener is invoked.
 * @author Akash Yadav
 */
data class StrictModeConfig(
	val enabled: Boolean,
	val executorService: ExecutorService = Executors.newSingleThreadExecutor()
)
