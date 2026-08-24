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
import java.nio.file.LinkOption

/**
 * Resolves [relativePath] against [baseDir], rejecting any attempt to escape outside it. Intended
 * for attacker-controllable input (e.g. the `{filename}` segment of a deep-link URL) that must never
 * be allowed to read/write outside a known root directory.
 *
 * Three layers, mirroring the zip-slip guard in
 * [com.itsaky.androidide.assets.AssetsInstallationHelper.extractZipToDir] and
 * `com.itsaky.androidide.utils.ZipUtils.unzipFile` (the `common` module's own copy, needed since
 * it can't depend on `app` to call this function directly). Any future fix to the containment
 * algorithm below must be applied in all three places:
 * 1. A lexical reject of an empty string, a `..` *segment*, or a leading `/` or `\` -- cheap,
 *    catches the common case outright. Per segment, not as a substring: `notes..txt` and
 *    `a..b/c.kt` are legitimate filenames, and a deep link to one has no business failing. Only a
 *    literal `..` segment can name a parent directory, so nothing is lost -- and layers 2 and 3
 *    below are what actually enforce containment in any case. (The sibling zip guards reached the
 *    same conclusion for the same reason; `ZipUtils.unzipFile` spells it out.) An empty string is
 *    rejected explicitly: [java.nio.file.Path.resolve] treats it as a no-op and returns [baseDir]
 *    itself unchanged, which would otherwise trivially pass the containment check below and violate
 *    this function's own "returns null" contract.
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
	// Split on both separators: '\' is not a path separator on Android, but a caller handing over a
	// Windows-style path should not have it silently treated as one long filename.
	if (relativePath.isEmpty() ||
		relativePath.startsWith("/") ||
		relativePath.startsWith("\\") ||
		relativePath.split('/', '\\').any { it == ".." }
	) {
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
		// NOFOLLOW_LINKS: plain Files.exists() follows symlinks, so a *dangling* symlink (one whose
		// target doesn't currently exist) would otherwise read as absent here, walking straight past
		// it to its parent instead of stopping to verify it -- toRealPath() below throws IOException
		// (caught at the bottom) for a genuinely dangling target, correctly rejecting the path instead
		// of silently trusting whatever ends up at the far side of it later.
		while (!Files.exists(existingAncestor, LinkOption.NOFOLLOW_LINKS)) {
			existingAncestor = existingAncestor.parent ?: return null
		}
		if (!existingAncestor.toRealPath().startsWith(realBase)) null else resolved.toFile()
	} catch (_: InvalidPathException) {
		null
	} catch (_: IOException) {
		null
	}
}
