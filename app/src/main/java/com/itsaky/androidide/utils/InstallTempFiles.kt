package com.itsaky.androidide.utils

import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Shared `filesDir/temp` staging area for the .cgp/.cgt install flows (ExternalFileInstallViewModel,
 * and PluginManagerViewModel's ContentUri branch) - centralizes temp-file naming so both ViewModels
 * don't duplicate it, and sweeps orphans left behind by a hand-off that never completed (e.g.
 * process death between ExternalFileInstallViewModel sending ForwardToPluginManager and
 * PluginManagerActivity reading the pending-install-file extra).
 */
object InstallTempFiles {
	private val MAX_AGE_MS = TimeUnit.HOURS.toMillis(1)

	/** Creates a uniquely-named `<prefix>_<uuid>.<extension>` file under `filesDir/temp`. */
	fun newTempFile(
		filesDir: File,
		prefix: String,
		extension: String,
	): File {
		val tempDir = File(filesDir, "temp").apply { mkdirs() }
		sweepStale(tempDir)
		return File(tempDir, "${prefix}_${UUID.randomUUID()}.$extension")
	}

	private fun sweepStale(tempDir: File) {
		val cutoff = System.currentTimeMillis() - MAX_AGE_MS
		tempDir.listFiles()?.forEach { file ->
			if (file.lastModified() < cutoff) {
				file.delete()
			}
		}
	}
}
