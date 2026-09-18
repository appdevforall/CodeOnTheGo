package com.itsaky.androidide.tooling.api.sync

import com.itsaky.androidide.project.FileInfo
import com.itsaky.androidide.project.GradleModels
import com.itsaky.androidide.project.SyncMeta
import com.itsaky.androidide.project.SyncMetaModels
import com.itsaky.androidide.utils.SharedEnvironment
import com.itsaky.androidide.utils.sha256
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.collections.iterator
import kotlin.io.path.outputStream
import kotlin.io.path.pathString

/**
 * Utility functions to work with project sync metadata.
 *
 * @author Akash Yadav
 */
object ProjectSyncHelper {
	/**
	 * Version of the on-disk project sync files.
	 *
	 * Bump this whenever a persisted proto schema changes incompatibly. A stored version that
	 * differs from this one discards the sync files and forces a full sync. The models are
	 * produced and consumed only by the IDE and hold no user data, so discarding them is safe.
	 */
	const val SYNC_META_VERSION = "2"

	private val logger = LoggerFactory.getLogger(ProjectSyncHelper::class.java)

	/**
	 * Per-lock-file mutex, keyed by the lock file's absolute path.
	 *
	 * Closing any channel on a file releases every lock the JVM holds on that file, so two threads
	 * must never hold overlapping channels on one sync lock file -- the second one's close would
	 * silently drop the first one's lock. `FileChannel.tryLock` cannot express that (it throws on a
	 * same-JVM overlap), so in-process contention is settled here, before a channel is opened.
	 */
	private val inProcessLocks = ConcurrentHashMap<String, Semaphore>()

	/** The in-process mutex held on behalf of each acquired channel, released when it is closed. */
	private val heldLocks = ConcurrentHashMap<FileChannel, Semaphore>()
	private val hashDispatcher =
		Dispatchers.Default.limitedParallelism(Runtime.getRuntime().availableProcessors())

	/**
	 * How long to wait for the sync lock before giving up on discarding the sync files.
	 */
	private const val DISCARD_LOCK_TIMEOUT_MS = 1_000L

	/**
	 * Path matchers for files that we need to watch.
	 */
	private val watchedFileGlobs =
		listOf(
			"glob:**/*.gradle",
			"glob:**/*.gradle.kts",
		).map { glob -> FileSystems.getDefault().getPathMatcher(glob) }

	private val watchedFileMatcher =
		PathMatcher { path ->
			watchedFileGlobs.any { matcher ->
				matcher.matches(path)
			}
		}

	/**
	 * File names that we need to watch.
	 */
	private val watchedFileNames =
		listOf(
			"gradle.properties",
			"local.properties",
			"gradle-wrapper.properties",
		)

	/**
	 * Directories that should not be traversed.
	 */
	private val excludedDirectoryNames =
		listOf(
			".git",
			".gradle",
			".kotlin",
			".cxx",
		)

	/**
	 * Get the project model cache file for the given project directory.
	 *
	 * @param projectDir The project directory.
	 * @return The project model cache file.
	 */
	fun cacheFileForProject(projectDir: File) =
		projectDir
			.resolve(SharedEnvironment.PROJECT_SYNC_CACHE_MODEL_FILE)

	/**
	 * Get the sync metadata file for the given project directory.
	 *
	 * @param projectDir The project directory.
	 * @return The sync metadata file.
	 */
	fun syncMetaFileForProject(projectDir: File) = projectDir.resolve(SharedEnvironment.PROJECT_SYNC_CACHE_META_FILE)

	/**
	 * Try to acquire the sync lock.
	 */
	fun tryAcquireSyncLock(
		projectDir: File,
		timeoutMs: Long,
	) = tryAcquireSyncLock(projectDir.toPath(), timeoutMs)

