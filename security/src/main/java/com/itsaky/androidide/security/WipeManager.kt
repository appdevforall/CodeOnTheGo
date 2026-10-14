/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.security

import java.io.File

/**
 * What Instant Wipe destroys, resolved by the caller (`:app` owns the real paths like
 * `Environment.PROJECTS_DIR` and the Room database files -- `:security` must not depend on `:app`).
 *
 * @property secureTargets small, sensitive files/trees to overwrite-then-delete (project source, the
 *   Recents DB files). See [SecureShredder] for why "secure" is best-effort only.
 * @property fastDeleteTargets bulk, regenerable trees to plain-delete fast (Gradle/build/`cacheDir`);
 *   overwriting these would defeat "instant" and buys nothing (they hold no user secrets).
 * @property secureTargetSkipDirs directory names within [secureTargets] to fast plain-delete instead of
 *   shredding (regenerable caches like `build` / `.gradle` -- keeps the shred fast).
 * @property markerFiles tripwire files to create after a real wipe (e.g. `.pip` / `.pip2`).
 */
data class WipePlan(
	val secureTargets: List<File>,
	val fastDeleteTargets: List<File>,
	val markerFiles: List<File>,
	val secureTargetSkipDirs: Set<String> = emptySet(),
)

/**
 * The Android-side work Instant Wipe needs but `:security` cannot reach across the module boundary;
 * `:app` implements it. All of it is kept behind this interface so [WipeManager] stays a pure,
 * unit-testable orchestrator over [File]s.
 */
interface WipeTeardown {
	/**
	 * Release every runtime hold on the targets BEFORE anything is deleted: cancel and stop the
	 * Gradle build service, stop the Termux session, close the project (`IProjectManager.destroy()`)
	 * and the editor tab cache, and close the Recents Room database (so no live connection resurrects
	 * its file). Called in both real and dry-run modes.
	 */
	fun stopEverything()

	/** Reset open-project bookkeeping so nothing reopens a deleted dir (`lastOpenedProject`, etc.). */
	fun resetBookkeeping()

	/** Blank the security/app preferences, leaving the post-destruct state. */
	fun clearPreferences()
}

/** Outcome of a [WipeManager.wipe] call; [enumeratedTargets] is populated in dry-run too. */
data class WipeResult(
	val dryRun: Boolean,
	val secureShredded: Int,
	val fastDeleted: Int,
	val markersWritten: Int,
	val enumeratedTargets: List<String>,
)

/**
 * Orchestrates Instant Wipe (ADFA-6510): stop the world, destroy the user's work, reset state, drop
 * the tripwire markers. Presenting the disguise ([CryptogramActivity]) is the caller's job.
 *
 * In **dry-run** mode (the `/sdcard/Download/CodeOnTheGo.nowipe` QA override, see
 * [SecurityOverride.isWipeDryRun]) it runs the real teardown and enumerates every target for the log,
 * but destroys nothing and leaves prefs/markers untouched -- so the irreversible feature can be
 * exercised on a real device without data loss.
 */
object WipeManager {
	/**
	 * @param log sink for human-readable progress (an advisory toast / logcat); never contains the
	 *   file contents, only paths and counts.
	 */
	fun wipe(
		plan: WipePlan,
		teardown: WipeTeardown,
		dryRun: Boolean,
		log: (String) -> Unit = {},
	): WipeResult {
		val enumerated = (plan.secureTargets + plan.fastDeleteTargets).map { it.absolutePath }
		log("Instant Wipe: ${enumerated.size} target(s)${if (dryRun) " (DRY RUN)" else ""}")
		enumerated.forEach { log("  target: $it") }

		// Release handles first, in both modes -- a delete while the build/editor holds files fails
		// piecemeal, and running teardown in dry-run is how QA verifies it without destroying data.
		teardown.stopEverything()

		if (dryRun) {
			log("DRY RUN: nothing destroyed; preferences and markers left untouched")
			return WipeResult(dryRun = true, 0, 0, 0, enumerated)
		}

		// Drop the durable wipe markers FIRST, before any destruction or pref-clearing. If the process
		// dies mid-wipe (user swipes the app away, OS kill), the next launch must still read isWiped and
		// stay the disguise -- independent of the now-blank prefs and of whether the alias swap succeeded
		// (ADFA-6510 review F02). Safe to write before the shred: the markers live in filesDir /
		// noBackupFilesDir, outside every secure-shred and fast-delete target.
		var markers = 0
		plan.markerFiles.forEach { marker ->
			runCatching {
				marker.parentFile?.mkdirs()
				marker.createNewFile()
				if (marker.exists()) markers++
			}
		}

		var shredded = 0
		plan.secureTargets.forEach { shredded += SecureShredder.shredTree(it, skipDirNames = plan.secureTargetSkipDirs) }

		var deleted = 0
		plan.fastDeleteTargets.forEach { target ->
			runCatching { if (!target.exists() || target.deleteRecursively()) deleted++ }
		}

		teardown.resetBookkeeping()
		teardown.clearPreferences()

		log("Instant Wipe complete: shredded=$shredded deleted=$deleted markers=$markers")
		return WipeResult(dryRun = false, shredded, deleted, markers, enumerated)
	}
}
