package com.itsaky.androidide.assets

import android.content.Context
import androidx.annotation.WorkerThread
import com.itsaky.androidide.app.configuration.CpuArch
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.utils.Environment
import com.itsaky.androidide.utils.TerminalInstaller
import com.itsaky.androidide.utils.retryOnceOnNoSuchFile
import com.itsaky.androidide.utils.withTempZipChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.adfa.constants.ANDROID_SDK_ZIP
import org.adfa.constants.DOCUMENTATION_DB
import org.adfa.constants.GRADLE_API_NAME_JAR_ZIP
import org.adfa.constants.GRADLE_DISTRIBUTION_ARCHIVE_NAME
import org.adfa.constants.LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import java.util.zip.ZipInputStream
import kotlin.system.measureTimeMillis

data object SplitAssetsInstaller : BaseAssetsInstaller() {
	private val logger = LoggerFactory.getLogger(SplitAssetsInstaller::class.java)
	private lateinit var zipFile: ZipFile

	override suspend fun preInstall(
		context: Context,
		stagingDir: Path,
	): Unit =
		withContext(Dispatchers.IO) {
			if (!Environment.SPLIT_ASSETS_ZIP.exists()) {
				throw FileNotFoundException("Assets zip file not found at path: ${Environment.SPLIT_ASSETS_ZIP.path}")
			}

			zipFile = ZipFile(Environment.SPLIT_ASSETS_ZIP)
		}

