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

import android.os.Environment
import java.io.File

/**
 * One-way, file-based escape hatches for the security features. There is no UI path that weakens
 * either protection; both are enabled only by placing a magic file in Downloads, so they are a
 * deliberate, visible QA/testing opt-out rather than a setting a user could flip by accident.
 */
object SecurityOverride {
	/** FLAG_SECURE override (ADFA-40): `/sdcard/Download/CodeOnTheGo.mfs`. */
	private const val OVERRIDE_FILE_NAME = "CodeOnTheGo.mfs"

	/** Instant Wipe dry-run override (ADFA-6510): `/sdcard/Download/CodeOnTheGo.nowipe`. */
	private const val WIPE_DRY_RUN_FILE_NAME = "CodeOnTheGo.nowipe"

	/** True when the override file is present, i.e. FLAG_SECURE should NOT be applied. */
	fun isScreenshotBlockingDisabled(): Boolean = downloadsFileExists(OVERRIDE_FILE_NAME)

	/**
	 * True when the dry-run file is present, i.e. Instant Wipe must enumerate and log its targets but
	 * destroy nothing. Lets the irreversible feature be developed and tested without data loss.
	 *
	 * This is dev/QA scaffolding and MUST be removed before the feature ships (ADFA-6510): a shipped
	 * dry-run path is a silent kill-switch defeat.
	 */
	fun isWipeDryRun(): Boolean = downloadsFileExists(WIPE_DRY_RUN_FILE_NAME)

	private fun downloadsFileExists(name: String): Boolean =
		runCatching {
			File(
				Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
				name,
			).exists()
		}.getOrDefault(false)
}
