package com.itsaky.androidide.utils

import android.content.Context
import androidx.annotation.WorkerThread
import com.aayushatharva.brotli4j.Brotli4jLoader
import com.aayushatharva.brotli4j.decoder.BrotliInputStream
import com.itsaky.androidide.app.configuration.IDEBuildConfigProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext
import org.adfa.constants.ANDROID_SDK_ZIP
import org.adfa.constants.DOCUMENTATION_DB
import org.adfa.constants.GRADLE_WRAPPER_FILE_NAME
import org.adfa.constants.LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.pathString

object AssetsInstaller {

    private val logger = LoggerFactory.getLogger(AssetsInstaller::class.java)
    private const val BOOTSTRAP_ENTRY_NAME = "bootstrap.zip"

    @OptIn(ExperimentalPathApi::class)
    suspend fun install(
        context: Context,
    ) = withContext(Dispatchers.IO) {
        // For RELEASE builds:
        // Our assets.zip.br is a Brotli compressed zip file, so we create brotli-stream to read the
        // brotli compression. This gives us the brotli input stream, which when read, should give
        // produce a ZIP data stream -- which is then provided to ZipInputStream below.
        val input = AssetsHandler.createAssetsInputStream(context)

        val buildConfig = IDEBuildConfigProvider.getInstance()

        val expectedEntries = arrayOf(
            GRADLE_WRAPPER_FILE_NAME,
            ANDROID_SDK_ZIP,
            DOCUMENTATION_DB,
            LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME,
            BOOTSTRAP_ENTRY_NAME
        )

        val actualEntries = HashSet<String>(expectedEntries.size)

        val stagingDir = Files.createTempDirectory(UUID.randomUUID().toString())
        logger.debug("Staging directory ({}): {}", buildConfig.cpuArch, stagingDir)

        // Ensure relevant shared libraries are loaded
        Brotli4jLoader.ensureAvailability()

        // pre-install hook
        AssetsHandler.preInstall()

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
                // TODO: The name of this value must be changed.
                //   It contains the name of the Gradle "distribution", NOT the "wrapper"!
                GRADLE_WRAPPER_FILE_NAME,
                ANDROID_SDK_ZIP,
                DOCUMENTATION_DB,
                LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME,
                BOOTSTRAP_ENTRY_NAME -> {
                    val destFile = stagingDir.resolve(entry.name)
                    if (destFile.exists()) {
                        throw IllegalStateException("FATAL: file already exists: $destFile")
                    }

                    actualEntries.add(entry.name)

                    logger.debug("Extracting entry '{}' to file: {}", entry.name, destFile)
                    Files.newOutputStream(destFile).use { dest ->
                        zipInput.copyTo(dest)
                    }
                }

                else -> throw IllegalStateException("Unknown entry: ${entry.name}")
            }
        }

        if (!actualEntries.all { expectedEntries.contains(it) }) {
            throw IllegalStateException("Missing entries in assets: ${expectedEntries.toSet() - actualEntries}")
        }

        val extractors = expectedEntries.map { entry ->
            val srcFile = stagingDir.resolve(entry)
            when (entry) {
                GRADLE_WRAPPER_FILE_NAME -> async {
                    logger.info("Extracting Gradle distribution...")
                    val destDir = Files.createDirectories(Environment.GRADLE_DISTS.toPath())
                    extractZipToDir(srcFile, destDir)
                }

                ANDROID_SDK_ZIP -> async {
                    logger.info("Extracting Android SDK...")
                    val destDir = Files.createDirectories(Environment.ANDROID_HOME.toPath())
                    extractZipToDir(srcFile, destDir)
                }

                DOCUMENTATION_DB -> async {
                    logger.info("Copying documentation database...")
                    val destFile = Environment.DOC_DB
                    destFile.outputStream().use { out ->
                        Files.newInputStream(srcFile).use { it.copyTo(out) }
                    }
                }

                LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME -> async {
                    logger.info("Extracting local maven repo...")
                    val destDir = Files.createDirectories(Environment.LOCAL_MAVEN_DIR.toPath())
                    extractZipToDir(srcFile, destDir)
                }

                BOOTSTRAP_ENTRY_NAME -> async {
                    val byteChannel = Files.newByteChannel(srcFile)
                    val result = TerminalInstaller.installIfNeeded(context, byteChannel)
                    if (result !is TerminalInstaller.InstallResult.Success) {
                        throw IllegalStateException("Failed to install terminal: $result")
                    }
                }

                // this must not be reached
                else -> throw IllegalStateException("Unknown entry: $entry")
            }
        }

        // wait for all jobs to complete
        extractors.joinAll()

        // clean up
        stagingDir.deleteRecursively()

        // post-install hook -- for variant-specific installations
        AssetsHandler.postInstall()
    }

    @WorkerThread
    private fun extractZipToDir(src: Path, destDir: Path) {
        ZipInputStream(Files.newInputStream(src).buffered()).useEntriesEach { zipInput, entry ->
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