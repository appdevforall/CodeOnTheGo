package com.itsaky.androidide.gradle

import java.io.File
import java.security.MessageDigest

/**
 * Where a project's build directory goes when the IDE relocates build output off the project
 * folder (ADFA-4930): `<root>/<build>/<project>`.
 *
 * `<build>` is `main` for the build the IDE runs. A nested build - `buildSrc` or an included
 * build - has its own projects with the same paths (`:`, `:app`), so it gets a directory of its
 * own, keyed by its root directory.
 */
internal object BuildOutputRedirect {
	private const val MAIN_BUILD = "main"
	private const val ROOT_PROJECT = "_root"
	private const val HASH_CHARS = 12

	/**
	 * @param root the relocated output root the IDE passed in.
	 * @param isNestedBuild whether the project belongs to `buildSrc` or an included build.
	 * @param buildRootDir the root directory of the project's build.
	 * @param projectPath the project's Gradle path, `:` for the root project.
	 * @return the project's build directory.
	 */
	fun buildDirFor(
		root: File,
		isNestedBuild: Boolean,
		buildRootDir: File,
		projectPath: String,
	): File {
		val build = if (isNestedBuild) nestedBuildKey(buildRootDir) else MAIN_BUILD
		val project = if (projectPath == ":") ROOT_PROJECT else projectPath.removePrefix(":").replace(':', '/')
		return File(File(root, build), project)
	}

	private fun nestedBuildKey(buildRootDir: File): String {
		val path = buildRootDir.absoluteFile.normalize().path
		val hash =
			MessageDigest
				.getInstance("SHA-256")
				.digest(path.toByteArray(Charsets.UTF_8))
				.joinToString("") { "%02x".format(it) }
				.take(HASH_CHARS)
		val name = buildRootDir.name.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("")
		return "${name.ifEmpty { "build" }}-$hash"
	}
}
