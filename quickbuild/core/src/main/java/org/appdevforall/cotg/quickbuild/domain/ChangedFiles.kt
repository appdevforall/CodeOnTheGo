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
	 *
	 * @param other the NEWER changed-set, whose verdict per path wins over this one's.
	 * @return the reconciled union, [Unknown] whenever either side is [Unknown] - a collapse that
	 *   discards the enumerated side's paths, so a caller that routed on them must preserve the
	 *   verdict itself (see `LiveReloadOrchestrator.stickyInvalidation`).
	 */
	operator fun plus(other: ChangedFiles): ChangedFiles

	/** True only for an empty [Known] set - [Unknown] is never empty. */
	val isEmpty: Boolean

	/**
	 * An enumerated changed-set.
	 *
	 * @property files paths modified or created since the last absorbed build.
	 * @property removed paths deleted since then, kept separate because a removal is classified by
	 *   path shape alone (nothing is left on disk to inspect) and routes to the incremental
	 *   compiler's removed-sources slot, which drops its outputs and recompiles dependents.
	 */
	data class Known(
		val files: Set<File>,
		val removed: Set<File> = emptySet(),
	) : ChangedFiles {
		override fun plus(other: ChangedFiles): ChangedFiles =
			when (other) {
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
			/** The shared "nothing changed" value; a no-op save must not recompile. */
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
