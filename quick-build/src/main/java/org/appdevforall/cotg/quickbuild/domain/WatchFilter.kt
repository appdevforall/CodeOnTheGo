package org.appdevforall.cotg.quickbuild.domain

import java.io.File

/**
 * Decides whether a filesystem/save event is relevant to the quick-build session.
 * The watcher observes `src/`, `res/` and `assets/` roots plus the project's Gradle
 * files; everything else — build intermediates, editor temp files — is noise.
 *
 * Editor saves are atomic temp+rename, so the watcher listens for close/move events and
 * this filter drops the temp-file names those saves produce along the way.
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
			name.endsWith(".bak")
}
