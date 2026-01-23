package com.itsaky.androidide.assets

import android.content.Context
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path
import com.termux.shared.termux.TermuxConstants
import com.itsaky.androidide.utils.Environment
import kotlin.system.measureTimeMillis


abstract class BaseAssetsInstaller : AssetsInstaller {
    private val logger = LoggerFactory.getLogger(BaseAssetsInstaller::class.java)

    override suspend fun postInstall(
        context: Context,
        stagingDir: Path
    ) {
        Environment.AAPT2.setExecutable(true)

        installNdk(
            File(Environment.ANDROID_HOME, Environment.NDK_TAR_XZ),
            Environment.ANDROID_HOME
        )
    }

    private fun installNdk(archiveFile: File, outputDir: File): Boolean {
        if (!archiveFile.exists()) {
            logger.debug("NDK installable package not found: ${archiveFile.absolutePath}")
            return false
        }

        logger.debug("Starting installation of ${archiveFile.absolutePath}")

        var exitCode: Int
        var result: String
        val elapsed = measureTimeMillis {
            val processBuilder = ProcessBuilder(
                "${TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH}/bash",
                "-c",
                "tar -xJf ${archiveFile.absolutePath} -C ${outputDir.absolutePath} --no-same-owner"
            )
                .redirectErrorStream(true)

            val env = processBuilder.environment()
            env["PATH"] = "${TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH}:${env["PATH"]}"

            val process = processBuilder.start()

            result = process.inputStream.bufferedReader().use { it.readText() }
            exitCode = process.waitFor()
        }

        return if (exitCode == 0) {
            logger.debug("Extraction of ${archiveFile.absolutePath} successful took ${elapsed}ms : $result")

            if (archiveFile.exists()) {
                val deleted = archiveFile.delete()
                if (deleted) {
                    logger.debug("${archiveFile.absolutePath} deleted successfully.")
                } else {
                    logger.debug("Failed to delete ${archiveFile.absolutePath}.")
                }
                deleted
            } else {
                logger.debug("Archive file not found for deletion.")
                false
            }
        } else {
            logger.error("Extraction failed with code $exitCode: $result")
            false
        }
    }
}
