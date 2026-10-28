package com.itsaky.androidide.helper

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.itsaky.androidide.utils.Experiment
import com.itsaky.androidide.utils.Experiments
import com.itsaky.androidide.utils.FeatureFlags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals

private const val EXPERIMENTS_FLAG_PATH = "/sdcard/Download/CodeOnTheGo.exp"

/**
 * Whether the experiments sentinel file currently exists on disk. Lets a test snapshot
 * the pre-test state and restore it in its after-block instead of unconditionally
 * deleting the flag (which strips it from a dev device that had it enabled).
 */
fun isExperimentsFlagSet(): Boolean = java.io.File(EXPERIMENTS_FLAG_PATH).exists()

/**
 * Turns [experiment] on or off for a test. Every experiment shares one sentinel file in
 * Downloads, so this (un)creates it via shell (independent of the app's storage permission)
 * and then [FeatureFlags.refresh]es, since the startup read is cached for the process.
 */
fun setExperimentForTest(
	experiment: Experiment,
	enabled: Boolean,
) {
	val instrumentation = InstrumentationRegistry.getInstrumentation()
	val command = if (enabled) "touch $EXPERIMENTS_FLAG_PATH" else "rm -f $EXPERIMENTS_FLAG_PATH"
	// Drain the output stream to EOF so the command has finished before we re-read flags.
	val fd = instrumentation.uiAutomation.executeShellCommand(command)
	ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }

	runBlocking { FeatureFlags.refresh() }

	assertEquals(
		"$experiment did not follow $EXPERIMENTS_FLAG_PATH (is all-files access granted?)",
		enabled,
		Experiments.isOn(experiment),
	)
}
