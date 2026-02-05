package com.itsaky.androidide.assets

import android.content.Context
import android.os.StatFs
import androidx.annotation.WorkerThread
import com.aayushatharva.brotli4j.Brotli4jLoader
import com.itsaky.androidide.app.configuration.IDEBuildConfigProvider
import com.itsaky.androidide.utils.useEntriesEach
import com.itsaky.androidide.utils.Environment.DEFAULT_ROOT
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
import com.itsaky.androidide.resources.R
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.math.pow

typealias AssetsInstallerProgressConsumer = (AssetsInstallationHelper.Progress) -> Unit

object AssetsInstallationHelper {
    private const val STATUS_INSTALLING = "Installing"
    private const val STATUS_FINISHED = "FINISHED"

	sealed interface Result {
		data object Success : Result

		data class Failure(
			val cause: Throwable?,
			val errorMessage: String? = cause?.message,
			val shouldReportToSentry: Boolean = true
		) : Result
	}

	data class Progress(
		val message: String,
	)

    const val LLAMA_AAR = "dynamic_libs/llama.aar"
    const val PLUGIN_ARTIFACTS_ZIP = "plugin-artifacts.zip"
    private val logger = LoggerFactory.getLogger(AssetsInstallationHelper::class.java)
	private val ASSETS_INSTALLER = AssetsInstaller.CURRENT_INSTALLER
	const val BOOTSTRAP_ENTRY_NAME = "bootstrap.zip"

	suspend fun install(
		context: Context,
		onProgress: AssetsInstallerProgressConsumer = {},
	): Result =
		withContext(Dispatchers.IO) {
			checkStorageAccessibility(context, onProgress)?.let { return@withContext it }

			val result =
				runCatching {
					doInstall(context, onProgress)
				}

			if (result.isFailure) {
				val e = result.exceptionOrNull()
				val msg = e?.message ?: "Failed to install assets"
				logger.error("Failed to install assets", e)
				onProgress(Progress(msg))
				return@withContext Result.Failure(e, errorMessage = msg)
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
                LLAMA_AAR,
                PLUGIN_ARTIFACTS_ZIP
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

        val entrySizes: Map<String, Long> = expectedEntries.associateWith { entry ->
            ASSETS_INSTALLER.expectedSize(entry)
        }

        val totalSize = entrySizes.values.sum()

        val entryStatusMap = ConcurrentHashMap<String, String>()

		val installerJobs =
			expectedEntries.map { entry ->
				async {
					entryStatusMap[entry] = STATUS_INSTALLING

					ASSETS_INSTALLER.doInstall(
						context = context,
						stagingDir = stagingDir,
						cpuArch = cpuArch,
						entryName = entry,
					)

					entryStatusMap[entry] = STATUS_FINISHED
				}
			}

		val progressUpdater =
            launch {
                var previousSnapshot = ""
                while (isActive) {
                    val installedSize = entryStatusMap
                        .filterValues { it == STATUS_FINISHED }
                        .keys
                        .sumOf { entrySizes[it] ?: 0 }

                    val percent = if (totalSize > 0) {
                        (installedSize * 100.0 / totalSize)
                    } else 0.0

                    val freeStorage = getAvailableStorage(File(DEFAULT_ROOT))

                    val snapshot =
                        if (percent >= 99.0) {
                            "Post install processing in progress...."
                        } else {
                            buildString {
                                entryStatusMap.forEach { (entry, status) ->
                                    appendLine("$entry ${if (status == STATUS_FINISHED) "✓" else ""}")
                                }
                                appendLine("--------------------")
                                appendLine("Progress: ${formatPercent(percent)}")
                                appendLine("Installed: ${formatBytes(installedSize)} / ${formatBytes(totalSize)}")
                                appendLine("Remaining storage: ${formatBytes(freeStorage)}")
                            }
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
		// Normalize and make destDir absolute for secure path validation
		val normalizedDestDir = destDir.toAbsolutePath().normalize()
		
		ZipInputStream(srcStream.buffered()).useEntriesEach { zipInput, entry ->
			// Validate entry name doesn't contain dangerous patterns
			if (entry.name.contains("..") || entry.name.startsWith("/") || entry.name.startsWith("\\")) {
				throw IllegalStateException("Zip entry contains dangerous path components: ${entry.name}")
			}
			
			val destFile = normalizedDestDir.resolve(entry.name).normalize()
			
			// Use Path.startsWith() for proper path validation instead of string comparison
			if (!destFile.startsWith(normalizedDestDir)) {
				// DO NOT allow extraction to outside of the target dir
				throw IllegalStateException("Entry is outside of the target dir: ${entry.name}")
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

    private fun getAvailableStorage(path: File): Long {
        return try {
            val stat = StatFs(path.absolutePath)
            stat.availableBytes
        } catch (e: Exception) {
            logger.warn("Failed to get available storage for {}: {}", path, e.message)
            -1L
        }
    }

    private fun formatBytes(bytes: Long): String {
        val unit = 1024
        if (bytes < unit) return "$bytes B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(unit.toDouble())).toInt()
        val pre = "KMGTPE"[exp - 1]
        return String.format(
            Locale.getDefault(), // use device locale
            "%.1f %sB",
            bytes / unit.toDouble().pow(exp.toDouble()),
            pre
        )
    }

    private fun formatPercent(value: Double): String {
        return String.format(Locale.getDefault(), "%.1f%%", value)
    }

	private fun checkStorageAccessibility(
		context: Context,
		onProgress: AssetsInstallerProgressConsumer,
	): Result.Failure? {
		val rootDir = File(DEFAULT_ROOT)

		if (!rootDir.exists()) {
			runCatching {
				rootDir.mkdirs()
			}.onFailure { logger.warn("Failed to create root dir: ${it.message}") }
		}

		if (!rootDir.exists() || !rootDir.canWrite()) {
			val errorMsg = context.getString(R.string.storage_not_accessible)
			logger.error("Storage not accessible: {}", DEFAULT_ROOT)
			onProgress(Progress(errorMsg))
			return Result.Failure(
				IllegalStateException(errorMsg),
				errorMsg,
				false
			)
		}
		return null
	}
}