	@OptIn(ExperimentalPathApi::class)
	@WorkerThread
	override suspend fun doInstall(
		context: Context,
		stagingDir: Path,
		cpuArch: CpuArch,
		entryName: String,
	): Unit =
		withContext(Dispatchers.IO) {
			val entry = zipFile.getEntry(entryName)
			?: throw FileNotFoundException(context.getString(R.string.err_asset_entry_not_found, entryName))
			val time =
				measureTimeMillis {
					zipFile.getInputStream(entry).use { zipInput ->
						when (entry.name) {
							GRADLE_DISTRIBUTION_ARCHIVE_NAME,
							ANDROID_SDK_ZIP,
							LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME,
							GRADLE_API_NAME_JAR_ZIP,
							-> {
								val destDir = destinationDirForArchiveEntry(entry.name).toPath()
								if (Files.exists(destDir)) {
									destDir.deleteRecursively()
								}
								Files.createDirectories(destDir)
								logger.debug("Extracting '{}' to dir: {}", entry.name, destDir)
								AssetsInstallationHelper.extractZipToDir(zipInput, destDir)
								logger.debug("Completed extracting '{}' to dir: {}", entry.name, destDir)
							}

                            AssetsInstallationHelper.BOOTSTRAP_ENTRY_NAME -> {
								logger.debug("Extracting 'bootstrap.zip' to dir: {}", stagingDir)

								val result = retryOnceOnNoSuchFile(
									onFirstFailure = { Files.createDirectories(stagingDir) },
									onSecondFailure = { e2 ->
										logger.error("Failed to open temporary bootstrap zip after retry", e2)
										return@withContext
									}
								) {
									withTempZipChannel(
										stagingDir = stagingDir,
										prefix = "bootstrap",
										writeTo = { path ->
											zipFile.getInputStream(entry).use { freshZipInput ->
												Files.newOutputStream(path).use { out ->
													freshZipInput.copyTo(out)
												}
											}
										},
										useChannel = { ch ->
											TerminalInstaller.installIfNeeded(context, ch)
										}
									)
								}

								if (result !is TerminalInstaller.InstallResult.Success) {
									logger.error("Failed to install terminal: {}", result)
								}

								logger.debug("Completed extracting 'bootstrap.zip' to dir: {}", stagingDir)
							}

							DOCUMENTATION_DB -> {
								logger.debug("Extracting '{}' to {}", DOCUMENTATION_DB, Environment.DOC_DB)

								// prefer already extracted documentation.db -- only in debug builds
								val splitDb = Environment.DOWNLOAD_DIR.resolve(DOCUMENTATION_DB)
								if (splitDb.exists() && splitDb.isFile) {
									splitDb.inputStream()
								} else {
									zipInput
								}.use { stream ->
									Environment.DOC_DB.outputStream().use { output ->
										stream.copyTo(output)
									}
								}
								logger.debug("Completed extracting '{}' to {}", DOCUMENTATION_DB, Environment.DOC_DB)
							}
                            AssetsInstallationHelper.LLAMA_AAR -> {
                                val destDir = context.getDir("dynamic_libs", Context.MODE_PRIVATE)
                                destDir.mkdirs()
                                val destFile = File(destDir, "llama.aar")

                                logger.debug("Extracting '{}' to {}", entry.name, destFile.absolutePath)
                                destFile.outputStream().use { output ->
                                    zipInput.copyTo(output)
                                }
                            }
                            AssetsInstallationHelper.PLUGIN_ARTIFACTS_ZIP -> {
                                logger.debug("Extracting plugin artifacts from '{}'", entry.name)
                                val pluginDir = Environment.PLUGIN_API_JAR.parentFile
                                    ?: throw IllegalStateException("Plugin API parent directory is null")
                                val pluginDirPath = pluginDir.toPath().toAbsolutePath().normalize()
                                if (Files.exists(pluginDirPath)) {
                                    pluginDirPath.deleteRecursively()
                                }
                                Files.createDirectories(pluginDirPath)

                                ZipInputStream(zipInput).use { pluginZip ->
                                    var pluginEntry = pluginZip.nextEntry
                                    while (pluginEntry != null) {
                                        if (!pluginEntry.isDirectory) {
                                            val targetPath = pluginDirPath.resolve(pluginEntry.name).normalize()
                                            // Security check: prevent path traversal attacks
                                            if (!targetPath.startsWith(pluginDirPath)) {
                                                throw IllegalStateException(
                                                    "Zip entry '${pluginEntry.name}' would escape target directory"
                                                )
                                            }
                                            val targetFile = targetPath.toFile()
                                            targetFile.parentFile?.mkdirs()
                                            logger.debug("Extracting '{}' to {}", pluginEntry.name, targetFile)
                                            targetFile.outputStream().use { output ->
                                                pluginZip.copyTo(output)
                                            }
                                        }
                                        pluginEntry = pluginZip.nextEntry
                                    }
                                }
                                logger.debug("Completed extracting plugin artifacts")
                            }
							else -> throw IllegalStateException("Unknown entry: $entryName")
						}
					}
				}
			logger.info("Extraction of '{}' completed in {}ms", entry.name, time)
		}

	override suspend fun postInstall(
		context: Context,
		stagingDir: Path,
	) {
		withContext(Dispatchers.IO) {
			zipFile.close()
		}
		super.postInstall(context, stagingDir)
	}

    override fun expectedSize(entryName: String): Long = when (entryName) {
        GRADLE_DISTRIBUTION_ARCHIVE_NAME -> 137260932L
        ANDROID_SDK_ZIP                  -> 286625871L
        DOCUMENTATION_DB                  -> 224296960L
        LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME -> 215389106L
        AssetsInstallationHelper.BOOTSTRAP_ENTRY_NAME -> 456462823L
        GRADLE_API_NAME_JAR_ZIP           -> 46758608L
        else -> 0L
    }

    private fun destinationDirForArchiveEntry(entryName: String): File =
		when (entryName) {
			GRADLE_DISTRIBUTION_ARCHIVE_NAME -> Environment.GRADLE_DISTS
			ANDROID_SDK_ZIP -> Environment.ANDROID_HOME
			LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME -> Environment.LOCAL_MAVEN_DIR
			GRADLE_API_NAME_JAR_ZIP -> Environment.GRADLE_GEN_JARS
			else -> throw IllegalStateException("Entry '$entryName' is not expected to be an archive")
		}

}



