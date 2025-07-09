/*
 * Copyright (C) 2025 Akash Yadav
 *
 * Scribe is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Scribe is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Scribe.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.utils

import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.itsaky.androidide.resources.R
import com.termux.app.TermuxInstaller
import com.termux.shared.android.PackageUtils
import com.termux.shared.errors.Error
import com.termux.shared.file.FileUtils
import com.termux.shared.logger.Logger
import com.termux.shared.markdown.MarkdownUtils
import com.termux.shared.shell.command.ExecutionCommand
import com.termux.shared.shell.command.ExecutionCommand.Runner
import com.termux.shared.shell.command.runner.app.AppShell
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH
import com.termux.shared.termux.file.TermuxFileUtils
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader


/**
 * Install the Termux bootstrap packages if necessary by following the below steps:
 * 1. If $PREFIX already exist, assume that it is correct and be done. Note that this relies on that we do not create a
 *    broken $PREFIX directory below.
 * 2. A progress dialog is shown with "Installing..." message and a spinner.
 * 3. A staging directory, $STAGING_PREFIX, is cleared if left over from broken installation below.
 * 4. The zip file is loaded from a shared library.
 * 5. The zip, containing entries relative to the $PREFIX, is is downloaded and extracted by a zip input stream
 *    continuously encountering zip file entries:
 *    - If the zip entry encountered is SYMLINKS.txt, go through it and remember all symlinks to setup.
 *    - For every other zip entry, extract it into $STAGING_PREFIX and set execute permissions if necessary.
 *
 * @author Akash Yadav
 * @see <a href="https://gitlab.com/scribe-oss/core/Scribe/-/blob/main/core/resources/src/main/res/values/terminal.xml">ScribeTerminalInstaller.kt</a>
 */
object TerminalInstaller {
    private val logger = LoggerFactory.getLogger(TerminalInstaller::class.java)

    private val executableDirs = arrayOf(
        "bin/",
        "libexec",
        "lib/apt/apt-helper",
        "lib/apt/methods",
    )

    private val executableFiles = arrayOf(
        "etc/termux/termux-bootstrap/second-stage/termux-bootstrap-second-stage.sh",
    )

    /**
     * The result of the install operation.
     */
    sealed interface InstallResult {
        /**
         * The install was successful.
         */
        data object Success : InstallResult

        /**
         * The installation was not performed, possibility due to a 'dry run' flag.
         */
        data object NotInstalled : InstallResult

        /**
         * The install was not successful.
         */
        sealed interface Error : InstallResult {
            /**
             * The install was not successful because the user is not the primary user.
             */
            data object IsSecondaryUser : Error

            /**
             * The generic error which is shown to the user.
             */
            data class Interactive(
                val title: String,
                val message: String,
            ) : InstallResult
        }
    }

    sealed interface ProgressType {
        data object Preparing : ProgressType

        data class Unzipping(
            val entry: String,
        ) : ProgressType

        data class Linking(
            val source: String,
            val destination: String,
        ) : ProgressType
    }

