package com.itsaky.androidide.utils

import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File

private data class FlagsCache(
	val experimentsEnabled: Boolean = false,
	val debugLoggingEnabled: Boolean = false,
	val emulatorUseEnabled: Boolean = false,
	val reprieveEnabled: Boolean = false,
    val pardonEnabled: Boolean = false,
    val ndkEnabled: Boolean = false,
) {
	companion object {
		/**
		 * Default flags.
		 */
		val DEFAULT = FlagsCache()
	}
}

object FeatureFlags {
	private const val EXPERIMENTS_FILE_NAME = "CodeOnTheGo.exp"
	private const val LOGD_FILE_NAME = "CodeOnTheGo.logd"
	private const val EMULATOR_FILE_NAME = "S153.txt"
	private const val REPRIEVE_FILE_NAME = "CodeOnTheGo.a3s19"
    private const val PARDON_FILE_NAME = "CodeOnTheGo.a2s2"
    private const val NDK_FILE_NAME = "CodeOnTheGo.ndk"

	private val logger = LoggerFactory.getLogger(FeatureFlags::class.java)

	private val mutex = Mutex()
	private var flags = FlagsCache.DEFAULT

	private val downloadsDir =
		Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

	/**
	 * Whether Code On the Go experiments are enabled.
	 */
	val isExperimentsEnabled: Boolean
		get() = flags.experimentsEnabled

	/**
	 * Whether debug log *reporting* is enabled or not.
	 */
	val isDebugLoggingEnabled: Boolean
		get() = flags.debugLoggingEnabled

	/**
	 * Whether emulator use is enabled or not.
	 */
	val isEmulatorUseEnabled: Boolean
		get() = flags.emulatorUseEnabled

	/**
	 * Whether reprieve is enabled or not.
	 */
	val isReprieveEnabled: Boolean
		get() = flags.reprieveEnabled

    /**
     * Whether pardon is enabled or not.
     */
    val isPardonEnabled: Boolean
        get() = flags.pardonEnabled

    /**
     * Whether ndk use is enabled or not.
     */
    val isNdkEnabled: Boolean
        get() = flags.ndkEnabled

    /**
	 * Initialize feature flag values. This is thread-safe and idempotent i.e.
	 * subsequent calls do not access disk.
	 */
	suspend fun initialize(): Unit =
		mutex.withLock {
			if (flags !== FlagsCache.DEFAULT) {
				// already initialized
				return@withLock
			}

			fun checkFlag(fileName: String) = File(downloadsDir, fileName).exists()

			flags =
				withContext(Dispatchers.IO) {
					runCatching {
						logger.info("Loading feature flags...")
						FlagsCache(
							experimentsEnabled = checkFlag(EXPERIMENTS_FILE_NAME),
							debugLoggingEnabled = checkFlag(LOGD_FILE_NAME),
							emulatorUseEnabled = checkFlag(EMULATOR_FILE_NAME),
							reprieveEnabled = checkFlag(REPRIEVE_FILE_NAME),
                            pardonEnabled = checkFlag(PARDON_FILE_NAME),
                            ndkEnabled = checkFlag(NDK_FILE_NAME),
						)
					}.getOrElse { error ->
						logger.error("Failed to load feature flags. Falling back to default values.", error)
						FlagsCache.DEFAULT
					}
				}
		}
}
