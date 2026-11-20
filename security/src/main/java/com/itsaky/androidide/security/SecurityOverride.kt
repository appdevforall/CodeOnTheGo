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
 * A one-way, file-based escape hatch for the Instant Hide screenshot block (ADFA-40). Screenshot
 * blocking is on by default whenever Instant Hide is armed; placing the magic file disables it, so
 * QA can capture screenshots and bug reports still work. There is no UI path that weakens the block.
 */
object SecurityOverride {
	/** Testing override file: `/sdcard/Download/CodeOnTheGo.mfs`. */
	private const val OVERRIDE_FILE_NAME = "CodeOnTheGo.mfs"

	/** True when the override file is present, i.e. FLAG_SECURE should NOT be applied. */
	fun isScreenshotBlockingDisabled(): Boolean =
		runCatching {
			File(
				Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
				OVERRIDE_FILE_NAME,
			).exists()
		}.getOrDefault(false)
}