    /**
     * Install the terminal bootstrap packages if necessary.
     *
     * @param context The application context.
     * @return The installation result.
     */
    suspend fun installIfNeeded(
        context: Context,
        dryRun: Boolean = false,
        onProgress: (ProgressType) -> Unit = {},
    ): InstallResult = withContext(Dispatchers.IO) {
        onProgress(ProgressType.Preparing)

        val filesDirAccessibleErr = TermuxFileUtils.isTermuxFilesDirectoryAccessible(
            // context =
            context,
            // createDirectoryIfMissing =
            true,
            // setMissingPermissions =
            true,
        )

        val isFilesDirAccessible = filesDirAccessibleErr == null

        if (!PackageUtils.isCurrentUserThePrimaryUser(context)) {
            val errorMessage = context.getString(
                R.string.bootstrap_error_not_primary_user_message,
                MarkdownUtils.getMarkdownCodeForString(
                    TermuxConstants.TERMUX_PREFIX_DIR_PATH,
                    false,
                ),
            )

            logger.error("isFilesDirAccessible: $isFilesDirAccessible")
            logger.error(errorMessage)
            return@withContext InstallResult.Error.IsSecondaryUser
        }

        if (!isFilesDirAccessible) {
            var errorMessage = Error.getMinimalErrorString(filesDirAccessibleErr)

            // noinspection SdCardPath
            if (PackageUtils.isAppInstalledOnExternalStorage(context) && TermuxConstants.TERMUX_FILES_DIR_PATH != context.filesDir.absolutePath.replace(
                    "^/data/user/0".toRegex(),
                    "/data/data/",
                )
            ) {
                errorMessage += "\n\n" + context.getString(
                    R.string.bootstrap_error_installed_on_portable_sd,
                    MarkdownUtils.getMarkdownCodeForString(
                        TermuxConstants.TERMUX_PREFIX_DIR_PATH,
                        false,
                    ),
                )
            }

            logger.error(errorMessage)
            return@withContext InstallResult.Error.Interactive(
                title = context.getString(R.string.bootstrap_error_title),
                message = errorMessage,
            )
        }

        if (FileUtils.directoryFileExists(TermuxConstants.TERMUX_PREFIX_DIR_PATH, true)) {
            if (TermuxFileUtils.isTermuxPrefixDirectoryEmpty()) {
                logger.info(
                    "The termux prefix directory {} exists but is empty or only contains specific unimportant files.",
                    TermuxConstants.TERMUX_PREFIX_DIR_PATH,
                )
            } /*else {
                    return@withContext InstallResult.Success
                }*/
        } else if (FileUtils.fileExists(TermuxConstants.TERMUX_PREFIX_DIR_PATH, false)) {
            logger.info(
                "The termux prefix directory {} does not exist but another file exists at its destination.",
                TermuxConstants.TERMUX_PREFIX_DIR_PATH,
            )
        }

        if (dryRun) {
            // halt actual installation
            return@withContext InstallResult.NotInstalled
        }

        logger.info("Installing {} bootstrap packages.", TermuxConstants.TERMUX_APP_NAME)

        var error = FileUtils.createDirectoryFile(TermuxConstants.TERMUX_HOME_DIR_PATH)
        if (error != null) {
            return@withContext InstallResult.Error.Interactive(
                title = context.getString(R.string.bootstrap_error_title),
                message = Error.getErrorMarkdownString(error),
            )
        }

        error = FileUtils.deleteFile(
            "termux prefix staging directory",
            TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH,
            true,
        )
        if (error != null) {
            return@withContext InstallResult.Error.Interactive(
                title = context.getString(R.string.bootstrap_error_title),
                message = Error.getErrorMarkdownString(error),
            )
        }

        error = FileUtils.deleteFile(
            "termux prefix directory",
            TermuxConstants.TERMUX_PREFIX_DIR_PATH,
            true,
        )
        if (error != null) {
            return@withContext InstallResult.Error.Interactive(
                title = context.getString(R.string.bootstrap_error_title),
                message = Error.getErrorMarkdownString(error),
            )
        }

        error = TermuxFileUtils.isTermuxPrefixStagingDirectoryAccessible(true, true)
        if (error != null) {
            return@withContext InstallResult.Error.Interactive(
                title = context.getString(R.string.bootstrap_error_title),
                message = Error.getErrorMarkdownString(error),
            )
        }

        error = TermuxFileUtils.isTermuxPrefixDirectoryAccessible(true, true)
        if (error != null) {
            return@withContext InstallResult.Error.Interactive(
                title = context.getString(R.string.bootstrap_error_title),
                message = Error.getErrorMarkdownString(error),
            )
        }

        logger.info(
            "Extracting bootstrap zip to prefix staging directory {}.",
            TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH,
        )

        return@withContext doInstall(context, onProgress)
    }

