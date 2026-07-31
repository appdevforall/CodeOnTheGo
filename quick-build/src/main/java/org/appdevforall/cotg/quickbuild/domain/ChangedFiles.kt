package org.appdevforall.cotg.quickbuild.domain

import java.io.File

/**
 * The set of files changed since the last successfully absorbed quick build.
 *
 * [Known] and [Unknown] are deliberately distinct types: an empty [Known] set means
 * "nothing changed" (a no-op save must NOT trigger a recompile), while [Unknown] means
 * "we cannot tell what changed" (crash recovery, missed watcher events) and forces the
 * next build to treat every source as potentially dirty. The ADFA-4128 prototype
 * conflated the two, which turned no-op saves into spurious full recompiles.
 */
sealed interface ChangedFiles {
	/** Union of two changed-sets. [Unknown] absorbs everything. */
	operator fun plus(other: ChangedFiles): ChangedFiles

	/** True only for an empty [Known] set — [Unknown] is never empty. */
	val isEmpty: Boolean

	/**
	 * @property files the paths modified or created since the last absorbed build.
	 * @property removed the paths DELETED since then (a `git pull`/branch-switch that drops
	 *   a tracked file, a Termux `rm`, a file-manager delete). Modeled distinctly from
	 *   [files] because a removal is classified by path SHAPE alone - the file is gone, so
	 *   nothing on disk can be re-stat'd - and it routes differently downstream: a removed
	 *   `.kt`/`.java` feeds the incremental compiler's removed-sources slot (its outputs are
	 *   deleted and dependents recompiled), while a live edit compiles the file itself. The
	 *   ADFA-4128 watcher never detected standalone deletions at all (Bug 12), so a deleted
	 *   class lingered in the running app until an unrelated edit fired a build.
	 */
	data class Known(
		val files: Set<File>,
		val removed: Set<File> = emptySet(),
	) : ChangedFiles {
		override fun plus(other: ChangedFiles): ChangedFiles =
			when (other) {
				is Known -> Known(files + other.files, removed + other.removed)
				Unknown -> Unknown
			}

		override val isEmpty: Boolean
			get() = files.isEmpty() && removed.isEmpty()

		companion object {
			val EMPTY = Known(emptySet())
		}
	}

	data object Unknown : ChangedFiles {
		override fun plus(other: ChangedFiles): ChangedFiles = Unknown

		override val isEmpty: Boolean
			get() = false
	}
}
