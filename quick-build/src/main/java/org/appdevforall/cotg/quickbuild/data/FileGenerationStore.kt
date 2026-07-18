package org.appdevforall.cotg.quickbuild.data

import org.appdevforall.cotg.quickbuild.domain.GenerationStore
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException

/**
 * [GenerationStore] persisted as a single number in
 * `<project>/.androidide/quickbuild/generation`.
 *
 * A corrupt or unreadable file loads as `null` (fresh session) instead of throwing:
 * the generation counter only needs monotonicity from where it can prove it, and a
 * broken state file must never take the whole quick-build feature down.
 *
 * Writes are temp+rename so a crash mid-write leaves either the old value or the new
 * one, never a torn file.
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

		/** The canonical per-project location of the generation file. */
		fun forProject(projectRoot: File): FileGenerationStore = FileGenerationStore(File(projectRoot, ".androidide/quickbuild/generation"))
	}
}