    private fun doInstall(
        context: Context, onProgress: (ProgressType) -> Unit
    ): InstallResult {
        var error: Error?
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val symlinks = mutableListOf<Pair<String, String>>()

        ZipFile.builder()
            .setSeekableByteChannel(SeekableInMemoryByteChannel(TermuxInstaller.loadZipBytes()))
            .get().use { zipFile ->
                zipFile.entries.asSequence().forEach { entry ->
                    val entryStream = zipFile.getInputStream(entry)
                    if (entry.name == "SYMLINKS.txt") {
                        val symlinksReader = BufferedReader(InputStreamReader(entryStream))
                        var line: String?
                        while ((symlinksReader.readLine().also { line = it }) != null) {
                            val parts = line!!.split("←".toRegex())
                                .dropLastWhile { segment -> segment.isEmpty() }.toTypedArray()

                            if (parts.size != 2) {
                                throw RuntimeException("Malformed symlink line: $line")
                            }

                            val oldPath = parts[0]
                            val newPath = buildString {
                                append(TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH)
                                append("/")
                                append(parts[1])
                            }

                            symlinks.add(oldPath to newPath)

                            error = ensureDirectoryExists(File(newPath).parentFile!!)
                            if (error != null) {
                                return@use InstallResult.Error.Interactive(
                                    title = context.getString(R.string.bootstrap_error_title),
                                    message = Error.getErrorMarkdownString(error),
                                )
                            }
                        }
                    } else {
                        val zipEntryName = entry.name
                        onProgress(ProgressType.Unzipping(zipEntryName))

                        val targetFile =
                            File(TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH, zipEntryName)
                        val isDirectory = entry.isDirectory

                        error =
                            ensureDirectoryExists(if (isDirectory) targetFile else targetFile.parentFile)
                        if (error != null) {
                            return InstallResult.Error.Interactive(
                                title = context.getString(R.string.bootstrap_error_title),
                                message = Error.getErrorMarkdownString(error),
                            )
                        }

                        if (!isDirectory) {
                            FileOutputStream(targetFile).use { outStream ->
                                var readBytes: Int
                                while ((entryStream.read(buffer)
                                        .also { len -> readBytes = len }) != -1
                                ) {
                                    outStream.write(buffer, 0, readBytes)
                                }
                            }

                            if (executableDirs.any { dir -> zipEntryName.startsWith(dir) }
                                || executableFiles.any { file -> file == zipEntryName }) {
                                Os.chmod(targetFile.absolutePath, OsConstants.S_IRWXU)
                            } else {
                                Os.chmod(targetFile.absolutePath, entry.unixMode)
                            }
                        }
                    }
                }
            }

        if (symlinks.isEmpty()) {
            throw java.lang.RuntimeException("No SYMLINKS.txt encountered")
        }

        for ((first, second) in symlinks) {
            onProgress(ProgressType.Linking(first, second))
            Os.symlink(first, second)
        }

        logger.info("Moving termux prefix staging to prefix directory.")

        if (!TermuxConstants.TERMUX_STAGING_PREFIX_DIR.renameTo(TermuxConstants.TERMUX_PREFIX_DIR)) {
            throw RuntimeException("Moving termux prefix staging to prefix directory failed")
        }

        // Run Termux bootstrap second stage.
        val termuxBootstrapSecondStageFile =
            "$TERMUX_PREFIX_DIR_PATH/etc/termux/termux-bootstrap/second-stage/termux-bootstrap-second-stage.sh"
        if (!FileUtils.fileExists(termuxBootstrapSecondStageFile, false)) {
            logger.info(
                "Not running Termux bootstrap second stage since script not found at \"{}\" path.",
                termuxBootstrapSecondStageFile
            )
        } else {
            if (!FileUtils.fileExists(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash", true)) {
                logger.info(
                    "Not running Termux bootstrap second stage since bash not found."
                )
            }

            logger.info("Running Termux bootstrap second stage.")

            val executionCommand = ExecutionCommand(
                -1,
                termuxBootstrapSecondStageFile,
                null,
                null,
                null,
                Runner.APP_SHELL.runnerName,
                false
            )

            executionCommand.commandLabel = "Termux Bootstrap Second Stage Command"
            executionCommand.backgroundCustomLogLevel = Logger.LOG_LEVEL_NORMAL

            val shell = AppShell.execute(
                context, executionCommand, null, TermuxShellEnvironment(), hashMapOf(), true
            )

            if (shell == null || !executionCommand.isSuccessful || executionCommand.resultData.exitCode != 0) {

                // Delete prefix directory as otherwise when app is restarted, the broken prefix directory would be used and logged into.
                error =
                    FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true)
                logger.error("Failed to run Termux second-stage command: {}", executionCommand)
                logger.error("Failed to run Termux second-stage command: {}", error)
                return InstallResult.Error.Interactive(
                    title = context.getString(R.string.bootstrap_error_title),
                    message = Error.getErrorMarkdownString(error),
                )
            }
        }


        logger.info("Bootstrap packages installed successfully.")

        // Recreate env file since termux prefix was wiped earlier
        TermuxShellEnvironment.writeEnvironmentToFile(context)

        return InstallResult.Success
    }

    private fun ensureDirectoryExists(directory: File): Error? =
        FileUtils.createDirectoryFile(directory.absolutePath)
}
