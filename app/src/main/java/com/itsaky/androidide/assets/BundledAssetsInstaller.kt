package com.itsaky.androidide.assets

import android.content.Context
import androidx.annotation.WorkerThread
import com.aayushatharva.brotli4j.decoder.BrotliInputStream
import com.itsaky.androidide.app.configuration.CpuArch
import com.itsaky.androidide.managers.ToolsManager
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.utils.Environment
import com.itsaky.androidide.utils.TerminalInstaller
import com.itsaky.androidide.utils.retryOnceOnNoSuchFile
import com.itsaky.androidide.utils.withTempZipChannel
import com.itsaky.androidide.utils.writeBrotliAssetToPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.adfa.constants.ANDROID_SDK_ZIP
import org.adfa.constants.DOCUMENTATION_DB
import org.adfa.constants.GRADLE_API_NAME_JAR
import org.adfa.constants.GRADLE_API_NAME_JAR_BR
import org.adfa.constants.GRADLE_API_NAME_JAR_ZIP
import org.adfa.constants.GRADLE_DISTRIBUTION_ARCHIVE_NAME
import org.adfa.constants.LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream

data object BundledAssetsInstaller : BaseAssetsInstaller() {
	private val logger = LoggerFactory.getLogger(BundledAssetsInstaller::class.java)

	// Do nothing here
	// All assets (including bootstrap packages) will be read directly from the assets input stream
	override suspend fun preInstall(
		context: Context,
		stagingDir: Path,
	): Unit = Unit

	@WorkerThread
	override suspend fun doInstall(
		context: Context,
		stagingDir: Path,
		cpuArch: CpuArch,
		entryName: String,
	): Unit =
		withContext(Dispatchers.IO) {
			val assets = context.assets
			when (entryName) {
				GRADLE_DISTRIBUTION_ARCHIVE_NAME,
				ANDROID_SDK_ZIP,
				LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME,
				-> {
					val assetPath = ToolsManager.getCommonAsset("$entryName.br")
					assets.open(assetPath).use { assetStream ->
						BrotliInputStream(assetStream).use { srcStream ->
							val destDir = destinationDirForArchiveEntry(entryName).toPath()
							AssetsInstallationHelper.extractZipToDir(srcStream, destDir)
						}
					}
				}

				GRADLE_API_NAME_JAR_ZIP -> {
					val assetPath = ToolsManager.getCommonAsset(GRADLE_API_NAME_JAR_BR)
					BrotliInputStream(assets.open(assetPath)).use { input ->
						val destFile = Environment.GRADLE_GEN_JARS.resolve(GRADLE_API_NAME_JAR)
						destFile.outputStream().use { output ->
							input.copyTo(output)
						}
					}
				}

				AssetsInstallationHelper.BOOTSTRAP_ENTRY_NAME -> {
					val assetPath =
						ToolsManager.getCommonAsset("${AssetsInstallationHelper.BOOTSTRAP_ENTRY_NAME}.br")

					val result = retryOnceOnNoSuchFile (
						onFirstFailure = { Files.createDirectories(stagingDir) },
						onSecondFailure = { e2 ->
            	throw IOException(
								context.getString(R.string.terminal_installation_failed_low_storage),
								e2
							)
						}
					) {
						withTempZipChannel(
							stagingDir = stagingDir,
							prefix = "bootstrap",
							writeTo = { path -> writeBrotliAssetToPath(context, assetPath, path) },
							useChannel = { ch -> TerminalInstaller.installIfNeeded(context, ch) }
						)
					}

					when (result) {
						is TerminalInstaller.InstallResult.Success -> {}
						is TerminalInstaller.InstallResult.Error.Interactive -> {
							throw IOException("${result.title}: ${result.message}")
						}
						is TerminalInstaller.InstallResult.Error.IsSecondaryUser -> {
							throw IOException(
								context.getString(R.string.terminal_installation_failed_secondary_user)
							)
						}
						is TerminalInstaller.InstallResult.NotInstalled -> {
							throw IllegalStateException("Terminal installation failed: NotInstalled state")
						}
					}
				}

				DOCUMENTATION_DB -> {
					BrotliInputStream(assets.open(ToolsManager.getDatabaseAsset("${DOCUMENTATION_DB}.br"))).use { input ->
						Environment.DOC_DB.outputStream().use { output ->
							input.copyTo(output)
						}
					}
				}
                AssetsInstallationHelper.LLAMA_AAR -> {
                    val sourceAssetName = when (cpuArch) {
                        CpuArch.AARCH64 -> "llama-v8.aar"
                        CpuArch.ARM -> "llama-v7.aar"
                        else -> {
                            logger.warn("Unsupported CPU arch for Llama AAR: $cpuArch. Skipping.")
                            return@withContext
                        }
                    }
                    val candidates = listOf(
                        "dynamic_libs/${sourceAssetName}.br", // preferred (compressed)
                        "dynamic_libs/${sourceAssetName}",    // fallback (uncompressed)
                    )

                    val destDir = context.getDir("dynamic_libs", Context.MODE_PRIVATE)
                    destDir.mkdirs()
                    val destFile = File(destDir, "llama.aar")

                    val opened = candidates.firstNotNullOfOrNull { path ->
                        try {
                            path to assets.open(path)
                        } catch (_: FileNotFoundException) {
                            null
                        }
                    } ?: run {
                        logger.warn(
                            "Llama AAR asset not found for arch {}. Tried {}",
                            cpuArch,
                            candidates
                        )
                        return@withContext
                    }

                    val (assetPath, stream) = opened
                    logger.debug("Extracting '{}' to {}", assetPath, destFile.absolutePath)

                    val inputStream =
                        if (assetPath.endsWith(".br")) BrotliInputStream(stream) else stream

                    inputStream.use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                AssetsInstallationHelper.PLUGIN_ARTIFACTS_ZIP -> {
                    logger.debug("Extracting plugin artifacts from '{}'", entryName)
                    val pluginDir = Environment.PLUGIN_API_JAR.parentFile
                        ?: throw IllegalStateException("Plugin API parent directory is null")
                    pluginDir.mkdirs()
                    val pluginDirPath = pluginDir.toPath().toAbsolutePath().normalize()

                    val assetPath = ToolsManager.getCommonAsset("$entryName.br")
                    assets.open(assetPath).use { assetStream ->
                        BrotliInputStream(assetStream).use { brotliStream ->
                            ZipInputStream(brotliStream).use { pluginZip ->
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
                        }
                    }
                    logger.debug("Completed extracting plugin artifacts")
                }
				else -> throw IllegalStateException("Unknown entry: $entryName")
			}
		}

    override fun expectedSize(entryName: String): Long = when (entryName) {
        GRADLE_DISTRIBUTION_ARCHIVE_NAME -> 63399283L
        ANDROID_SDK_ZIP                  -> 53226785L
        DOCUMENTATION_DB                  -> 297763377L
        LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME -> 97485855L
        AssetsInstallationHelper.BOOTSTRAP_ENTRY_NAME -> 124120151L
        GRADLE_API_NAME_JAR_ZIP           -> 29447748L
        AssetsInstallationHelper.PLUGIN_ARTIFACTS_ZIP -> 86442L
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
