package com.itsaky.androidide.plugins.manager.services

import java.io.File
import java.nio.file.Paths

/**
 * Resolves the working directory a plugin asked to run a command in: null means [projectRoot], and
 * a relative path is taken against it.
 *
 * @throws SecurityException if the directory lies outside [projectRoot], or one is given with no
 *   project open.
 */
internal fun resolvePluginWorkingDirectory(
	pluginId: String,
	projectRoot: File?,
	workingDirectory: String?,
): File? {
	if (workingDirectory != null && projectRoot == null) {
		throw SecurityException("Plugin $pluginId asked for working directory $workingDirectory with no project open")
	}
	val dir =
		when {
			workingDirectory == null -> projectRoot
			Paths.get(workingDirectory).isAbsolute -> File(workingDirectory)
			else -> File(projectRoot, workingDirectory).canonicalFile
		}
	if (dir == null || projectRoot == null) return dir

	val normalizedDir = dir.canonicalFile.toPath()
	val normalizedRoot = projectRoot.canonicalFile.toPath()
	if (normalizedDir != normalizedRoot && !normalizedDir.startsWith(normalizedRoot)) {
		throw SecurityException(
			"Plugin $pluginId attempted to execute in directory outside project root: $normalizedDir",
		)
	}
	return dir
}