	/**
	 * Try to acquire the sync lock.
	 */
	fun tryAcquireSyncLock(
		projectDir: Path,
		timeoutMs: Long,
	): FileChannel? {
		val lockFile = projectDir.resolve(SharedEnvironment.PROJECT_SYNC_CACHE_LOCK_FILE)
		Files.createDirectories(lockFile.parent)

		val inProcessLock = inProcessLocks.computeIfAbsent(lockFile.toAbsolutePath().pathString) { Semaphore(1) }
		if (!inProcessLock.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
			return null
		}

		var pending: FileChannel? = null
		try {
			val channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
			pending = channel

			val start = System.currentTimeMillis()
			while (System.currentTimeMillis() - start < timeoutMs) {
				if (channel.tryLock() != null) {
					pending = null
					heldLocks[channel] = inProcessLock
					return channel
				}
				Thread.sleep(50)
			}

			return null
		} catch (err: InterruptedException) {
			Thread.currentThread().interrupt()
			return null
		} catch (err: OverlappingFileLockException) {
			// The semaphore above should have made this unreachable; report it rather than spin.
			logger.warn("Sync lock is already held by this process", err)
			return null
		} catch (err: IOException) {
			logger.warn("Failed to acquire the sync lock", err)
			return null
		} finally {
			pending?.let { channel ->
				channel.close()
				inProcessLock.release()
			}
		}
	}

	/**
	 * Release the sync lock.
	 */
	fun releaseSyncLock(channel: FileChannel?) {
		channel ?: return
		try {
			channel.close()
		} finally {
			heldLocks.remove(channel)?.release()
		}
	}

