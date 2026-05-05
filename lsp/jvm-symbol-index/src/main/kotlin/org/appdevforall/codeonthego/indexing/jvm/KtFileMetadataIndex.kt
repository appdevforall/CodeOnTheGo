package org.appdevforall.codeonthego.indexing.jvm

import android.content.Context
import org.appdevforall.codeonthego.indexing.SQLiteIndex
import org.appdevforall.codeonthego.indexing.api.indexQuery
import org.appdevforall.codeonthego.indexing.jvm.KtFileMetadataDescriptor.KEY_IS_INDEXED
import org.appdevforall.codeonthego.indexing.jvm.KtFileMetadataDescriptor.KEY_PACKAGE
import java.io.Closeable

/**
 * An index of [KtFileMetadata] entries, one per Kotlin source file.
 */
class KtFileMetadataIndex private constructor(
	private val backing: SQLiteIndex<KtFileMetadata>,
) : Closeable {

	companion object {

		/**
		 * Creates a [KtFileMetadataIndex] backed by an in-memory SQLite database.
		 *
		 * The [context] is required by the AndroidX SQLite helpers even for in-memory
		 * databases; it is not used for any file I/O.
		 */
		fun create(
			context: Context,
			dbName: String? = null
		): KtFileMetadataIndex =
			KtFileMetadataIndex(
				SQLiteIndex(
					descriptor = KtFileMetadataDescriptor,
					context = context,
					dbName = dbName,
					name = "kt-file-metadata",
				)
			)
	}

	/**
	 * Insert or replace the metadata record for a single file.
	 *
	 * Because [KtFileMetadata.key] == [KtFileMetadata.filePath], the
	 * underlying `CONFLICT_REPLACE` strategy ensures this is a true upsert.
	 */
	suspend fun upsert(metadata: KtFileMetadata) = backing.insert(metadata)

	/**
	 * Remove the metadata record for [filePath].
	 *
	 * No-op if the file is not in the index.
	 */
	suspend fun remove(filePath: String) = backing.removeBySource(filePath)

	/**
	 * Return the [KtFileMetadata] for [filePath], or `null` if the file is
	 * not present in the index.
	 */
	suspend fun get(filePath: String): KtFileMetadata? = backing.get(filePath)

	/**
	 * Return `true` if [filePath] has a record in the index.
	 */
	suspend fun contains(filePath: String): Boolean = backing.containsSource(filePath)

	/**
	 * Returns a [Sequence] of files whose declared package exactly matches
	 * [packageFqName].
	 */
	fun getFilesForPackage(packageFqName: String): Sequence<KtFileMetadata> =
		backing.query(
			indexQuery {
				eq(KEY_PACKAGE, packageFqName)
				limit = 0
			}
		)

	/**
	 * Returns a [Sequence] of absolute file paths whose declared package exactly
	 * matches [packageFqName].
	 */
	fun getFilePathsForPackage(packageFqName: String): Sequence<String> =
		getFilesForPackage(packageFqName).map { it.filePath }

	/**
	 * Returns `true` if at least one file with package [packageFqName] is
	 * present in the index.
	 *
	 * Pass an empty string for the root (default) package.
	 */
	fun packageExists(packageFqName: String): Boolean =
		backing.query(indexQuery {
			eq(KEY_PACKAGE, packageFqName)
			limit = 1
		}).firstOrNull() != null

	/**
	 * Returns the simple names of the direct child packages of [packageFqName].
	 *
	 * For example, if the index contains `com.example.foo`, `com.example.bar`,
	 * and `com.example.foo.sub`, then `getSubpackageNames("com.example")` returns
	 * `{"foo", "bar"}`. Pass an empty string to enumerate top-level packages.
	 *
	 * Implemented by scanning all distinct package names and extracting the
	 * first component after [packageFqName]. This is fast for typical Android
	 * projects (dozens of packages) and avoids a secondary SQL schema.
	 */
	fun getSubpackageNames(packageFqName: String): Set<String> {
		val prefix = if (packageFqName.isEmpty()) "" else "$packageFqName."
		val result = mutableSetOf<String>()
		for (pkg in backing.distinctValues(KEY_PACKAGE)) {
			if (pkg == packageFqName) continue
			if (prefix.isNotEmpty() && !pkg.startsWith(prefix)) continue
			val remainder = if (prefix.isEmpty()) pkg else pkg.removePrefix(prefix)
			val firstComponent = remainder.substringBefore('.')
			if (firstComponent.isNotEmpty()) result.add(firstComponent)
		}
		return result
	}

	/**
	 * Returns a [Sequence] of all distinct package names present in the index.
	 *
	 * Useful for building a complete package tree or bulk validity checks.
	 */
	fun allPackages(): Sequence<String> = backing.distinctValues(KEY_PACKAGE)

	/**
	 * Returns a [Sequence] of file paths that have been discovered but whose
	 * symbols have not yet been extracted ([KtFileMetadata.isIndexed] is `false`).
	 */
	fun getUnindexedFiles(): Sequence<String> =
		backing.query(indexQuery {
			eq(KEY_IS_INDEXED, false.toString())
			limit = 0
		}).map { it.filePath }

	/** Remove all records from the index. */
	suspend fun clear() = backing.clear()

	override fun close() = backing.close()
}
