package com.itsaky.androidide.utils

import android.content.Context
import androidx.annotation.WorkerThread
import com.aayushatharva.brotli4j.decoder.BrotliInputStream
import com.itsaky.androidide.app.configuration.CpuArch
import com.itsaky.androidide.managers.ToolsManager
import com.itsaky.androidide.utils.AssetsInstallationHelper.BOOTSTRAP_ENTRY_NAME
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.adfa.constants.ANDROID_SDK_ZIP
import org.adfa.constants.DOCUMENTATION_DB
import org.adfa.constants.GRADLE_API_NAME_JAR
import org.adfa.constants.GRADLE_API_NAME_JAR_BR
import org.adfa.constants.GRADLE_API_NAME_JAR_ZIP
import org.adfa.constants.GRADLE_WRAPPER_FILE_NAME
import org.adfa.constants.LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream
import kotlin.io.path.outputStream

object AssetsInstaller {

    private val logger = LoggerFactory.getLogger(AssetsInstaller::class.java)

    @WorkerThread
    suspend fun doInstall(
        context: Context,
        stagingDir: Path,
        cpuArch: CpuArch,
        entryName: String,
    ): Unit = withContext(Dispatchers.IO) {
        val assets = context.assets
        when (entryName) {
            GRADLE_WRAPPER_FILE_NAME,
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

            BOOTSTRAP_ENTRY_NAME -> {
                val channel = Files.newByteChannel(stagingDir.resolve(BOOTSTRAP_ENTRY_NAME))
                val result = TerminalInstaller.installIfNeeded(context, channel)
                if (result !is TerminalInstaller.InstallResult.Success) {
                    throw IllegalStateException("Failed to install terminal: $result")
                }
            }

            DOCUMENTATION_DB -> {
                // prefer already extracted documentation.db -- only in debug builds
                assets.open(ToolsManager.getDatabaseAsset(DOCUMENTATION_DB)).use { input ->
                    Environment.DOC_DB.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }

            else -> throw IllegalStateException("Unknown entry: $entryName")
        }
    }

    private fun destinationDirForArchiveEntry(entryName: String): File = when (entryName) {
        GRADLE_WRAPPER_FILE_NAME -> Environment.GRADLE_DISTS
        ANDROID_SDK_ZIP -> Environment.ANDROID_HOME
        LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME -> Environment.LOCAL_MAVEN_DIR
        GRADLE_API_NAME_JAR_ZIP -> Environment.GRADLE_GEN_JARS
        else -> throw IllegalStateException("Entry '$entryName' is not expected to be an archive")
    }

    suspend fun preInstall(
        context: Context,
        stagingDir: Path,
    ) = withContext(Dispatchers.IO) {
        // For release builds, we only need to copy bootstrap packages to the staging directory.
        // Other assets can be read directly from the assets input stream.

        val assets = context.assets
        val assetPath = ToolsManager.getCommonAsset("${BOOTSTRAP_ENTRY_NAME}.br")

        // Decompress brotli and write the contained ZIP file
        BrotliInputStream(assets.open(assetPath)).use { input ->
            stagingDir.resolve(BOOTSTRAP_ENTRY_NAME).outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }
}