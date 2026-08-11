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
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException

/**
 * Resolves [relativePath] against [baseDir], rejecting any attempt to escape outside it. Intended
 * for attacker-controllable input (e.g. the `{filename}` segment of a deep-link URL) that must never
 * be allowed to read/write outside a known root directory.
 *
 * Three layers, mirroring the zip-slip guard in
 * [com.itsaky.androidide.assets.AssetsInstallationHelper.extractZipToDir]:
 * 1. A lexical reject of `..`/a leading `/` or `\` -- cheap, catches the common case outright.
 * 2. Resolve + normalize against [baseDir] and verify with [java.nio.file.Path.startsWith] (not
 *    string prefix matching, which would wrongly accept `/project` as inside `/project-evil`) --
 *    this operates on Java's own resolved path, so it isn't fooled by however `..` made it into the
 *    string (a literal `..` segment is the only way a path can name a parent directory at all,
 *    however it got decoded).
 * 3. If [baseDir] exists on disk, resolve the nearest existing ancestor of the normalized path to
 *    its real, on-disk path via [java.nio.file.Path.toRealPath] and re-verify containment -- layer 2
 *    is purely lexical and won't catch a symlink already present inside [baseDir] (e.g. a project
 *    cloned with git, which supports symlinks) that points outside it. Walking up to the nearest
 *    *existing* ancestor (rather than the resolved path itself) handles callers resolving a path
 *    that doesn't exist yet. Skipped when [baseDir] itself doesn't exist -- there is nothing on disk
 *    to symlink-escape through, so the lexical check above is already authoritative.
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
		if (!resolved.startsWith(base)) {
			return null
		}

		if (!Files.exists(base)) {
			return resolved.toFile()
		}

		val realBase = base.toRealPath()
		var existingAncestor = resolved
		while (!Files.exists(existingAncestor)) {
			existingAncestor = existingAncestor.parent ?: return null
		}
		if (!existingAncestor.toRealPath().startsWith(realBase)) null else resolved.toFile()
	} catch (_: InvalidPathException) {
		null
	} catch (_: IOException) {
		null
	}
}
