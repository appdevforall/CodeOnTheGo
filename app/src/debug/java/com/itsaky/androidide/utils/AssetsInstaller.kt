package com.itsaky.androidide.utils

import android.content.Context
import androidx.annotation.WorkerThread
import com.itsaky.androidide.app.configuration.CpuArch
import com.itsaky.androidide.utils.AssetsInstallationHelper.BOOTSTRAP_ENTRY_NAME
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.adfa.constants.ANDROID_SDK_ZIP
import org.adfa.constants.DOCUMENTATION_DB
import org.adfa.constants.GRADLE_API_NAME_JAR_ZIP
import org.adfa.constants.GRADLE_DISTRIBUTION_ARCHIVE_NAME
import org.adfa.constants.LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream
import kotlin.io.path.exists

object AssetsInstaller {

    private val logger = LoggerFactory.getLogger(AssetsInstaller::class.java)

    @WorkerThread
    suspend fun doInstall(
        context: Context,
        stagingDir: Path,
        cpuArch: CpuArch,
        entryName: String,
    ): Unit = withContext(Dispatchers.IO) {
        when (entryName) {
            GRADLE_DISTRIBUTION_ARCHIVE_NAME,
            ANDROID_SDK_ZIP,
            LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME,
            GRADLE_API_NAME_JAR_ZIP -> {
                val srcFile = stagingDir.resolve(entryName)
                val destDir = destinationDirForArchiveEntry(entryName).toPath()
                AssetsInstallationHelper.extractZipToDir(srcFile, destDir)
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
                val splitDb = Environment.DOWNLOAD_DIR.resolve(DOCUMENTATION_DB)
                if (splitDb.exists()) {
                    splitDb.inputStream()
                } else {
                    Files.newInputStream(stagingDir.resolve(DOCUMENTATION_DB))
                }.use { input ->
                    Environment.DOC_DB.outputStream().use { output ->
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

    suspend fun preInstall(
        context: Context,
        stagingDir: Path,
    ) = withContext(Dispatchers.IO) {
        val input = Environment.SPLIT_ASSETS_ZIP.inputStream()

        // This ZIP data stream is the stream for the top-level ZIP file
        // which contains all other ZIP files. We read this ZIP data stream using ZipInputStream and
        // extract all the entries (like android-sdk.zip, bootstrap-*.zip, etc.) to a temporary
        // directory. Once all the ZIP files have been extracted to the staging directory, we extract
        // all of them (in parallel) to their appropriate locations.
        ZipInputStream(input.buffered(bufferSize = DEFAULT_BUFFER_SIZE * 2)).useEntriesEach { zipInput, entry ->
            if (entry.isDirectory) {
                throw IllegalStateException("Directory entries are not allowed in the zip file")
            }

            when (entry.name) {
                // TODO: The name of this variable must be changed.
                //   It contains the name of the Gradle "distribution", NOT the "wrapper"!
                GRADLE_DISTRIBUTION_ARCHIVE_NAME,
                ANDROID_SDK_ZIP,
                DOCUMENTATION_DB,
                LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME,
                BOOTSTRAP_ENTRY_NAME,
                GRADLE_API_NAME_JAR_ZIP -> {
                    val destFile = stagingDir.resolve(entry.name)
                    if (destFile.exists()) {
                        throw IllegalStateException("FATAL: file already exists: $destFile")
                    }

                    logger.debug("Extracting entry '{}' to file: {}", entry.name, destFile)
                    Files.newOutputStream(destFile).use { dest ->
                        zipInput.copyTo(dest)
                    }
                }

                else -> throw IllegalStateException("Unknown entry: ${entry.name}")
            }
        }
    }
}