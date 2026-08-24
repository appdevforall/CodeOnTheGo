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
import java.nio.file.Path

/**
 * Decides whether a relative path is safely inside a base directory -- the one containment
 * algorithm in the codebase, and the only place it should be implemented.
 *
 * Built for attacker-controllable input: the `{filename}` segment of a deep-link URL, and the entry
 * names in a zip. It lives in `common` because it is plain `java.io`/`java.nio` with no Android
 * dependency, so both the app and this module's own [ZipUtils] can call it. Three near-copies used
 * to exist, each with a comment asking whoever fixed one to remember the other two; they had
 * already drifted apart on the `..` rule by the time this replaced them.
 *
 * Three layers:
 * 1. A lexical reject of an empty string, a `..` *segment*, or a leading `/` or `\`. Per segment,
 *    not as a substring: `notes..txt` and `a..b/c.kt` are legitimate filenames, and only a literal
 *    `..` segment can name a parent directory, so nothing is lost.
 * 2. Resolve + normalize against the base and verify with [java.nio.file.Path.startsWith] (not
 *    string prefix matching, which would wrongly accept `/project` as inside `/project-evil`). This
 *    operates on Java's own resolved path, so it is not fooled by however `..` reached the string.
 * 3. Resolve the nearest *existing* ancestor of that path to its real, on-disk path via
 *    [java.nio.file.Path.toRealPath] and re-verify containment -- layer 2 is purely lexical and
 *    will not catch a symlink already present inside the base (a project cloned with git, an
 *    installer directory reused across runs). Walking up to the nearest existing ancestor handles a
 *    path that does not exist yet. Skipped when the base itself does not exist: there is nothing on
 *    disk to symlink-escape through.
 *
 * What this deliberately does *not* decide is what to do about an existing symlink *at the target*
 * whose destination is still inside the base. Callers disagree: unzipping leaves a user's own
 * `gradlew` symlink alone, the asset installer refuses to write through any symlink at all, and the
 * deep-link reader is content to follow one. That is policy, and it stays visible at each call site
 * rather than being buried here.
 *
 * Holds no state beyond the base directory, and verifies against the filesystem on every call --
 * see [resolve] for why it does not memoize what it has already proven.
 */
class ContainedPathResolver(
	baseDir: File,
) {
	private val base: Path = baseDir.toPath().toAbsolutePath().normalize()

	// Null when the base does not exist on disk, which makes layer 3 unnecessary.
	private val realBase: Path? =
		try {
			if (Files.exists(base)) base.toRealPath() else null
		} catch (_: IOException) {
			null
		}

	/**
	 * The resolved file [relativePath] names inside the base directory, or null when it is invalid
	 * or escapes -- including when it is not a representable path at all (a decoded NUL byte, say:
	 * `Uri.pathSegments` percent-decodes before this ever sees the string, so `%00` arrives as a
	 * literal NUL, which [java.nio.file.Path] rejects rather than silently ignoring).
	 */
	fun resolve(relativePath: String): File? {
		// Split on both separators: '\' is not a path separator on Android, but a caller handing
		// over a Windows-style path should not have it treated as one long filename.
		if (relativePath.isEmpty() ||
			relativePath.startsWith("/") ||
			relativePath.startsWith("\\") ||
			relativePath.split('/', '\\').any { it == ".." }
		) {
			return null
		}

		return try {
			val resolved = base.resolve(relativePath).normalize()
			if (!resolved.startsWith(base)) {
				return null
			}

			val realBase = realBase ?: return resolved.toFile()

			var ancestor = resolved
			// NOFOLLOW_LINKS: plain Files.exists() follows symlinks, so a *dangling* symlink (one
			// whose target does not currently exist) would read as absent here, walking straight
			// past it to its parent instead of stopping to verify it. toRealPath() below throws
			// IOException for a genuinely dangling target, correctly rejecting the path rather than
			// trusting whatever ends up on the far side of it later.
			while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
				ancestor = ancestor.parent ?: return null
			}
			// Re-resolved on every call, deliberately. Caching a directory once proven contained saves
			// a toRealPath() per entry, but it answers later paths under that directory without
			// looking -- so if anything replaces it with a symlink in between, the answer is stale and
			// the caller writes through the link. The cache was measured against a real 1.8 GB asset
			// installation on device and bought nothing: 48.0 s without it, 51.4 s with it, 51.3 s for
			// the hand-rolled cache it replaced. Extraction is I/O and inflate; this is noise
			// (ADFA-5257 review).
			if (!ancestor.toRealPath().startsWith(realBase)) {
				return null
			}
			resolved.toFile()
		} catch (_: InvalidPathException) {
			null
		} catch (_: IOException) {
			null
		}
	}
}

/**
 * [ContainedPathResolver.resolve] for a single path, where there is nothing to reuse a resolver for.
 * Prefer the class when validating many paths against one base -- a zip's entries, say.
 */
fun resolveWithinDirectory(
	baseDir: File,
	relativePath: String,
): File? = ContainedPathResolver(baseDir).resolve(relativePath)
