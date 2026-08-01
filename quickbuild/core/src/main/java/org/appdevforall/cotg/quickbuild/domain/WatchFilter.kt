package org.appdevforall.cotg.quickbuild.domain

import java.io.File

/**
 * Decides whether a filesystem/save event is relevant to the quick-build session.
 * The watcher observes `src/`, `res/` and `assets/` roots plus the project's Gradle
 * files; everything else — build intermediates, editor temp files — is noise.
 *
 * CoGo's own editor writes in place (truncate + sequential write; see
 * [com.itsaky.androidide.editor.utils.ContentReadWrite.writeTo]) — its saves need no
 * special handling here. The temp-file names this filter drops come from EXTERNAL
 * atomic-rename tools instead: `sed -i`, `git checkout`/`stash`, vim with
 * `backupcopy=yes`, which write a sibling temp file and rename it over the target. This
 * filter only recognizes temp names by shape (dot-prefix, `~`, `.tmp`/`.swp`/`.bak`,
 * `.orig`/`.rej`); an
 * unrecognized one (e.g. `sed`'s `sedXXXXXX`) still reaches the coalesced batch and is
 * dropped later — once it no longer exists AND has no recognized project-file shape at
 * batch-settle time — see
 * [org.appdevforall.cotg.quickbuild.service.QuickBuildSessionManager.onWatcherBatch].
 */
class WatchFilter(
	watchedRoots: Collection<File>,
	watchedFiles: Collection<File> = emptyList(),
) {
	private val roots = watchedRoots.map { it.absoluteFile }
	private val files = watchedFiles.mapTo(HashSet()) { it.absoluteFile }

	fun isRelevant(file: File): Boolean {
		val abs = file.absoluteFile
		if (isTempArtifact(abs.name)) return false
		if (abs in files) return true

		val underRoot = roots.any { root -> abs.startsWith(root) }
		if (!underRoot) return false
		return !hasBuildSegment(abs)
	}

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

	private fun isTempArtifact(name: String): Boolean =
		name.startsWith(".") ||
			name.endsWith("~") ||
			name.endsWith(".tmp") ||
			name.endsWith(".swp") ||
			name.endsWith(".bak") ||
			// `patch`/merge droppings: a persisted `.orig`/`.rej` under `src/` would
			// otherwise classify UNSUPPORTED and force a spurious rebaseline (audit Gap B).
			name.endsWith(".orig") ||
			name.endsWith(".rej")
}
