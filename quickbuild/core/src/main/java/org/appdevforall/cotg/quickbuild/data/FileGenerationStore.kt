package org.appdevforall.cotg.quickbuild.data

import org.appdevforall.cotg.quickbuild.domain.GenerationStore
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
 * session) so a broken state file cannot take quick build down; writes are temp+rename so a
 * crash leaves either the old value or the new one, never a torn file.
 */
class FileGenerationStore(
	private val file: File,
) : GenerationStore {
	override fun load(): Long? =
		try {
			if (file.isFile) file.readText().trim().toLongOrNull() else null
		} catch (e: IOException) {
			log.warn("Failed to read generation from {}; starting fresh", file, e)
			null
		}

	override fun save(generation: Long) {
		file.parentFile?.mkdirs()
		val tmp = File(file.parentFile, file.name + ".tmp")
		tmp.writeText(generation.toString())
		if (!tmp.renameTo(file)) {
			// Windows-style rename-over-existing failure path; harmless on device but
			// keeps the store correct wherever the JVM tests run.
			file.delete()
			if (!tmp.renameTo(file)) {
				throw IOException("Unable to persist generation $generation to $file")
			}
		}
	}

	companion object {
		private val log = LoggerFactory.getLogger(FileGenerationStore::class.java)

		/** Builds a store at the canonical per-project location of the generation file. */
		fun forProject(projectRoot: File): FileGenerationStore = FileGenerationStore(File(projectRoot, ".androidide/quickbuild/generation"))
	}
}
