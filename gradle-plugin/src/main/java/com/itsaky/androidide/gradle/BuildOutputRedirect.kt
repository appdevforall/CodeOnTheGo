package com.itsaky.androidide.gradle

import java.io.File
import java.security.MessageDigest

/**
 * Where a project's build directory goes when the IDE relocates build output off the project
 * folder (ADFA-4930): `<root>/<build>/<project>`, one flat directory per project.
 *
 * `<build>` is `main` for the build the IDE runs. A nested build - `buildSrc` or an included
 * build - has its own projects with the same paths (`:`, `:app`), so it gets a directory of its
 * own, keyed by its root directory.
 *
 * `<project>` is the project's name plus a hash of its full Gradle path. Mapping `:core:ui` to
 * `core/ui` instead would put it inside `:core`'s build directory, where `:core:clean` deletes it
 * and a subproject called `generated` or `intermediates` lands in its parent's task outputs. The
 * name never keeps `:`, which is the classpath separator.
 */
internal object BuildOutputRedirect {
	private const val MAIN_BUILD = "main"
	private const val ROOT_PROJECT = "root"

	// The same lengths as the app's ProjectStorageKey, which keys the tree this lives in. The
	// plugin keeps its own copy because its jar is injected into every user build and stays off
	// the IDE's modules.
	private const val HASH_CHARS = 16
	private const val MAX_NAME_CHARS = 40

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
		val build =
			if (isNestedBuild) {
				key(buildRootDir.name, buildRootDir.absoluteFile.normalize().path)
			} else {
				MAIN_BUILD
			}
		val name = if (projectPath == ":") ROOT_PROJECT else projectPath.substringAfterLast(':')
		return File(File(root, build), key(name, projectPath))
	}

	/** `<sanitized name>-<sha256 prefix of identity>`: readable, and unique per identity. */
	private fun key(
		name: String,
		identity: String,
	): String {
		val hash =
			MessageDigest
				.getInstance("SHA-256")
				.digest(identity.toByteArray(Charsets.UTF_8))
				.joinToString("") { "%02x".format(it) }
				.take(HASH_CHARS)
		val safeName =
			name
				.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }
				.joinToString("")
				.take(MAX_NAME_CHARS)
				.ifEmpty { "build" }
		return "$safeName-$hash"
	}
}
