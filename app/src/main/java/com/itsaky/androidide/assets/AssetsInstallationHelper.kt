package com.itsaky.androidide.assets

import android.content.Context
import androidx.annotation.WorkerThread
import com.aayushatharva.brotli4j.Brotli4jLoader
import com.itsaky.androidide.app.configuration.IDEBuildConfigProvider
import com.itsaky.androidide.utils.useEntriesEach
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.adfa.constants.ANDROID_SDK_ZIP
import org.adfa.constants.DOCUMENTATION_DB
import org.adfa.constants.GRADLE_API_NAME_JAR_ZIP
import org.adfa.constants.GRADLE_DISTRIBUTION_ARCHIVE_NAME
import org.adfa.constants.LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME
import org.slf4j.LoggerFactory
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.pathString

typealias AssetsInstallerProgressConsumer = (AssetsInstallationHelper.Progress) -> Unit

object AssetsInstallationHelper {
	sealed interface Result {
		data object Success : Result

		data class Failure(
			val cause: Throwable?,
		) : Result
	}

	data class Progress(
		val message: String,
	)

	private val logger = LoggerFactory.getLogger(AssetsInstallationHelper::class.java)
	private val ASSETS_INSTALLER = AssetsInstaller.CURRENT_INSTALLER
	const val BOOTSTRAP_ENTRY_NAME = "bootstrap.zip"

	suspend fun install(
		context: Context,
		onProgress: AssetsInstallerProgressConsumer = {},
	): Result =
		withContext(Dispatchers.IO) {
			val result =
				runCatching {
					doInstall(context, onProgress)
				}

			if (result.isFailure) {
				logger.error("Failed to install assets", result.exceptionOrNull())
				onProgress(Progress("Failed to install assets"))
				return@withContext Result.Failure(result.exceptionOrNull())
			}

			return@withContext Result.Success
		}

	@OptIn(ExperimentalPathApi::class)
	private suspend fun doInstall(
		context: Context,
		onProgress: AssetsInstallerProgressConsumer,
	) = coroutineScope {
		onProgress(Progress("Preparing..."))

		val buildConfig = IDEBuildConfigProvider.getInstance()
		val cpuArch = buildConfig.cpuArch
		val expectedEntries =
			arrayOf(
				GRADLE_DISTRIBUTION_ARCHIVE_NAME,
				ANDROID_SDK_ZIP,
				DOCUMENTATION_DB,
				LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME,
				BOOTSTRAP_ENTRY_NAME,
				GRADLE_API_NAME_JAR_ZIP,
			)

		val stagingDir = Files.createTempDirectory(UUID.randomUUID().toString())
		logger.debug("Staging directory ({}): {}", cpuArch, stagingDir)

		// Ensure relevant shared libraries are loaded
		Brotli4jLoader.ensureAvailability()

		// pre-install hook
		val isPreInstallSuccessful =
			try {
				ASSETS_INSTALLER.preInstall(context, stagingDir)
				true
			} catch (e: FileNotFoundException) {
				logger.error("ZIP file not found: {}", e.message)
				onProgress(Progress("${e.message}"))
				false
			} catch (e: ZipException) {
				logger.error("Invalid ZIP format: {}", e.message)
				onProgress(Progress("Corrupt zip file ${e.message}"))
				false
			} catch (e: IOException) {
				logger.error("I/O error during preInstall: {}", e.message)
				onProgress(Progress("Failed to load ${e.message}"))
				false
			}

		if (!isPreInstallSuccessful) {
			return@coroutineScope Result.Failure(IOException("preInstall failed"))
		}

		val entryStatusMap = ConcurrentHashMap<String, String>()

		val installerJobs =
			expectedEntries.map { entry ->
				async {
					entryStatusMap[entry] = "Installing"

					ASSETS_INSTALLER.doInstall(
						context = context,
						stagingDir = stagingDir,
						cpuArch = cpuArch,
						entryName = entry,
					)

					entryStatusMap[entry] = "FINISHED"
				}
			}

		val progressUpdater =
			launch {
				var previousSnapshot = ""
				while (isActive) {
					val snapshot =
						entryStatusMap.entries.joinToString("\n") { (entry, status) ->
							"$entry → $status"
						}

					if (snapshot != previousSnapshot) {
						onProgress(Progress(snapshot))
						previousSnapshot = snapshot
					}

					delay(500)
				}
			}

		// wait for all jobs to complete
		installerJobs.joinAll()

		// notify post-install
		ASSETS_INSTALLER.postInstall(context, stagingDir)

		// then cancel progress updater
		progressUpdater.cancel()

		// clean up
		stagingDir.deleteRecursively()
	}

	@WorkerThread
	internal fun extractZipToDir(
		srcFile: Path,
		destDir: Path,
	) = extractZipToDir(Files.newInputStream(srcFile), destDir)

	@WorkerThread
	internal fun extractZipToDir(
		srcStream: InputStream,
		destDir: Path,
	) {
		Files.createDirectories(destDir)
		ZipInputStream(srcStream.buffered()).useEntriesEach { zipInput, entry ->
			val destFile = destDir.resolve(entry.name).normalize()
			if (!destFile.pathString.startsWith(destDir.pathString)) {
				// DO NOT allow extraction to outside of the target dir
				throw IllegalStateException("Entry is outside of the target dir: ${zipInput.buffered()}")
			}

			if (entry.isDirectory) {
				Files.createDirectories(destFile)
			} else {
				Files.newOutputStream(destFile).use { dest ->
					zipInput.copyTo(dest)
				}
			}
		}
	}
}
