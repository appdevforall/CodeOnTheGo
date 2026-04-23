package com.itsaky.androidide.plugins.manager.services

import com.itsaky.androidide.plugins.PluginPermission
import com.itsaky.androidide.plugins.services.ArchiveFormat
import com.itsaky.androidide.plugins.services.ExtractResult
import com.itsaky.androidide.plugins.services.IdeArchiveService
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.slf4j.LoggerFactory
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.measureTimeMillis

class IdeArchiveServiceImpl(
    private val pluginId: String,
    private val permissions: Set<PluginPermission>,
    private val pathValidator: IdeFileServiceImpl.PathValidator? = null
) : IdeArchiveService {

    override fun extract(
        source: InputStream,
        format: ArchiveFormat,
        destination: File,
        onProgress: ((bytesProcessed: Long, currentEntry: String?) -> Unit)?
    ): ExtractResult {
        return try {
            ensureWritePermission()
            ensurePathAllowed(destination)

            val buffered = BufferedInputStream(NonClosingInputStream(source))
            when (format) {
                ArchiveFormat.TAR_XZ -> {
                    val tempFile = File.createTempFile("archive", ".tar.xz", destination.parentFile)
                    try {
                        tempFile.outputStream().use { source.copyTo(it) }
                        val ok = extractTarXzViaTermux(tempFile, destination)
                        if (ok) ExtractResult.Success(0, 0)
                        else ExtractResult.Failure(Exception("Termux tar extraction failed"))
                    } finally {
                        tempFile.delete()
                    }
                }
                ArchiveFormat.XZ -> extractSingleStream(
                    XZCompressorInputStream(buffered),
                    destination,
                    onProgress
                )
                ArchiveFormat.GZIP -> extractSingleStream(
                    GzipCompressorInputStream(buffered),
                    destination,
                    onProgress
                )
                ArchiveFormat.TAR -> extractTar(buffered, destination, onProgress)
                ArchiveFormat.TAR_GZ -> extractTar(
                    GzipCompressorInputStream(buffered),
                    destination,
                    onProgress
                )
                ArchiveFormat.ZIP -> extractZip(buffered, destination, onProgress)
            }
        } catch (e: Exception) {
            logger.error("error in extract ${e}")
            ExtractResult.Failure(e)
        }
    }

    private fun extractSingleStream(
        input: InputStream,
        destination: File,
        onProgress: ((Long, String?) -> Unit)?
    ): ExtractResult {
        destination.parentFile?.mkdirs()
        if (destination.isDirectory) {
            return ExtractResult.Failure(
                IllegalArgumentException("destination must be a file for single-stream formats: ${destination.absolutePath}")
            )
        }
        val written = input.use { stream ->
            FileOutputStream(destination).use { out ->
                copyInterruptible(stream, out, onProgress, entryName = destination.name)
            }
        }
        return ExtractResult.Success(bytesWritten = written, filesExtracted = 1)
    }

    private fun extractTar(
        input: InputStream,
        destination: File,
        onProgress: ((Long, String?) -> Unit)?
    ): ExtractResult {
        ensureDirectory(destination)
        val destRoot = destination.canonicalFile
        var totalBytes = 0L
        var fileCount = 0

        TarArchiveInputStream(input).use { tar ->
            while (true) {
                if (Thread.interrupted()) throw InterruptedException("extract interrupted")
                val entry = tar.nextEntry ?: break
                if (!tar.canReadEntryData(entry)) {
                    throw SecurityException("unreadable tar entry: ${entry.name}")
                }
                if (entry.isSymbolicLink || entry.isLink) {
                    throw SecurityException("tar entry is a link, refusing: ${entry.name}")
                }
                val target = resolveEntryTarget(destRoot, entry)
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out ->
                        totalBytes += copyInterruptible(tar, out, onProgress, entryName = entry.name, alreadyWritten = totalBytes)
                    }
                    fileCount += 1
                    applyTarMode(entry, target)
                }
            }
        }

        return ExtractResult.Success(bytesWritten = totalBytes, filesExtracted = fileCount)
    }

    private fun extractZip(
        input: InputStream,
        destination: File,
        onProgress: ((Long, String?) -> Unit)?
    ): ExtractResult {
        ensureDirectory(destination)
        val destRoot = destination.canonicalFile
        var totalBytes = 0L
        var fileCount = 0

        ZipArchiveInputStream(input).use { zip ->
            while (true) {
                if (Thread.interrupted()) throw InterruptedException("extract interrupted")
                val entry = zip.nextEntry ?: break
                if (!zip.canReadEntryData(entry)) {
                    throw SecurityException("unreadable zip entry: ${entry.name}")
                }
                val target = resolveEntryTarget(destRoot, entry)
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out ->
                        totalBytes += copyInterruptible(zip, out, onProgress, entryName = entry.name, alreadyWritten = totalBytes)
                    }
                    fileCount += 1
                }
            }
        }

        return ExtractResult.Success(bytesWritten = totalBytes, filesExtracted = fileCount)
    }

    private fun resolveEntryTarget(destRoot: File, entry: ArchiveEntry): File {
        val target = File(destRoot, entry.name).canonicalFile
        val rootPath = destRoot.path
        if (target.path != rootPath && !target.path.startsWith(rootPath + File.separator)) {
            throw SecurityException("archive entry escapes destination: ${entry.name}")
        }
        return target
    }

    private fun applyTarMode(entry: TarArchiveEntry, target: File) {
        val mode = entry.mode
        if (mode and OWNER_EXECUTE_BIT != 0) {
            target.setExecutable(true, false)
        }
    }

    private fun ensureDirectory(destination: File) {
        if (destination.exists() && !destination.isDirectory) {
            throw IllegalArgumentException("destination exists and is not a directory: ${destination.absolutePath}")
        }
        destination.mkdirs()
    }

    private fun copyInterruptible(
        input: InputStream,
        output: OutputStream,
        onProgress: ((Long, String?) -> Unit)?,
        entryName: String?,
        alreadyWritten: Long = 0L
    ): Long {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        var written = 0L
        var sinceReport = 0L
        while (true) {
            if (Thread.interrupted()) throw InterruptedException("extract interrupted")
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
            written += read
            sinceReport += read
            if (onProgress != null && sinceReport >= PROGRESS_REPORT_INTERVAL) {
                onProgress(alreadyWritten + written, entryName)
                sinceReport = 0L
            }
        }
        output.flush()
        onProgress?.invoke(alreadyWritten + written, entryName)
        return written
    }

    private fun ensureWritePermission() {
        if (writePermissions.none { it in permissions }) {
            throw SecurityException(
                "Plugin $pluginId does not have required permissions: ${writePermissions.joinToString(", ") { it.name }}"
            )
        }
    }

    private fun ensurePathAllowed(path: File) {
        val validator = pathValidator
        val allowed = if (validator != null) {
            validator.isPathAllowed(path)
        } else {
            PluginPathAllowlist.isAllowed(path, permissions, pluginId)
        }

        if (!allowed) {
            throw SecurityException("Plugin $pluginId does not have access to path: ${path.absolutePath}")
        }
    }

    private class NonClosingInputStream(delegate: InputStream) : FilterInputStream(delegate) {
        override fun close() {
            // no-op: IdeArchiveService contract states the caller owns the source stream
        }
    }

    private fun extractTarXzViaTermux(archiveFile: File, outputDir: File): Boolean {
        if (!archiveFile.exists()) {
            logger.debug("Archive not found: ${archiveFile.absolutePath}")
            return false
        }

        logger.debug("Starting Termux tar extraction: ${archiveFile.absolutePath}")

        return runCatching {
            val output = StringBuilder()
            var exitCode = -1

            val elapsed = measureTimeMillis {
                val process = ProcessBuilder(
                    "$TERMUX_BIN_PATH/tar", "-xJf", archiveFile.absolutePath,
                    "-C", outputDir.canonicalPath, "--no-same-owner"
                ).redirectErrorStream(true).apply {
                    environment()["PATH"] = TERMUX_BIN_PATH
                }.start()

                val reader = thread(name = "tar-xz-extract-output") {
                    process.inputStream.bufferedReader().useLines { it.forEach(output::appendLine) }
                }

                val completed = process.waitFor(2, TimeUnit.MINUTES)
                if (!completed) process.destroyForcibly()
                reader.join()
                exitCode = if (completed) process.exitValue() else -1
            }

            when (exitCode) {
                0 -> {
                    logger.debug("Extraction succeeded in ${elapsed}ms: $output")
                    true
                }
                else -> {
                    logger.error("Extraction failed (code=$exitCode): $output")
                    false
                }
            }
        }.getOrElse { e ->
            logger.error("Termux process error: ${e.message}")
            false
        }
    }


    private companion object {
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val PROGRESS_REPORT_INTERVAL = 1L * 1024 * 1024
        const val OWNER_EXECUTE_BIT = 0b001_000_000
        const val TERMUX_BIN_PATH = "/data/data/com.itsaky.androidide/files/usr/bin"
        val writePermissions = setOf(
            PluginPermission.FILESYSTEM_WRITE,
            PluginPermission.IDE_ENVIRONMENT_WRITE
        )
        private val logger = LoggerFactory.getLogger(IdeArchiveServiceImpl::class.java)
    }
}