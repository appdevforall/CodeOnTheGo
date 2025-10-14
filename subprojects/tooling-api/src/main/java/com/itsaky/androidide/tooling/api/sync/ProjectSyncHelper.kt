package com.itsaky.androidide.tooling.api.sync

import com.itsaky.androidide.project.FileInfo
import com.itsaky.androidide.project.GradleModels
import com.itsaky.androidide.project.SyncMeta
import com.itsaky.androidide.project.SyncMetaModels
import com.itsaky.androidide.utils.SharedEnvironment
import com.itsaky.androidide.utils.sha256
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.collections.iterator
import kotlin.io.path.outputStream
import kotlin.io.path.pathString

/**
 * Utility functions to work with project sync metadata.
 *
 * @author Akash Yadav
 */
object ProjectSyncHelper {

	private val logger = LoggerFactory.getLogger(ProjectSyncHelper::class.java)
	private val hashDispatcher =
		Dispatchers.Default.limitedParallelism(Runtime.getRuntime().availableProcessors())

	/**
	 * Path matchers for files that we need to watch.
	 */
	private val watchedFileGlobs = listOf(
		"glob:**/*.gradle",
		"glob:**/*.gradle.kts",
	).map { glob -> FileSystems.getDefault().getPathMatcher(glob) }

	private val watchedFileMatcher = PathMatcher { path ->
		watchedFileGlobs.any { matcher ->
			matcher.matches(path)
		}
	}

	/**
	 * File names that we need to watch.
	 */
	private val watchedFileNames = listOf(
		"gradle.properties",
		"local.properties",
		"gradle-wrapper.properties",
	)

	/**
	 * Directories that should not be traversed.
	 */
	private val excludedDirectoryNames = listOf(
		".git",
		".gradle",
		".kotlin",
		".cxx"
	)

	/**
	 * Get the project model cache file for the given project directory.
	 *
	 * @param projectDir The project directory.
	 * @return The project model cache file.
	 */
	fun cacheFileForProject(projectDir: File) = projectDir
		.resolve(SharedEnvironment.PROJECT_SYNC_CACHE_MODEL_FILE)

	/**
	 * Get the sync metadata file for the given project directory.
	 *
	 * @param projectDir The project directory.
	 * @return The sync metadata file.
	 */
	fun syncMetaFile(projectDir: File) =
		projectDir.resolve(SharedEnvironment.PROJECT_SYNC_CACHE_META_FILE)

	/**
	 * Try to acquire the sync lock.
	 */
	fun tryAcquireSyncLock(
		projectDir: File,
		timeoutMs: Long
	) = tryAcquireSyncLock(projectDir.toPath(), timeoutMs)

	/**
	 * Try to acquire the sync lock.
	 */
	fun tryAcquireSyncLock(projectDir: Path, timeoutMs: Long): FileChannel? {
		val lockFile = projectDir.resolve(SharedEnvironment.PROJECT_SYNC_CACHE_LOCK_FILE)
		Files.createDirectories(lockFile.parent)
		val channel =
			FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
		val start = System.currentTimeMillis()
		while (System.currentTimeMillis() - start < timeoutMs) {
			val lock = channel.tryLock()
			if (lock != null) return channel
			Thread.sleep(50)
		}

		// Locking failed
		channel.close()
		return null
	}

	/**
	 * Release the sync lock.
	 */
	fun releaseSyncLock(channel: FileChannel?) {
		channel?.close()
	}

	/**
	 * Try to use the sync lock.
	 */
	inline fun tryUseSyncLock(projectDir: File, timeoutMs: Long, block: () -> Unit): Boolean {
		var channel: FileChannel? = null
		try {
			channel = tryAcquireSyncLock(projectDir, timeoutMs)
			if (channel == null) return false

			block()
			return true
		} finally {
			releaseSyncLock(channel)
		}
	}

	/**
	 * Read the Gradle build model from the given project directory.
	 *
	 * @param cacheFile The project cache file.
	 * @return The Gradle build model result.
	 */
	suspend fun readGradleBuild(cacheFile: File): Result<GradleModels.GradleBuild> {
		return withContext(Dispatchers.IO) {
			runCatching {
				cacheFile.inputStream().buffered().use { input ->
					GradleModels.GradleBuild.parseFrom(input)
				}
			}
		}
	}

