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

package com.itsaky.androidide.utils

import org.junit.Assume
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Creates a symlink at [link] pointing to [target], or skips the calling test (via [Assume]) when
 * this environment cannot create one: a filesystem without symlink support (FAT32 throws
 * [UnsupportedOperationException]), or Windows NTFS without the elevated/Developer Mode privilege
 * (a [FileSystemException] whose reason names the missing privilege). Any other
 * [FileSystemException] is a real failure and is rethrown -- silently swallowing it into a skip
 * would take a symlink assertion out of CI without anyone noticing.
 */
fun createSymlinkOrSkipTest(
	link: Path,
	target: Path,
) {
	val created =
		try {
			Files.createSymbolicLink(link, target)
			true
		} catch (_: UnsupportedOperationException) {
			false
		} catch (e: FileSystemException) {
			if (e.reason?.contains("privilege", ignoreCase = true) != true) throw e
			false
		}
	// Report as skipped, not silently passed.
	Assume.assumeTrue("Symlinks are not supported/permitted on this filesystem", created)
}
