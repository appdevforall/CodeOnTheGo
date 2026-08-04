package org.appdevforall.cotg.quickbuild.domain

import java.io.File

/**
 * The set of files changed since the last successfully absorbed quick build.
 *
 * [Known] and [Unknown] are separate types because an empty [Known] set means "nothing
 * changed" (a no-op save must not recompile), while [Unknown] means "we cannot tell" (crash
 * recovery, missed watcher events) and makes the next build treat every source as dirty.
 */
sealed interface ChangedFiles {
	/**
	 * Union of two changed-sets, reconciled per path with the newer batch winning. [other] is
	 * always the newer one, so modify-then-delete collapses to a removal and
	 * delete-then-recreate to a modification; a plain set union would leave the path in both
	 * sets and the executor would feed it to the daemon as changed AND removed.
	 */
	operator fun plus(other: ChangedFiles): ChangedFiles

	/** True only for an empty [Known] set - [Unknown] is never empty. */
	val isEmpty: Boolean

	/**
	 * An enumerated changed-set.
	 *
	 * @property files paths modified or created since the last absorbed build.
	 * @property removed paths deleted since then. Kept separate from [files] because a removal
	 *   is classified by path shape alone (nothing is left on disk to inspect) and routes
	 *   differently: a removed `.kt`/`.java` feeds the incremental compiler's removed-sources
	 *   slot, dropping its outputs and recompiling dependents.
	 */
	data class Known(
		val files: Set<File>,
		val removed: Set<File> = emptySet(),
	) : ChangedFiles {
		override fun plus(other: ChangedFiles): ChangedFiles =
			when (other) {
				// other is the NEWER batch: its events override this batch's per path.
				is Known -> {
					Known(
						(files - other.removed) + other.files,
						(removed - other.files) + other.removed,
					)
				}

				Unknown -> {
					Unknown
				}
			}

		override val isEmpty: Boolean
			get() = files.isEmpty() && removed.isEmpty()

		companion object {
			val EMPTY = Known(emptySet())
		}
	}

	/** The changed-set could not be enumerated; every source counts as dirty. */
	data object Unknown : ChangedFiles {
		override fun plus(other: ChangedFiles): ChangedFiles = Unknown

		override val isEmpty: Boolean
			get() = false
	}
}
