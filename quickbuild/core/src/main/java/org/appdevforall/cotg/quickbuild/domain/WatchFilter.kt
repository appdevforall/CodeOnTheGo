package org.appdevforall.cotg.quickbuild.domain

import java.io.File

/**
 * Decides whether a filesystem event is relevant to the quick-build session: inside the
 * watched `src/`, `res/` and `assets/` roots or the watched Gradle files, and not a build
 * intermediate or a temp file.
 *
 * The temp names dropped here come from EXTERNAL atomic-rename tools (`sed -i`, `git
 * checkout`/`stash`, vim with `backupcopy=yes`), which write a sibling temp and rename it over
 * the target; CoGo's own editor writes in place and needs no special handling. Only temp names
 * of a recognized shape are dropped here - an unrecognized one such as `sed`'s `sedXXXXXX`
 * reaches the coalesced batch and is dropped at batch-settle time by
 * [org.appdevforall.cotg.quickbuild.service.QuickBuildSessionManager.onWatcherBatch].
 */
class WatchFilter(
	watchedRoots: Collection<File>,
	watchedFiles: Collection<File> = emptyList(),
) {
	private val roots = watchedRoots.map { it.absoluteFile }
	private val files = watchedFiles.mapTo(HashSet()) { it.absoluteFile }

	/** True when the session should react to a change at [file]. */
	fun isRelevant(file: File): Boolean {
		val abs = file.absoluteFile
		if (isTempArtifact(abs.name)) return false
		if (abs in files) return true

		val underRoot = roots.any { root -> abs.startsWith(root) }
		if (!underRoot) return false
		return !hasBuildSegment(abs)
	}

	/** True when [root] is this file or one of its ancestor directories. */
	private fun File.startsWith(root: File): Boolean {
		var current: File? = this
		while (current != null) {
			if (current == root) return true
			current = current.parentFile
		}
		return false
	}

	/** True when the path passes through a `build/` dir (Gradle intermediates). */
	private fun hasBuildSegment(file: File): Boolean {
		var current: File? = file.parentFile
		while (current != null) {
			if (current.name == "build") return true
			current = current.parentFile
		}
		return false
	}

	/** True for names an editor or rename-based tool leaves behind rather than real sources. */
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