	/**
	 * Write the Gradle build model. The model files will be written to the root project's
	 * directly of the provided [com.itsaky.androidide.project.GradleModels.GradleBuild].
	 *
	 * @param gradleBuild The Gradle build model.
	 * @param targetFile The target file.
	 */
	suspend fun writeGradleBuild(
		gradleBuild: GradleModels.GradleBuild,
		targetFile: File,
	): Unit = withContext(Dispatchers.IO) {
		writeGradleBuildSync(gradleBuild, targetFile)
	}

	/**
	 * Write the Gradle build model synchronously. Use with caution.
	 *
	 * @param gradleBuild The Gradle build model.
	 * @param targetFile The target file.
	 */
	fun writeGradleBuildSync(
		gradleBuild: GradleModels.GradleBuild,
		targetFile: File
	) {
		// use a temporary file on the same path to allow atomic moves
		// /data/data and /sdcard are different devices (partitions)
		// atomic moves are not possible for cross-device moves
		val tempCacheFile = Paths.get(targetFile.path + ".tmp")
		logger.debug("Write project model to file: {}", tempCacheFile)
		runCatching {
			tempCacheFile
				.outputStream(StandardOpenOption.CREATE, StandardOpenOption.WRITE)
				.buffered()
				.use { tempOut ->
					gradleBuild.writeTo(tempOut)
					tempOut.flush()
				}
			logger.debug("Wrote file {}", tempCacheFile)
		}.map {
			logger.debug("Moving {} to {}", tempCacheFile, targetFile)
			// update atomically
			Files.move(
				tempCacheFile,
				targetFile.toPath(),
				StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
			)
		}.getOrThrow()
	}

	/**
	 * Check if a sync is needed for the given project directory.
	 *
	 * @param projectDir The project directory.
	 * @return `true` if a sync is needed, `false` otherwise.
	 */
	suspend fun checkSyncNeeded(projectDir: File): Boolean {
		val draft = createSyncMeta(projectDir, includeChecksum = false)
		val syncMetaFile = syncMetaFile(projectDir)
		val stored = try {
			loadSyncMetaFromFile(syncMetaFile)
		} catch (_: FileNotFoundException) {
			// sync meta is not available, require sync
			logger.debug("NEED_SYNC: sync meta file not found")
			return true
		} catch (err: Throwable) {
			logger.warn("NEED_SYNC: failed to read sync metadata file", err)
			return true
		}

		val draftFilePaths = draft.watchedFilesList.map { it.relativePath }.toSet()
		val storedFilePaths = stored.watchedFilesList.map { it.relativePath }.toSet()
		if (draftFilePaths != storedFilePaths) {
			// watched files changed, sync required
			logger.debug("NEED_SYNC: watched files list changed")
			return true
		}

		val storedMap = stored.watchedFilesList.associateBy { it.relativePath }
		val needsHash = mutableListOf<SyncMetaModels.FileInfoOrBuilder>()

		for (draft in draft.watchedFilesList) {
			// RHS of elvis should not happen because of the check above,
			// but just to be safe
			val stored = storedMap[draft.relativePath] ?: continue

			if (draft.canonicalPath != stored.canonicalPath) {
				// file was probably a link, but the destination is now changed
				// require sync
				logger.debug(
					"NEED_SYNC: destination of '{}' changed from '{}' to '{}'",
					draft.relativePath,
					stored.canonicalPath,
					draft.canonicalPath
				)
				return true
			}

			if (draft.size == stored.size && draft.mtime == stored.mtime) {
				// file unchanged
				continue
			}

			// file might have been modified, verify by comparing checksum
			needsHash.add(draft)
		}

		val hashResults = computeHashes(needsHash)
		for ((draft, computedSha) in hashResults) {
			val stored = storedMap[draft.relativePath] ?: continue
			if (stored.sha256 == null) {
				// stored metadata didn't have sha256, so we can't compare
				// require sync
				logger.debug("NEED_SYNC: watched file '{}' doesn't have stored checksum", stored.canonicalPath)
				return true
			}

			if (!computedSha.equals(other = stored.sha256, ignoreCase = true)) {
				// content changed
				// require sync
				logger.debug("NEED_SYNC: checksum mismatch '{}': expected={}, actual={}", stored.canonicalPath, stored.sha256, computedSha)
				return true
			}
		}

		return false
	}

	private suspend fun computeHashes(files: List<SyncMetaModels.FileInfoOrBuilder>): Map<SyncMetaModels.FileInfoOrBuilder, String> =
		coroutineScope {
			val deferred =
				files.associateWith { file ->
					async { File(file.canonicalPath).sha256() }
				}

			deferred.mapValues { (_, d) -> d.await() }
		}

