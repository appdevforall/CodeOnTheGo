package org.appdevforall.codeonthego.indexing.jvm

import org.appdevforall.codeonthego.indexing.api.Indexable
import java.time.Instant

/**
 * Metadata for a single Kotlin source file.
 *
 * Stored in [KtFileMetadataIndex], one entry per `.kt` file discovered
 * in the project source roots. The entry is keyed by [filePath] so that
 * subsequent updates for the same file replace the previous record.
 *
 * @param filePath Absolute path to the `.kt` file. Acts as [key] and [sourceId].
 * @param packageFqName Fully-qualified package name declared in the file
 *                      (empty string for the root / default package).
 * @param lastModified  Wall-clock time the file was last written to disk.
 * @param modificationStamp Monotonically increasing stamp from the VFS or
 *                          filesystem; used to detect stale cache entries
 *                          without comparing file content.
 * @param isIndexed Whether [symbolKeys] has been populated for this file.
 *                  Files are inserted with `isIndexed = false` as a placeholder
 *                  when first discovered; the indexer flips this to `true`
 *                  after scanning and writing all symbols.
 * @param symbolKeys The [Indexable.key] values of every [JvmSymbol]
 *                        declared in this file that was written to the symbol
 *                        index. Empty until [isIndexed] becomes `true`.
 */
data class KtFileMetadata(
	val filePath: String,
	val packageFqName: String,
	val lastModified: Instant,
	val modificationStamp: Long,
	val isIndexed: Boolean = false,
	val symbolKeys: List<String> = emptyList(),
) : Indexable {

	companion object {
		fun shouldBeSkipped(existing: KtFileMetadata? = null, new: KtFileMetadata): Boolean {
			return existing != null && !existing.lastModified.isBefore(new.lastModified) &&
					existing.modificationStamp >= new.modificationStamp &&
					(new.modificationStamp != 0L || existing.modificationStamp == 0L)
		}
	}

    override val key: String get() = filePath
    override val sourceId: String get() = filePath
}
