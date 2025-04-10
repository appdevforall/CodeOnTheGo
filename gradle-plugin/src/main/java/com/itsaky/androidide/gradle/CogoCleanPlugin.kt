package com.itsaky.androidide.gradle

import org.gradle.api.Plugin
import org.gradle.api.invocation.Gradle
import org.gradle.api.logging.Logging
import java.io.File

const val MAX_LOGFILE_COUNT = 2

class CogoCleanPlugin : Plugin<Gradle> {
    companion object {
        private val logger = Logging.getLogger(CogoCleanPlugin::class.java)
    }

    override fun apply(target: Gradle) {
        // logger.lifecycle("#@^*( Applyingg Clean Plugin")
        // Get the Gradle user home directory
        val gradleUserHomeDir = target.gradleUserHomeDir

        // Get the current Gradle version
        val currentGradleVersion = target.gradleVersion
        val logsDir = File(gradleUserHomeDir, "daemon/$currentGradleVersion")

        if (logsDir.exists() && logsDir.isDirectory) {
            logger.lifecycle("CoGo clean logs of gradle ($currentGradleVersion) task running....")

            // Filter and iterate over log files, sorted by last modified date
            logsDir.listFiles()?.filter { it.isFile && it.name.endsWith(".log") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(MAX_LOGFILE_COUNT)
                ?.forEach { logFile ->
                    logger.lifecycle("deleting log: ${logFile.name}")
                    logFile.delete()
                }
        }
        else {
            logger.lifecycle("No deletions made, number of log files does not exceed ($MAX_LOGFILE_COUNT) for gradle ($currentGradleVersion). ")
        }

    }
}