	/**
	 * Create sync metadata for the given project directory.
	 *
	 * @param projectDir The project directory.
	 * @param includeChecksum Whether to include checksums in the metadata.
	 * @param projectModelInfo The project model info, if any.
	 * @return The sync metadata model.
	 */
	suspend fun createSyncMeta(
		projectDir: File,
		includeChecksum: Boolean = false,
		projectModelInfo: SyncMetaModels.ProjectModelInfo? = null,
	): SyncMetaModels.SyncMeta =
		createSyncMeta(
			projectDir = projectDir.toPath(),
			includeChecksum = includeChecksum,
			projectModelInfo = projectModelInfo
		)

	/**
	 * Create sync metadata for the given project directory.
	 *
	 * @param projectDir The project directory.
	 * @param includeChecksum Whether to include checksums in the metadata.
	 * @param projectModelInfo The project model info, if any.
	 * @return The sync metadata model.
	 */
	suspend fun createSyncMeta(
		projectDir: Path,
		includeChecksum: Boolean = false,
		projectModelInfo: SyncMetaModels.ProjectModelInfo? = null,
	): SyncMetaModels.SyncMeta {
		val projectDir = projectDir.toRealPath()
		return SyncMeta(
			metaVersion = "1",
			rootProjectPath = projectDir.pathString,
			syncTime = System.currentTimeMillis().toString(),
			watchedFilesList = createWatchedFilesList(projectDir, includeChecksum),
			projectModelInfo = projectModelInfo,
		)
	}

	/**
	 * Create a list of watched files for the given project directory.
	 *
	 * @param projectDir The project directory.
	 * @param includeChecksum Whether to include checksums in the metadata.
	 * @return The list of watched files and their metadata.
	 */
	suspend fun createWatchedFilesList(
		projectDir: Path,
		includeChecksum: Boolean = false
	): List<SyncMetaModels.FileInfo> =
		collectWatchedFiles(projectDir).map { (file, attrs) ->
			FileInfo(
				relativePath = projectDir.relativize(file).pathString,
				canonicalPath = file.toRealPath().pathString,
				size = attrs.size(),
				mtime = attrs.lastModifiedTime().toMillis(),
				sha256 = if (includeChecksum) withContext(Dispatchers.Default) { file.sha256() } else null
			)
		}

	/**
	 * Collect all the watched files in the given project directory.
	 *
	 * @param projectDir The project directory.
	 * @return The list of watched files and their basic file attributes.
	 */
	fun collectWatchedFiles(projectDir: Path): List<Pair<Path, BasicFileAttributes>> {
		val results = mutableListOf<Pair<Path, BasicFileAttributes>>()
		val visited = HashSet<Any?>()

		Files.walkFileTree(projectDir, object : SimpleFileVisitor<Path>() {
			override fun preVisitDirectory(
				path: Path,
				attributes: BasicFileAttributes
			): FileVisitResult {
				val dirName = path.fileName?.toString() ?: ""
				if (dirName in excludedDirectoryNames) return FileVisitResult.SKIP_SUBTREE

				val key = runCatching { attributes.fileKey() }.getOrDefault(null)
				val id = key ?: runCatching { path.toRealPath().pathString }.getOrElse {
					path.toAbsolutePath().normalize().pathString
				}
				if (!visited.add(id)) return FileVisitResult.SKIP_SUBTREE
				return FileVisitResult.CONTINUE
			}

			override fun visitFile(
				path: Path,
				attributes: BasicFileAttributes
			): FileVisitResult {
				val fileName = path.fileName?.toString() ?: ""
				if (fileName in watchedFileNames || watchedFileMatcher.matches(path)) {
					results.add(path to attributes)
				}

				return FileVisitResult.CONTINUE
			}

			override fun visitFileFailed(p0: Path, p1: IOException): FileVisitResult =
				// ignore files we can't read
				FileVisitResult.CONTINUE
		})

		return results
	}

	/**
	 * Read the sync metadata from the given file.
	 *
	 * @param file The file to read from.
	 * @return The sync metadata model.
	 */
	suspend fun loadSyncMetaFromFile(file: File): SyncMetaModels.SyncMeta =
		withContext(Dispatchers.IO) {
			file.inputStream().buffered().use { fileIn ->
				SyncMetaModels.SyncMeta.parseFrom(fileIn)
			}
		}
}