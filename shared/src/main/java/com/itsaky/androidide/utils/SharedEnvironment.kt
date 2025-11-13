package com.itsaky.androidide.utils

/**
 * Shared properties.
 *
 * @author Akash Yadav
 */
object SharedEnvironment {

	/**
	 * The name of the per-project cache directory.
	 */
	const val PROJECT_CACHE_DIR_NAME = ".androidide"

	/**
	 * The name of the gradle sync cache directory.
	 */
	const val PROJECT_SYNC_CACHE_DIR_NAME = "gradle-sync"

	/**
	 * The relative path to the gradle sync cache directory of a project.
	 */
	const val PROJECT_SYNC_CACHE_DIR = "$PROJECT_CACHE_DIR_NAME/$PROJECT_SYNC_CACHE_DIR_NAME"

	/**
	 * The name of the gradle sync cache lock file.
	 */
	const val PROJECT_SYNC_CACHE_LOCK_FILE_NAME = "sync.lock"

	/**
	 * The relative path to the gradle sync cache lock file of a project.
	 */
	const val PROJECT_SYNC_CACHE_LOCK_FILE = "$PROJECT_SYNC_CACHE_DIR/$PROJECT_SYNC_CACHE_LOCK_FILE_NAME"

	/**
	 * The name of the gradle sync metadata file.
	 */
	const val PROJECT_SYNC_CACHE_META_FILE_NAME = "sync.pb"

	/**
	 * The relative path to the gradle sync cache metadata file of a project.
	 */
	const val PROJECT_SYNC_CACHE_META_FILE = "$PROJECT_SYNC_CACHE_DIR/$PROJECT_SYNC_CACHE_META_FILE_NAME"

	/**
	 * The name of the gradle project cache model file.
	 */
	const val PROJECT_SYNC_CACHE_MODEL_FILE_NAME = "project.pb"

	/**
	 * The relative path to the gradle project cache model file of a project.
	 */
	const val PROJECT_SYNC_CACHE_MODEL_FILE = "$PROJECT_SYNC_CACHE_DIR/$PROJECT_SYNC_CACHE_MODEL_FILE_NAME"
}