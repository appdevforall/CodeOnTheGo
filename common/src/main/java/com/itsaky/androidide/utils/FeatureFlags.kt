package com.itsaky.androidide.utils

import android.os.Environment
import androidx.annotation.VisibleForTesting
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
	val leakCanaryDumpInhibited: Boolean = false,
	val quickBuildBenchEnabled: Boolean = false,
	val quickBuildWarmCompileDisabled: Boolean = false,
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
	private const val LEAKCANARY_FILE_NAME = "CodeOnTheGo.lc"
	private const val QUICK_BUILD_BENCH_FILE_NAME = "CodeOnTheGo.qbbench"
	private const val QUICK_BUILD_NO_SEED_FILE_NAME = "CodeOnTheGo.qbnoseed"

	private val logger = LoggerFactory.getLogger(FeatureFlags::class.java)

	private val mutex = Mutex()

	// The getters below read this without the mutex the sole writer holds. FlagsCache is
	// immutable, so publishing the reference is the whole fix - without it a reader can keep
	// seeing the direct-boot all-false snapshot after refresh() has replaced it.
	@Volatile
	private var flags = FlagsCache.DEFAULT

	/**
	 * Whether the flag files have been read from disk. Explicit rather than inferred from
	 * [flags] being non-[FlagsCache.DEFAULT]: a device-protected (direct boot) read sees no
	 * external storage at all, so it produces the same all-false snapshot as a genuine read
	 * of a device with no flag files, and an identity check cannot tell the two apart.
	 */
	private var loaded = false

	// Lazy so JVM unit tests that install a flagFileResolver never touch android.os.Environment.
	private val downloadsDir: File by lazy {
		Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
	}

	/**
	 * Resolves a flag sentinel file by name. Test seam: unit tests point this at a temp dir
	 * (or throw from it to exercise the failed-read path); production resolves against the
	 * shared Downloads dir, which needs a real Android environment.
	 */
	@VisibleForTesting
	internal var flagFileResolver: (String) -> File = { name -> File(downloadsDir, name) }

	/** Drops the cached snapshot and the [loaded] latch so a test starts from process-fresh state. */
	@VisibleForTesting
	internal fun resetForTest() {
		flags = FlagsCache.DEFAULT
		loaded = false
	}

	/**
	 * Whether Code On the Go experiments are enabled.
	 *
	 * Read from the sentinel file once per process and cached, so adding or deleting the file
	 * changes nothing in an app that is already running - including one that was only
	 * backgrounded. Toggling a flag needs a force-stop, not a relaunch from Recents, and any
	 * test step that flips one has to say so.
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
	 * Whether LeakCanary heap dumping is inhibited (CodeOnTheGo.lc present in Downloads).
	 */
	val isLeakCanaryDumpInhibited: Boolean
		get() = flags.leakCanaryDumpInhibited

	/**
	 * Whether the Quick Build benchmark hooks are enabled (CodeOnTheGo.qbbench present in
	 * Downloads). Gates the adb-triggerable bench activity and the JSON-lines event file
	 * (ADFA-4128); always paired with [isExperimentsEnabled]. Off in shipping builds.
	 */
	val isQuickBuildBenchEnabled: Boolean
		get() = flags.quickBuildBenchEnabled

	/**
	 * Whether the Quick Build background warm compile is disabled (CodeOnTheGo.qbnoseed present in
	 * Downloads). Bench-only A/B seam (ADFA-4128), inert unless [isQuickBuildBenchEnabled]
	 * is also on - the DI wiring pairs the two.
	 */
	val isQuickBuildWarmCompileDisabled: Boolean
		get() = flags.quickBuildWarmCompileDisabled

	/**
	 * Initialize feature flag values. This is thread-safe and idempotent i.e.
	 * subsequent calls do not access disk. Use [refresh] to re-read.
	 */
	suspend fun initialize(): Unit =
		mutex.withLock {
			if (loaded) {
				return@withLock
			}
			load()
		}

	/**
	 * Re-read the flag files, replacing the cached snapshot.
	 *
	 * The startup read can happen in direct boot mode, where external storage is not
	 * mounted and every flag therefore reads as absent. That snapshot must not be allowed
	 * to stick, so the phase that runs once credential-protected storage is available
	 * re-reads instead of relying on [initialize] being a no-op by then.
	 */
	suspend fun refresh(): Unit = mutex.withLock { load() }

	/** Reads every flag file. Call under [mutex]. */
	private suspend fun load() {
		fun checkFlag(fileName: String) = flagFileResolver(fileName).exists()

		val read =
			withContext(Dispatchers.IO) {
				runCatching {
					logger.info("Loading feature flags...")
					FlagsCache(
						experimentsEnabled = checkFlag(EXPERIMENTS_FILE_NAME),
						debugLoggingEnabled = checkFlag(LOGD_FILE_NAME),
						emulatorUseEnabled = checkFlag(EMULATOR_FILE_NAME),
						reprieveEnabled = checkFlag(REPRIEVE_FILE_NAME),
						pardonEnabled = checkFlag(PARDON_FILE_NAME),
						leakCanaryDumpInhibited = checkFlag(LEAKCANARY_FILE_NAME),
						quickBuildBenchEnabled = checkFlag(QUICK_BUILD_BENCH_FILE_NAME),
						quickBuildWarmCompileDisabled = checkFlag(QUICK_BUILD_NO_SEED_FILE_NAME),
					)
				}
			}
		// A read that threw keeps the previous snapshot (all-off at startup) and leaves
		// `loaded` false, so a later call retries instead of latching the failure.
		flags =
			read.getOrElse { error ->
				logger.error("Failed to load feature flags. Falling back to default values.", error)
				return@load
			}
		loaded = true
	}
}
