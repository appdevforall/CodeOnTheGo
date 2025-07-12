package com.itsaky.androidide.utils

import androidx.annotation.WorkerThread
import com.aayushatharva.brotli4j.Brotli4jLoader
import com.aayushatharva.brotli4j.decoder.BrotliInputStream
import com.itsaky.androidide.app.configuration.CpuArch
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
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.pathString

object AssetsInstaller {

    private val logger = LoggerFactory.getLogger(AssetsInstaller::class.java)

    suspend fun install(
        input: InputStream
    ) = withContext(Dispatchers.IO) {
        val buildConfig = IDEBuildConfigProvider.getInstance()
        val bootstrapArch = when (val cpuArch = buildConfig.cpuArch) {
            CpuArch.AARCH64 -> "aarch64"
            CpuArch.ARM -> "arm"
            else -> throw IllegalStateException("Unsupported CPU architecture: $cpuArch")
        }
        val bootstrapEntryName = "bootstrap-${bootstrapArch}.zip"

        val expectedEntries = arrayOf(
            GRADLE_WRAPPER_FILE_NAME,
            ANDROID_SDK_ZIP,
            DOCUMENTATION_DB,
            LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME,
            "bootstrap-${bootstrapArch}.zip"
        )

        val actualEntries = HashSet<String>(expectedEntries.size)

        val stagingDir = Files.createTempDirectory(UUID.randomUUID().toString())
        logger.debug("Staging directory ({}): {}", bootstrapArch, stagingDir)

        // Ensure relevant shared libraries are loaded
        Brotli4jLoader.ensureAvailability()

        // Our assets.zip.br is a Brotli compressed zip file, so we create brotli-stream to read the
        // brotli compression. This gives us the brotli input stream, which when read, should give
        // produce a ZIP data stream. This ZIP data stream is the stream for the top-level ZIP file
        // which contains all other ZIP files. We read this ZIP data stream using ZipInputStream and
        // extract all the entries (like android-sdk.zip, bootstrap-*.zip, etc.) to a temporary
        // directory. Once all the ZIP files have been extracted to the staging directory, we extract
        // all of them (in parallel) to their appropriate locations
        BrotliInputStream(input.buffered(bufferSize = DEFAULT_BUFFER_SIZE * 2)).use { brotliInput ->
            ZipInputStream(brotliInput).useEntriesEach { zipInput, entry ->
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
                    bootstrapEntryName -> {
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
        }

        if (!expectedEntries.contentEquals(actualEntries.toTypedArray())) {
            throw IllegalStateException("Missing entries in assets: ${expectedEntries.toSet() - actualEntries}")
        }

        val extractors = expectedEntries.map { entry ->
            val srcFile = stagingDir.resolve(entry)
            when (entry) {
                GRADLE_WRAPPER_FILE_NAME -> async {
                    val destDir = Files.createDirectories(Environment.GRADLE_DISTS.toPath())
                    extractZipToDir(srcFile, destDir)
                }

                ANDROID_SDK_ZIP -> async {
                    val destDir = Files.createDirectories(Environment.ANDROID_HOME.toPath())
                    extractZipToDir(srcFile, destDir)
                }

                DOCUMENTATION_DB -> async {
                }

                LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME -> async {

                }

                bootstrapEntryName -> async {

                }

                // this must not be reached
                else -> throw IllegalStateException("Unknown entry: $entry")
            }
        }

        // wait for all jobs to complete
        extractors.joinAll()

        // clean up
        stagingDir.deleteIfExists()
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