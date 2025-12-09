package com.itsaky.androidide.projects.serial

import com.itsaky.androidide.project.GradleModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.outputStream

/**
 * Utilities for reading/writing proto project models.
 *
 * @author Akash Yadav
 */
object ProtoProject {
	private val logger = LoggerFactory.getLogger(ProtoProject::class.java)

	const val PROTO_CACHE_FILE_BASENAME = "project"
	const val PROTO_FILE_EXTENSION = "pb"
	const val PROTO_CACHE_FILE_NAME = "${PROTO_CACHE_FILE_BASENAME}.${PROTO_FILE_EXTENSION}"

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
				StandardCopyOption.REPLACE_EXISTING,
				StandardCopyOption.ATOMIC_MOVE,
			)
		}.getOrThrow()
	}
}
