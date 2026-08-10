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

import java.io.File
import java.nio.file.InvalidPathException

/**
 * Resolves [relativePath] against [baseDir], rejecting any attempt to escape outside it. Intended
 * for attacker-controllable input (e.g. the `{filename}` segment of a deep-link URL) that must never
 * be allowed to read/write outside a known root directory.
 *
 * Two layers, mirroring the zip-slip guard in
 * [com.itsaky.androidide.assets.AssetsInstallationHelper.extractZipToDir]:
 * 1. A lexical reject of `..`/a leading `/` or `\` -- cheap, catches the common case outright.
 * 2. Resolve + normalize against [baseDir] and verify with [java.nio.file.Path.startsWith] (not
 *    string prefix matching, which would wrongly accept `/project` as inside `/project-evil`) --
 *    this is the authoritative check: it operates on Java's own resolved path, so it isn't fooled by
 *    however `..` made it into the string (a literal `..` segment is the only way a path can name a
 *    parent directory at all, however it got decoded).
 *
 * Returns `null` if [relativePath] is invalid or escapes [baseDir] -- including when it's not a
 * representable path at all (e.g. containing a decoded NUL byte, `Uri.pathSegments` percent-decodes
 * before this function ever sees the string, so `%00` arrives as a literal NUL character, which
 * [java.nio.file.Path] rejects with [InvalidPathException] rather than silently ignoring).
 */
fun resolveWithinDirectory(
	baseDir: File,
	relativePath: String,
): File? {
	if (relativePath.contains("..") || relativePath.startsWith("/") || relativePath.startsWith("\\")) {
		return null
	}

	return try {
		val base = baseDir.toPath().toAbsolutePath().normalize()
		val resolved = base.resolve(relativePath).normalize()
		if (!resolved.startsWith(base)) null else resolved.toFile()
	} catch (e: InvalidPathException) {
		null
	}
}
