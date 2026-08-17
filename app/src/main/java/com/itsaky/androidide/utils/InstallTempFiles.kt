package com.itsaky.androidide.utils

import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Shared `filesDir/temp` staging area for the .cgp/.cgt install flows (ExternalFileInstallViewModel,
 * and PluginManagerViewModel's ContentUri branch) - centralizes temp-file naming so both ViewModels
 * don't duplicate it, and sweeps orphans left behind by a hand-off that never completed (e.g.
 * process death between ExternalFileInstallViewModel sending ForwardToPluginManager and
 * PluginManagerActivity reading the pending-install-file extra).
 */
object InstallTempFiles {
	private val MAX_AGE_MS = TimeUnit.HOURS.toMillis(1)

	// Stale entries can only ever appear once an hour (MAX_AGE_MS), so there's no point
	// re-scanning the directory on every single newTempFile() call - throttle to once per
	// interval instead of doing a full listFiles()+lastModified() pass every time. An AtomicLong
	// (rather than a plain var) since newTempFile() can be called concurrently from both
	// ExternalFileInstallViewModel and PluginManagerViewModel's coroutines.
	private val SWEEP_INTERVAL_MS = TimeUnit.MINUTES.toMillis(10)
	private val lastSweepAtMs = AtomicLong(0L)

	/** Creates a uniquely-named `<prefix>_<uuid>.<extension>` file under `filesDir/temp`. */
	fun newTempFile(
		filesDir: File,
		prefix: String,
		extension: String,
	): File {
		val tempDir = File(filesDir, "temp").apply { mkdirs() }
		sweepStaleIfDue(tempDir)
		return File(tempDir, "${prefix}_${UUID.randomUUID()}.$extension")
	}

	private fun sweepStaleIfDue(tempDir: File) {
		val now = System.currentTimeMillis()
		val last = lastSweepAtMs.get()
		if (now - last < SWEEP_INTERVAL_MS) return
		// Loses the race to another concurrent caller -> that caller's sweep already covers this
		// interval, so skip rather than sweep twice.
		if (!lastSweepAtMs.compareAndSet(last, now)) return

		val cutoff = now - MAX_AGE_MS
		tempDir.listFiles()?.forEach { file ->
			if (file.lastModified() < cutoff) {
				file.delete()
			}
		}
	}
}
