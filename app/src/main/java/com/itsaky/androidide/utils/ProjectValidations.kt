package com.itsaky.androidide.utils

import java.io.File
import kotlin.collections.filter
import kotlin.collections.orEmpty

/** Checks if the file is a readable, visible directory. */
internal fun File.isProjectCandidateDir(): Boolean = isDirectory && canRead() && !name.startsWith(".") && !isHidden

/** Scans the given root directory for valid Android project subdirectories. */
internal fun findValidProjects(projectsRoot: File): List<File> {
	if (!projectsRoot.isProjectCandidateDir()) return emptyList()

	val subdirs = projectsRoot.listFiles()
		?.filter { it.isProjectCandidateDir() }
		.orEmpty()
	if (subdirs.isEmpty()) return emptyList()

	return subdirs.filter { dir -> isValidProjectDirectory(dir) }
}

/** Determines if the directory contains a valid Android project structure. */
fun isValidProjectDirectory(selectedDir: File): Boolean {
	if (isPluginProject(selectedDir)) {
		return true
	}

	val appFolder = File(selectedDir, "app")
	val buildGradleFile = File(appFolder, "build.gradle")
	val buildGradleKtsFile = File(appFolder, "build.gradle.kts")
	return appFolder.exists() && appFolder.isDirectory &&
		(buildGradleFile.exists() || buildGradleKtsFile.exists())
}

/**
 * Determines if the selected directory is either:
 *  1. A valid Android project itself, OR
 *  2. A container that includes one or more valid Android projects.
 */
internal fun isValidProjectOrContainerDirectory(selectedDir: File): Boolean {
	if (!selectedDir.isProjectCandidateDir()) {
		return false
	}

	if (isValidProjectDirectory(selectedDir)) {
		return true
	}

	// Check if it contains valid Android projects as subdirectories
	val subDirs = selectedDir.listFiles()?.filter { it.isProjectCandidateDir() } ?: return false
	return subDirs.any { sub -> isValidProjectDirectory(sub) }
}

/** Checks if the directory contains a specific plugin project structure. */
internal fun isPluginProject(dir: File): Boolean {
	val pluginApiJar = File(dir, "libs/plugin-api.jar")
	val buildGradle = File(dir, "build.gradle.kts")
	return pluginApiJar.exists() && buildGradle.exists()
}