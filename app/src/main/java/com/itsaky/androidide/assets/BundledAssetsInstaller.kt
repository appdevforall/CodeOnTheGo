package com.itsaky.androidide.assets

import android.content.Context
import androidx.annotation.WorkerThread
import com.aayushatharva.brotli4j.decoder.BrotliInputStream
import com.itsaky.androidide.app.configuration.CpuArch
import com.itsaky.androidide.managers.ToolsManager
import com.itsaky.androidide.utils.Environment
import com.itsaky.androidide.utils.TerminalInstaller
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
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.outputStream

data object BundledAssetsInstaller : BaseAssetsInstaller() {

    private val logger = LoggerFactory.getLogger(BundledAssetsInstaller::class.java)

    override suspend fun preInstall(
        context: Context,
        stagingDir: Path,
    ): Unit = withContext(Dispatchers.IO) {
        // For bundled builds, we only need to copy bootstrap packages to the staging directory.
        // Other assets can be read directly from the assets input stream.

        val assets = context.assets
        val assetPath =
            ToolsManager.getCommonAsset("${AssetsInstallationHelper.BOOTSTRAP_ENTRY_NAME}.br")

        // Decompress brotli and write the contained ZIP file
        BrotliInputStream(assets.open(assetPath)).use { input ->
            stagingDir.resolve(AssetsInstallationHelper.BOOTSTRAP_ENTRY_NAME).outputStream()
                .use { output ->
                    input.copyTo(output)
                }
        }
    }

    @WorkerThread
    override suspend fun doInstall(
        context: Context,
        stagingDir: Path,
        cpuArch: CpuArch,
        entryName: String,
    ): Unit = withContext(Dispatchers.IO) {
        val assets = context.assets
        when (entryName) {
            GRADLE_DISTRIBUTION_ARCHIVE_NAME,
            ANDROID_SDK_ZIP,
            LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME -> {
                val assetPath = ToolsManager.getCommonAsset("${entryName}.br")
                val srcStream = BrotliInputStream(assets.open(assetPath))
                val destDir = destinationDirForArchiveEntry(entryName).toPath()
                AssetsInstallationHelper.extractZipToDir(srcStream, destDir)
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
                val channel =
                    Files.newByteChannel(stagingDir.resolve(AssetsInstallationHelper.BOOTSTRAP_ENTRY_NAME))
                val result = TerminalInstaller.installIfNeeded(context, channel)
                if (result !is TerminalInstaller.InstallResult.Success) {
                    throw IllegalStateException("Failed to install terminal: $result")
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
                val assetPath = "dynamic_libs/${sourceAssetName}.br"

                val destDir = context.getDir("dynamic_libs", Context.MODE_PRIVATE)
                destDir.mkdirs()
                val destFile = File(destDir, "llama.aar")

                logger.debug("Extracting '{}' to {}", assetPath, destFile.absolutePath)
                BrotliInputStream(assets.open(assetPath)).use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }

            else -> throw IllegalStateException("Unknown entry: $entryName")
        }
    }

    private fun destinationDirForArchiveEntry(entryName: String): File = when (entryName) {
        GRADLE_DISTRIBUTION_ARCHIVE_NAME -> Environment.GRADLE_DISTS
        ANDROID_SDK_ZIP -> Environment.ANDROID_HOME
        LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME -> Environment.LOCAL_MAVEN_DIR
        GRADLE_API_NAME_JAR_ZIP -> Environment.GRADLE_GEN_JARS
        else -> throw IllegalStateException("Entry '$entryName' is not expected to be an archive")
    }
}