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

import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Decides whether a relative path is safely inside a base directory.
 *
 * Built for attacker-controllable input: the `{filename}` segment of a deep-link URL, and the entry
 * names in a zip. It lives in `common` because it is plain `java.io`/`java.nio` with no Android
 * dependency, so both the app and this module's own [ZipUtils] can call it. It replaces the copies
 * in [ZipUtils] and `AssetsInstallationHelper`, which had already drifted apart on the `..` rule.
 * Two hand-rolled containment checks remain unmigrated -- `ZipRecipeExecutor` and `PluginLoader`
 * (ADFA-5266) -- so this is not yet the only implementation in the tree.
 *
 * A `..` segment is rejected outright, which is stricter than the canonical-prefix check [ZipUtils]
 * used to apply: an entry like `a/../b.txt` normalizes back inside the base and used to extract, and
 * now fails the archive. That is a deliberate narrowing, matching what the asset installer already
 * enforced, and it fails loudly rather than silently.
 *
 * Three layers:
 * 1. A lexical reject of an empty string, a `..` *segment*, or a leading `/` or `\`. Per segment,
 *    not as a substring: `notes..txt` and `a..b/c.kt` are legitimate filenames, and only a literal
 *    `..` segment can name a parent directory, so nothing is lost.
 * 2. Resolve + normalize against the base and verify with [java.nio.file.Path.startsWith] (not
 *    string prefix matching, which would wrongly accept `/project` as inside `/project-evil`). This
 *    operates on Java's own resolved path, so it is not fooled by however `..` reached the string.
 * 3. Resolve the base and the nearest *existing* ancestor of that path to its real, on-disk path via
 *    [java.nio.file.Path.toRealPath] and re-verify containment -- layer 2 is purely lexical and
 *    will not catch a symlink already present inside the base (a project cloned with git, an
 *    installer directory reused across runs). Walking up to the nearest existing ancestor handles a
 *    path that does not exist yet. Skipped only when the base is *confirmed* absent -- there is then
 *    nothing on disk to symlink-escape through. A base that cannot be resolved is refused outright,
 *    never quietly downgraded to layer 2.
 *
 * What this deliberately does *not* decide is what to do about an existing symlink *at the target*
 * whose destination is still inside the base. The two callers disagree -- unzipping leaves a user's
 * own `gradlew` symlink alone, the asset installer refuses to write through any symlink -- so that
 * check stays visible at each call site rather than being buried here. Each caller applies it
 * *after* asking this class, so the check only ever sees a path already proven contained.
 *
 * Holds no state beyond the base directory, and verifies against the filesystem on every call --
 * see [resolve] for why it does not memoize what it has already proven.
 */
class ContainedPathResolver(
	baseDir: File,
) {
	private val log = LoggerFactory.getLogger(ContainedPathResolver::class.java)

	internal val base: Path = baseDir.toPath().toAbsolutePath().normalize()

	/**
	 * What [resolve] decided. Three states, not two: "escapes" and "could not be determined" both
	 * fail closed, but a caller that renders them with one message accuses a filesystem error of
	 * being a traversal attempt (ADFA-5257 review).
	 */
	sealed interface Resolution {
		/** Proven inside the base; [file] is the target to use. */
		data class Contained(
			val file: File,
		) : Resolution

		/**
		 * Refused: lexically invalid, escaping, naming the base itself, or sitting at/behind a
		 * symlink the filesystem check would not vouch for. [lexicalTarget] is the in-base path
		 * layer 2 produced when only the *filesystem* check refused -- handed out so a caller can
		 * report what actually sits there (an existing symlink, say) without re-deriving
		 * containment. Null when the name itself was rejected.
		 */
		data class Rejected(
			val lexicalTarget: Path?,
		) : Resolution

		/**
		 * Containment could not be determined: the base or the path's nearest existing ancestor
		 * failed to resolve for a reason other than absence (EACCES after a mode change, EIO).
		 * Not an escape -- refused because unproven, and [cause] says why.
		 */
		data class Unverifiable(
			val cause: IOException,
		) : Resolution
	}

	/**
	 * Layers 1 and 2 alone: [relativePath] resolved against the base and normalized, or null when
	 * it is lexically rejected, not a representable path (a decoded NUL byte, say: `Uri.pathSegments`
	 * percent-decodes before this ever sees the string, so `%00` arrives as a literal NUL, which
	 * [java.nio.file.Path] rejects), or does not land strictly inside the base. The one
	 * implementation of the lexical containment rule -- [resolve] builds on it rather than beside
	 * it, so a fallback judging [Resolution.Rejected.lexicalTarget] cannot drift from it
	 * (ADFA-5257 review).
	 */
	internal fun lexicalResolve(relativePath: String): Path? {
		if (isLexicallyRejected(relativePath)) {
			return null
		}
		val resolved =
			try {
				base.resolve(relativePath).normalize()
			} catch (_: InvalidPathException) {
				return null
			}
		if (resolved == base) {
			// "." and "./" normalize to the base itself -- not a path *inside* it, and a caller
			// treats a Contained result as a usable target.
			return null
		}
		if (!resolved.startsWith(base)) {
			return null
		}
		return resolved
	}

	/** How [resolve] judged [relativePath] against the base directory -- see [Resolution]. */
	fun resolve(relativePath: String): Resolution {
		val resolved = lexicalResolve(relativePath) ?: return Resolution.Rejected(null)

		// Resolved per call, not once in a constructor. Two reasons: nothing stops a caller from
		// constructing the resolver before the base exists, so a base pinned at construction could
		// stay null for the resolver's whole life and layer 3 would never run even once the tree is
		// created -- and an existing base can gain a symlink later, so a resolution proven once can
		// go stale. Also, notExists() is not !exists() -- both are false
		// when the answer cannot be determined (a parent denying execute), and treating that as
		// "absent, nothing to symlink through" is the same silent downgrade to lexical containment.
		// Confirmed-absent skips layer 3; anything else must resolve or be refused.
		val realBase =
			try {
				base.toRealPath()
				// Fully qualified on purpose: Kotlin auto-imports kotlin.io.NoSuchFileException, which
				// toRealPath() never throws, so catching that one would quietly disable this branch.
			} catch (_: java.nio.file.NoSuchFileException) {
				// Confirmed absent, so there is nothing on disk to symlink through and layer 3 has
				// nothing to check. (A base that is itself a dangling symlink lands here too; a write
				// under it fails at the write, and layer 2 still holds.) Distinguished from a failure
				// this way rather than via a separate notExists() probe, which costs a second stat and
				// answers "false" for both absent and undeterminable.
				null
			} catch (e: IOException) {
				log.warn("Cannot resolve {} to a real path; refusing every path under it", base, e)
				return Resolution.Unverifiable(e)
			}
		realBase ?: return Resolution.Contained(resolved.toFile())

		var ancestor = resolved
		// NOFOLLOW_LINKS: plain Files.exists() follows symlinks, so a *dangling* symlink (one
		// whose target does not currently exist) would read as absent here, walking straight
		// past it to its parent instead of stopping to verify it. toRealPath() below throws
		// IOException for a genuinely dangling target, correctly rejecting the path rather than
		// trusting whatever ends up on the far side of it later.
		while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
			ancestor = ancestor.parent ?: return Resolution.Rejected(resolved)
		}
		// Re-resolved on every call, deliberately. Caching a directory once proven contained saves
		// a toRealPath() per entry, but it answers later paths under that directory without
		// looking -- so if anything replaces it with a symlink in between, the answer is stale and
		// the caller writes through the link. The cache was measured against a real 1.8 GB asset
		// installation on device and bought nothing: 48.0 s without it, 51.4 s with it, 51.3 s for
		// the hand-rolled cache it replaced. Extraction is I/O and inflate; this is noise
		// (ADFA-5257 review).
		val realAncestor =
			try {
				ancestor.toRealPath()
				// Fully qualified for the same reason as the base branch above.
			} catch (_: java.nio.file.NoSuchFileException) {
				// Expected for a dangling symlink: the NOFOLLOW walk stopped at a link that exists,
				// but its target does not, so there is no real path to prove contained. Refused
				// without the warning below -- this is the link check working, not a failure.
				return Resolution.Rejected(resolved)
			} catch (e: IOException) {
				log.warn(
					"Cannot resolve {} (nearest existing ancestor of {}) to a real path; refusing the path",
					ancestor,
					relativePath,
					e,
				)
				return Resolution.Unverifiable(e)
			}
		if (!realAncestor.startsWith(realBase)) {
			return Resolution.Rejected(resolved)
		}
		return Resolution.Contained(resolved.toFile())
	}

	companion object {
		/**
		 * Layer 1 alone: whether [relativePath] is rejected before any filesystem look -- empty,
		 * absolute (leading `/` or `\`), or containing a literal `..` segment. Exposed so a caller
		 * with a more lenient fallback for paths [resolve] refused ([ZipUtils]' skip of an existing
		 * symlink) can apply the same reject first: an entry that fails here is a bad archive
		 * however the filesystem looks, never fallback material.
		 */
		internal fun isLexicallyRejected(relativePath: String): Boolean =
			// Split on both separators: '\' is not a path separator on Android, but a caller handing
			// over a Windows-style path should not have it treated as one long filename.
			relativePath.isEmpty() ||
				relativePath.startsWith("/") ||
				relativePath.startsWith("\\") ||
				relativePath.split('/', '\\').any { it == ".." }

		/**
		 * Whether [relativePath] merely names the base directory itself -- `.`, `./`, and
		 * equivalents. [resolve] deliberately refuses such a path (a Contained result must be a
		 * usable target *inside* the base), but an archive's root directory entry is a no-op to
		 * extract, not an escape -- some archivers emit one. Exposed so extraction call sites can
		 * apply that tolerance without loosening the resolver (ADFA-5257 review).
		 */
		fun namesBase(relativePath: String): Boolean =
			relativePath.isNotEmpty() && relativePath.split('/', '\\').all { it.isEmpty() || it == "." }
	}
}

/**
 * [ContainedPathResolver.resolve] for a single path, where there is nothing to reuse a resolver for,
 * collapsed to contained-or-not: the file when contained, null otherwise. Prefer the class when
 * validating many paths against one base -- a zip's entries, say -- or when the caller must tell an
 * escape from a filesystem failure ([ContainedPathResolver.Resolution] keeps them apart).
 *
 * Nothing in `common` calls this yet; the caller is the deep-link handler in the app module (#1651),
 * which validates one `{filename}` per request. Kept here rather than landing with that PR so both
 * entry points ship as one reviewed unit.
 */
fun resolveWithinDirectory(
	baseDir: File,
	relativePath: String,
): File? = (ContainedPathResolver(baseDir).resolve(relativePath) as? ContainedPathResolver.Resolution.Contained)?.file
