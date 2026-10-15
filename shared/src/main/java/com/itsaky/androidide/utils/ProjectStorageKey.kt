package com.itsaky.androidide.utils

import java.io.File
import java.security.MessageDigest

/**
 * The directory key for a project's per-project trees in app-private storage: Quick Build's
 * scratch (`QuickBuildScratch`) and relocated Gradle build output (ADFA-4930). Both use this one
 * function so a project maps to the same key in each.
 */
object ProjectStorageKey {
	// gradle-plugin's BuildOutputRedirect keys build directories with its own copy of this
	// derivation, because its jar stays off the IDE's modules; change the two together.
	private const val HASH_CHARS = 16
	private const val MAX_BASENAME_CHARS = 40

	/**
	 * Derives a project's stable directory key: `<sanitized-basename>-<sha256-prefix>`.
	 *
	 * The basename is only for human debuggability; uniqueness comes from the hash of the
	 * normalized absolute path, so `a/MyApp` and `b/MyApp` cannot collide and a project maps
	 * to the same tree across sessions.
	 *
	 * @param projectRoot the project's root directory; only its path is read, so a moved or
	 *   renamed project keys to a different tree by design.
	 * @return a filesystem-safe single path segment - every character outside
	 *   `[A-Za-z0-9._-]` is replaced, and the basename is truncated before the hash is joined.
	 */
	fun of(projectRoot: File): String {
		val normalized = projectRoot.absoluteFile.normalize().path
		val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
		val hash = digest.joinToString("") { "%02x".format(it) }.take(HASH_CHARS)
		val base =
			projectRoot.name
				.map { if (it.isLetterOrDigit() || it == '.' || it == '_' || it == '-') it else '_' }
				.joinToString("")
				.take(MAX_BASENAME_CHARS)
				.ifEmpty { "project" }
		return "$base-$hash"
	}
}
