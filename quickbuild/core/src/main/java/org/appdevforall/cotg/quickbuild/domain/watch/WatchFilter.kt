package org.appdevforall.cotg.quickbuild.domain.watch

import java.io.File

/**
 * Decides whether a filesystem event is relevant to the quick-build session: inside the watched
 * `src/`, `res/` and `assets/` roots or the watched Gradle files, and not a build intermediate or
 * a temp file. The temp names dropped here come from EXTERNAL atomic-rename tools (`sed -i`, `git
 * checkout`/`stash`, vim with `backupcopy=yes`); only recognized shapes are dropped here, an
 * unrecognized one such as `sed`'s `sedXXXXXX` later, by [WatcherBatchReconciler].
 *
 * @param watchedRoots directories whose subtrees are relevant, `build/` excepted; resolved to
 *   absolute paths once at construction, so later relative-path callers still match.
 * @param watchedFiles individual files that are relevant wherever they sit (the manifest and the
 *   gradle files), matched exactly rather than by subtree.
 */
class WatchFilter(
	watchedRoots: Collection<File>,
	watchedFiles: Collection<File> = emptyList(),
) {
	private val roots = watchedRoots.map { it.absoluteFile }
	private val files = watchedFiles.mapTo(HashSet()) { it.absoluteFile }

	/**
	 * True when the session should react to a change at [file].
	 *
	 * @param file the changed path, absolute or relative; it need not still exist, since deletions
	 *   are filtered by the same rules.
	 * @return true to pass the event to the session; false drops it silently, so a watched file
	 *   wrongly excluded here becomes a stale build with no warning.
	 */
	fun isRelevant(file: File): Boolean {
		val abs = file.absoluteFile
		if (isTempArtifact(abs.name)) return false
		if (abs in files) return true

		val underRoot = roots.any { root -> abs.startsWith(root) }
		if (!underRoot) return false
		return !hasBuildSegment(abs)
	}

	/**
	 * True when [root] is this file or one of its ancestor directories.
	 *
	 * @receiver an absolute path, so the walk terminates at the filesystem root.
	 * @param root an already-absolute watched root; equality with the receiver counts as a match.
	 * @return true when the receiver lies in [root]'s subtree, comparing path segments only - no
	 *   symlink resolution, so a link into a watched root does not match.
	 */
	private fun File.startsWith(root: File): Boolean {
		var current: File? = this
		while (current != null) {
			if (current == root) return true
			current = current.parentFile
		}
		return false
	}

	/**
	 * True when the path passes through a `build/` dir OUTSIDE any `src/` (Gradle intermediates).
	 *
	 * The walk stops at the `src` boundary because Gradle's `build/` is a module-root sibling of
	 * `src/`, never inside it, while `build` is a legal Kotlin/Java package name: an unbounded walk
	 * drops `src/main/java/com/example/build/Builders.kt` upstream of both the inotify and poll
	 * channels, so that save reaches nothing at all - no build, no batch, no warning.
	 *
	 * @param file the changed path; only its ancestors are examined, so a source file itself named
	 *   `build` is not excluded.
	 * @return true to exclude the path as a build intermediate.
	 */
	private fun hasBuildSegment(file: File): Boolean {
		var current: File? = file.parentFile
		var sawBuild = false
		while (current != null) {
			// Reached from below, so a `src` ancestor proves every `build` seen so far sits
			// inside a source set and is therefore a package, not an intermediate.
			if (current.name == "src") return false
			if (current.name == "build") sawBuild = true
			current = current.parentFile
		}
		return sawBuild
	}

	/**
	 * True for names an editor or rename-based tool leaves behind rather than real sources.
	 *
	 * @param name the file's simple name, never a path - every test here is a prefix or suffix
	 *   match on that name alone.
	 * @return true to drop the event; unrecognized temp shapes return false and are dropped later,
	 *   at batch-settle time.
	 */
	private fun isTempArtifact(name: String): Boolean =
		name.startsWith(".") ||
			name.endsWith("~") ||
			name.endsWith(".tmp") ||
			name.endsWith(".swp") ||
			name.endsWith(".bak") ||
			// A persisted `patch`/merge dropping under `src/` would otherwise classify
			// UNSUPPORTED and force a spurious rebaseline.
			name.endsWith(".orig") ||
			name.endsWith(".rej")
}