	/**
	 * Try to use the sync lock.
	 */
	inline fun tryUseSyncLock(
		projectDir: File,
		timeoutMs: Long,
		block: () -> Unit,
	): Boolean {
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
	suspend fun readGradleBuild(cacheFile: File): Result<GradleModels.GradleBuild> =
		withContext(Dispatchers.IO) {
			runCatching {
				cacheFile.inputStream().buffered().use { input ->
					GradleModels.GradleBuild.parseFrom(input)
				}
			}
		}

	/**
	 * Write the Gradle build model. The model files will be written to the root project's
	 * directory of the provided [com.itsaky.androidide.project.GradleModels.GradleBuild].
	 *
	 * @param gradleBuild The Gradle build model.
	 * @param targetFile The target file.
	 */
	suspend fun writeGradleBuild(
		gradleBuild: GradleModels.GradleBuild,
		targetFile: File,
	): Unit =
		withContext(Dispatchers.IO) {
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
		targetFile: File,
	) {
		// use a temporary file on the same path to allow atomic moves
		// /data/data and /sdcard are different devices (partitions)
		// atomic moves are not possible for cross-device moves
		val tempCacheFile = Paths.get(targetFile.path + ".tmp")
		runCatching {
			tempCacheFile
				.outputStream(StandardOpenOption.CREATE, StandardOpenOption.WRITE)
				.buffered()
				.use { tempOut ->
					gradleBuild.writeTo(tempOut)
					tempOut.flush()
				}
		}.map {
			// update atomically
			Files.move(
				tempCacheFile,
				targetFile.toPath(),
				StandardCopyOption.REPLACE_EXISTING,
				StandardCopyOption.ATOMIC_MOVE,
			)
		}.getOrThrow()
	}

	/**
	 * Check whether the project sync files for the given project directory
	 * exist and are readable.
	 *
	 * @param projectDir The project directory.
	 * @return `true` if the files exist and are readable, `false` otherwise.
	 */
	fun areSyncFilesReadable(projectDir: File) =
		areSyncFilesReadable(
			syncMetaFile = syncMetaFileForProject(projectDir),
			projectCacheFile = cacheFileForProject(projectDir),
		)

	/**
	 * Check whether the project sync files for the given project directory
	 * exist and are readable.
	 *
	 * @param syncMetaFile The sync metadata file.
	 * @param projectCacheFile The project cache file.
	 * @return `true` if the files exist and are readable, `false` otherwise.
	 */
	fun areSyncFilesReadable(
		syncMetaFile: File,
		projectCacheFile: File,
	): Boolean =
		syncMetaFile.exists() &&
			syncMetaFile.canRead() &&
			projectCacheFile.exists() &&
			projectCacheFile.canRead()

	/**
	 * Check whether the sync metadata at [syncMetaFile] was written by the current
	 * [SYNC_META_VERSION].
	 *
	 * Metadata written by an older schema still parses -- a removed field reads back as its default
	 * rather than failing -- so the project cache beside it can only be trusted once the stored
	 * version has been checked. Callers that reach the cache without going through
	 * [checkSyncNeeded] must gate on this.
	 *
	 * Blocking: reads and parses the file on the calling thread. Never call it from the main
	 * thread.
	 *
	 * @param syncMetaFile The sync metadata file.
	 * @return `true` if the stored version is current, `false` if it differs or cannot be read.
	 */
	fun isSyncMetaVersionCurrent(syncMetaFile: File): Boolean =
		try {
			readSyncMeta(syncMetaFile).metaVersion == SYNC_META_VERSION
		} catch (err: CancellationException) {
			throw err
		} catch (err: Throwable) {
			/*
			 * A corrupt file normally surfaces as an InvalidProtocolBufferException, but the catch
			 * is deliberately total: this runs inside checkSyncNeeded's own failure handling, whose
			 * callers rethrow anything but FileNotFoundException, so an escape here crashes the
			 * project open instead of resyncing it. A parse that OOMs on a bogus length prefix is
			 * exactly the case a resync recovers from.
			 */
			logger.warn("Failed to read sync metadata file: {}", syncMetaFile, err)
			false
		}

	/**
	 * Check if a sync is needed for the given project directory.
	 *
	 * Not a pure query: once the metadata has been read, files it shows to be unusable -- corrupt,
	 * or written by a schema version other than [SYNC_META_VERSION] -- are deleted, because they
	 * would otherwise parse into a silently empty model. The same discard runs when the metadata is
	 * missing or unparseable, which leaves a cache nothing can vouch for. Only the case where both
	 * files are already missing or unreadable skips it, since there is nothing to delete. Deletion
	 * failures are logged, never thrown.
	 *
	 * @param projectDir The project directory.
	 * @return `true` if a sync is needed, `false` otherwise.
	 */
	suspend fun checkSyncNeeded(projectDir: File): Boolean {
		// @devs: add a log statement whenever this function returns `true`
		// describing why it returns `true`

		val syncMetaFile = syncMetaFileForProject(projectDir)
		val projectCacheFile = cacheFileForProject(projectDir)
		if (!areSyncFilesReadable(syncMetaFile, projectCacheFile)) {
			// one of the required files are missing, require sync
			logger.debug(
				"NEED_SYNC: sync files missing or are unreadable:" +
					" sync-meta={}," +
					" project-cache={}",
				syncMetaFile,
				projectCacheFile,
			)
			return true
		}

		val stored =
			try {
				loadSyncMetaFromFile(syncMetaFile)
			} catch (_: FileNotFoundException) {
				// sync meta is not available, require sync
				logger.debug("NEED_SYNC: sync meta file not found")
				discardSyncFiles(projectDir)
				return true
			} catch (err: CancellationException) {
				throw err
			} catch (err: Throwable) {
				logger.warn("NEED_SYNC: failed to read sync metadata file", err)
				discardSyncFiles(projectDir)
				return true
			}

		if (stored.metaVersion != SYNC_META_VERSION) {
			/*
			 * The cache file is written before the metadata under the same lock, so a stored
			 * version matching ours implies the cache beside it uses the current schema. On a
			 * mismatch the cache is unusable but still parses -- a removed field reads back as
			 * its default rather than failing -- so it has to be discarded, not just resynced.
			 */
			logger.debug(
				"NEED_SYNC: sync meta version mismatch: expected={}, actual={}",
				SYNC_META_VERSION,
				stored.metaVersion,
			)
			discardSyncFiles(projectDir)
			return true
		}

		// Built only once the cheap version gate has passed: it walks every watched file.
		val draft = createSyncMeta(projectDir, includeChecksum = false)

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
					draft.canonicalPath,
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
				logger.debug(
					"NEED_SYNC: watched file '{}' doesn't have stored checksum",
					stored.canonicalPath,
				)
				return true
			}

			if (!computedSha.equals(other = stored.sha256, ignoreCase = true)) {
				// content changed
				// require sync
				logger.debug(
					"NEED_SYNC: checksum mismatch '{}': expected={}, actual={}",
					stored.canonicalPath,
					stored.sha256,
					computedSha,
				)
				return true
			}
		}

