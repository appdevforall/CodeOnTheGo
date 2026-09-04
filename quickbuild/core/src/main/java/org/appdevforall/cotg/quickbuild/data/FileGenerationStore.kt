package org.appdevforall.cotg.quickbuild.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.appdevforall.cotg.quickbuild.domain.reload.GenerationStore
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException

/**
 * Keeps the generation counter in `<project>/.androidide/quickbuild/generation`.
 *
 * Lives with the project rather than in the app-private [QuickBuildScratch] tree because
 * scratch is deleted on session teardown while this counter must outlive sessions: an
 * installed proxy app keys its payloads by generation, so only a surviving counter lets a
 * later session stay strictly newer. A corrupt or unreadable file loads as null (fresh
 * session), so a broken state file cannot take quick build down.
 *
 * Every read and write runs under [ioDispatcher]: the file sits under the project root on
 * FUSE-backed storage, and the callers are on the session thread that concurrency.md says
 * must not block.
 *
 * @property file the counter file; it need not exist yet, its parent directory is created on
 *   first [save], and a sibling `.tmp` is the write staging path.
 * @property ioDispatcher where the file I/O runs; injectable so tests can pin the hop.
 */
class FileGenerationStore(
	private val file: File,
	private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : GenerationStore {
	/**
	 * Reads the persisted counter.
	 *
	 * @return the stored generation, or null when the file is missing, unreadable, or does not
	 *   parse as a Long - all of which the caller treats as a fresh session.
	 */
	override suspend fun load(): Long? =
		withContext(ioDispatcher) {
			try {
				if (file.isFile) file.readText().trim().toLongOrNull() else null
			} catch (e: IOException) {
				log.warn("Failed to read generation from {}; starting fresh", file, e)
				null
			}
		}

	/**
	 * Persists the counter atomically via temp file plus rename.
	 *
	 * @param generation the value to store; the caller guarantees it is strictly greater than
	 *   any previously saved one, since the installed proxy app keys its payloads by it.
	 * @throws IOException when the value could not be persisted: the staged write failed
	 *   before any rename was tried, or both renames AND the direct-write fallback failed.
	 *   Unlike [load] this is never swallowed, since losing it would let a later session
	 *   reuse a generation.
	 */
	override suspend fun save(generation: Long) =
		withContext(ioDispatcher) {
			file.parentFile?.mkdirs()
			val tmp = File(file.parentFile, file.name + ".tmp")
			tmp.writeText(generation.toString())
			if (!tmp.renameTo(file)) {
				// Windows-style rename-over-existing failure path; harmless on device but
				// keeps the store correct wherever the JVM tests run.
				file.delete()
				if (!tmp.renameTo(file)) {
					// The old value is already deleted, so a bare throw here would leave NO
					// counter at all - the next load() would restart the sequence, the exact
					// reuse the class exists to rule out. Non-atomic beats lost.
					try {
						file.writeText(generation.toString())
					} catch (e: IOException) {
						throw IOException("Unable to persist generation $generation to $file", e)
					} finally {
						tmp.delete()
					}
				}
			}
		}

	companion object {
		private val log = LoggerFactory.getLogger("QB-GenerationStore")

		/**
		 * Builds a store at the canonical per-project location of the generation file.
		 *
		 * @param projectRoot the user project's root directory; the file lands at
		 *   `.androidide/quickbuild/generation` beneath it, and neither need exist yet.
		 * @param ioDispatcher where the file I/O runs; see the class KDoc.
		 * @return a store for that path; no filesystem access happens until [load] or [save].
		 */
		fun forProject(
			projectRoot: File,
			ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
		): FileGenerationStore = FileGenerationStore(File(projectRoot, ".androidide/quickbuild/generation"), ioDispatcher)
	}
}
