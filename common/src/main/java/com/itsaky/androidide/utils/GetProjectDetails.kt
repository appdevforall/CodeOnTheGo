package com.itsaky.androidide.utils

import android.content.Context
import android.text.format.Formatter.formatFileSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class ProjectDetails(
    val sizeFormatted: String,
    val numberOfFiles: Int,
    val gradleVersion: String,
    val kotlinVersion: String,
    val javaVersion: String
)

suspend fun loadProjectDetails(projectPath: String, context: Context): ProjectDetails =
    withContext(Dispatchers.IO) {
        val root = File(projectPath)
        val appDir = root.toPath().resolve("app").toFile()
        var sizeBytes = 0L
        var fileCount = 0

        val ignoredDirs = arrayOf("build", ".gradle", ".git", ".idea")

        root.walkTopDown()
            .onEnter { !ignoredDirs.contains(it.name) }
            .forEach { file ->
            if (file.isFile) {
                fileCount++
                sizeBytes += file.length()
            }
        }
        val sizeFormatted = formatFileSize(context, sizeBytes)

        ProjectDetails(
            sizeFormatted = sizeFormatted,
            numberOfFiles = fileCount,
            gradleVersion = readGradleVersion(root),
            kotlinVersion = readKotlinVersion(appDir),
            javaVersion = readJavaVersion(appDir)
        )
    }