		return false
	}

	/**
	 * Delete the sync metadata and project model cache files for the given project directory.
	 *
	 * Taken under the same lock the sync writes them under. Failing to acquire it means a sync is
	 * already in flight and about to replace both files, so the deletion is skipped. Acquiring it
	 * does not mean the files are still stale, so staleness is rechecked under the lock.
	 */
	private suspend fun discardSyncFiles(projectDir: File) {
		withContext(Dispatchers.IO) {
			try {
				val locked =
					tryUseSyncLock(projectDir, DISCARD_LOCK_TIMEOUT_MS) {
						val syncMetaFile = syncMetaFileForProject(projectDir)

						/*
						 * Staleness was decided outside the lock, and the metadata is written
						 * non-atomically, so a reader can catch it truncated mid-write and a sync
						 * can complete while we wait here. Re-read before deleting, or a discard
						 * takes out the fresh files that sync just wrote.
						 */
						if (isSyncMetaVersionCurrent(syncMetaFile)) {
							logger.debug("Sync files were rewritten while waiting for the lock, keeping them")
						} else {
							deleteOrWarn(syncMetaFile)
							deleteOrWarn(cacheFileForProject(projectDir))
						}
					}

				if (!locked) {
					logger.debug("Sync lock unavailable, leaving the stale sync files to the running sync")
				}
			} catch (err: CancellationException) {
				throw err
			} catch (err: Throwable) {
				/*
				 * Creating or opening the lock file fails on a read-only volume, and the re-read
				 * under the lock can fail on a corrupt file. Callers treat checkSyncNeeded as a
				 * boolean query and rethrow anything else, so letting either escape would crash
				 * the project open instead of resyncing it.
				 */
				logger.warn("Failed to discard the stale sync files", err)
			}
		}
	}

	/**
	 * Delete [file], warning if it survives. A stale file left behind is read back on the next
	 * launch, so a silent failure here is worth a log line.
	 */
	private fun deleteOrWarn(file: File) {
		if (!file.delete() && file.exists()) {
			logger.warn("Failed to delete stale sync file: {}", file)
		}
	}

	private suspend fun computeHashes(files: List<SyncMetaModels.FileInfoOrBuilder>): Map<SyncMetaModels.FileInfoOrBuilder, String> =
		coroutineScope {
			withContext(hashDispatcher) {
				val deferred =
					files.associateWith { file ->
						async { File(file.canonicalPath).sha256() }
					}

				deferred.mapValues { (_, d) -> d.await() }
			}
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
			projectModelInfo = projectModelInfo,
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
		if (!Files.exists(projectDir)) {
			logger.warn("Project directory does not exist: {}", projectDir)
			throw FileNotFoundException("Project directory missing: $projectDir")
		}
		val projectDir = projectDir.toRealPath()
		return SyncMeta(
			metaVersion = SYNC_META_VERSION,
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
		includeChecksum: Boolean = false,
	): List<SyncMetaModels.FileInfo> =
		collectWatchedFiles(projectDir).map { (file, attrs) ->
			FileInfo(
				relativePath = projectDir.relativize(file).pathString,
				canonicalPath = file.toRealPath().pathString,
				size = attrs.size(),
				mtime = attrs.lastModifiedTime().toMillis(),
				sha256 = if (includeChecksum) withContext(Dispatchers.Default) { file.sha256() } else null,
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

		Files.walkFileTree(
			projectDir,
			object : SimpleFileVisitor<Path>() {
				override fun preVisitDirectory(
					path: Path,
					attributes: BasicFileAttributes,
				): FileVisitResult {
					val dirName = path.fileName?.toString() ?: ""
					if (dirName in excludedDirectoryNames) return FileVisitResult.SKIP_SUBTREE

					val key = runCatching { attributes.fileKey() }.getOrDefault(null)
					val id =
						key ?: runCatching { path.toRealPath().pathString }.getOrElse {
							path.toAbsolutePath().normalize().pathString
						}
					if (!visited.add(id)) return FileVisitResult.SKIP_SUBTREE
					return FileVisitResult.CONTINUE
				}

				override fun visitFile(
					path: Path,
					attributes: BasicFileAttributes,
				): FileVisitResult {
					val fileName = path.fileName?.toString() ?: ""
					if (fileName in watchedFileNames || watchedFileMatcher.matches(path)) {
						results.add(path to attributes)
					}

					return FileVisitResult.CONTINUE
				}

				override fun visitFileFailed(
					p0: Path,
					p1: IOException,
				): FileVisitResult =
					// ignore files we can't read
					FileVisitResult.CONTINUE
			},
		)

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
			readSyncMeta(file)
		}

	private fun readSyncMeta(file: File): SyncMetaModels.SyncMeta =
		file.inputStream().buffered().use { fileIn ->
			SyncMetaModels.SyncMeta.parseFrom(fileIn)
		}
}
