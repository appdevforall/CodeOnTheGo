package com.itsaky.androidide.repositories

import java.io.File

/**
 * Repository interface for template-collection (.cgt) operations.
 */
interface TemplateCollectionRepository {
	data class CollectionInfo(
		val templateNames: List<String>,
	)

	/**
	 * Parse and validate a candidate .cgt archive without installing it.
	 */
	suspend fun inspectCollection(candidateFile: File): Result<CollectionInfo>

	/**
	 * Returns the filename (without extension) of an already-installed template collection
	 * matching [baseName] case-insensitively, or `null` if there is no collision.
	 */
	suspend fun findExistingCollision(baseName: String): String?

	/**
	 * Install [candidateFile] into the templates directory under [targetBaseName], reloading
	 * the template provider afterwards.
	 */
	suspend fun installCollection(
		candidateFile: File,
		targetBaseName: String,
		overwrite: Boolean,
	): Result<Unit>

	/**
	 * Check if the templates system is available (i.e. IDE setup has completed).
	 */
	fun isTemplatesFeatureAvailable(): Boolean
}
