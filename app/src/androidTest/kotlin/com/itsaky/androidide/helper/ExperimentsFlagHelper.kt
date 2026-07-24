package com.itsaky.androidide.helper

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.itsaky.androidide.utils.FeatureFlags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals

private const val EXPERIMENTS_FLAG_PATH = "/sdcard/Download/CodeOnTheGo.exp"

/**
 * Flips [FeatureFlags.isExperimentsEnabled] for a test. The flag is a sentinel file in
 * Downloads that [FeatureFlags.initialize] reads exactly once per process, so this
 * (un)creates the file via shell (independent of the app's storage permission) and then
 * resets the cached flags via reflection so a re-initialize actually re-reads disk.
 * Reflection is deliberate: FeatureFlags has no test seam, and a loud reflection failure
 * here beats a production-only test hook.
 */
fun setExperimentsFlagForTest(enabled: Boolean) {
	val instrumentation = InstrumentationRegistry.getInstrumentation()
	val command = if (enabled) "touch $EXPERIMENTS_FLAG_PATH" else "rm -f $EXPERIMENTS_FLAG_PATH"
	// Drain the output stream to EOF so the command has finished before we re-read flags.
	val fd = instrumentation.uiAutomation.executeShellCommand(command)
	ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }

	val flagsField =
		FeatureFlags::class.java
			.getDeclaredField("flags")
			.apply { isAccessible = true }
	val defaultFlags =
		Class
			.forName("com.itsaky.androidide.utils.FlagsCache")
			.getDeclaredField("DEFAULT")
			.apply { isAccessible = true }
			.get(null)
	// initialize() only touches disk while the cache is the DEFAULT singleton instance.
	flagsField.set(FeatureFlags, defaultFlags)
	runBlocking { FeatureFlags.initialize() }

	assertEquals(
		"FeatureFlags did not pick up $EXPERIMENTS_FLAG_PATH (is all-files access granted?)",
		enabled,
		FeatureFlags.isExperimentsEnabled,
	)
}
