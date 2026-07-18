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

	data class Known(
		val files: Set<File>,
	) : ChangedFiles {
		override fun plus(other: ChangedFiles): ChangedFiles =
			when (other) {
				is Known -> Known(files + other.files)
				Unknown -> Unknown
			}

		override val isEmpty: Boolean
			get() = files.isEmpty()

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
