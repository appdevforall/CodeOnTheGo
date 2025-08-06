package com.itsaky.androidide.assets

import android.content.Context
import androidx.annotation.WorkerThread
import com.itsaky.androidide.app.configuration.CpuArch
import com.itsaky.androidide.utils.Environment
import com.itsaky.androidide.utils.TerminalInstaller
import com.itsaky.androidide.utils.useEntriesEach
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
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.system.measureTimeMillis

data object SplitAssetsInstaller : BaseAssetsInstaller() {

    private val logger = LoggerFactory.getLogger(SplitAssetsInstaller::class.java)
    private lateinit var zipFile: ZipFile

    override suspend fun preInstall(
        context: Context,
        stagingDir: Path,
    ): Unit = withContext(Dispatchers.IO) {
        zipFile = ZipFile(Environment.SPLIT_ASSETS_ZIP)
    }

    @WorkerThread
    override suspend fun doInstall(
        context: Context,
        stagingDir: Path,
        cpuArch: CpuArch,
        entryName: String,
    ): Unit = withContext(Dispatchers.IO) {
        val entry = zipFile.getEntry(entryName)
        val time = measureTimeMillis {
            zipFile.getInputStream(entry).use { zipInput ->
                when (entry.name) {
                    GRADLE_DISTRIBUTION_ARCHIVE_NAME,
                    ANDROID_SDK_ZIP,
                    LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME,
                    GRADLE_API_NAME_JAR_ZIP -> {
                        val destDir = destinationDirForArchiveEntry(entry.name).toPath()
                        logger.debug("Extracting '{}' to dir: {}", entry.name, destDir)
                        ZipInputStream(zipInput).useEntriesEach { innerZipInput, innerEntry ->
                            val innerFile = destDir.resolve(innerEntry.name)
                            if (innerEntry.isDirectory) {
                                Files.createDirectories(innerFile)
                            } else {
                                if (innerFile.parent != null) {
                                    Files.createDirectories(innerFile.parent)
                                }
                                Files.newOutputStream(innerFile).use { out ->
                                    innerZipInput.copyTo(out)
                                }
                            }
                        }
                        logger.debug("Completed extracting '{}' to dir: {}", entry.name, destDir)
                    }

                    AssetsInstallationHelper.BOOTSTRAP_ENTRY_NAME -> {
                        logger.debug("Extracting 'bootstrap.zip' to dir: {}", stagingDir)

                        // We need a SeekableByteChannel for the TerminalInstaller, but the ZipInputStream is not seekable.
                        // The only way is to write it to a temporary file first.
                        val tempBootstrap = Files.createTempFile(stagingDir, "bootstrap", ".zip")
                        try {
                            Files.newOutputStream(tempBootstrap).use { out ->
                                zipInput.copyTo(out)
                            }
                            val channel = Files.newByteChannel(tempBootstrap)
                            val result = TerminalInstaller.installIfNeeded(context, channel)
                            if (result !is TerminalInstaller.InstallResult.Success) {
                                // Log the error and continue with other assets.
                                logger.error("Failed to install terminal: $result")
                            }
                        } finally {
                            Files.deleteIfExists(tempBootstrap)
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

                    else -> logger.warn("Unknown entry in assets zip: {}", entry.name)
                }
            }
        }
        logger.info("Extraction of '{}' completed in {}ms", entry.name, time)
    }

    override suspend fun postInstall(
        context: Context,
        stagingDir: Path
    ) {
        withContext(Dispatchers.IO) {
            zipFile.close()
        }
        super.postInstall(context, stagingDir)
    }

    private fun destinationDirForArchiveEntry(entryName: String): File = when (entryName) {
        GRADLE_DISTRIBUTION_ARCHIVE_NAME -> Environment.GRADLE_DISTS
        ANDROID_SDK_ZIP -> Environment.ANDROID_HOME
        LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME -> Environment.LOCAL_MAVEN_DIR
        GRADLE_API_NAME_JAR_ZIP -> Environment.GRADLE_GEN_JARS
        else -> throw IllegalStateException("Entry '$entryName' is not expected to be an archive")
    }
}
