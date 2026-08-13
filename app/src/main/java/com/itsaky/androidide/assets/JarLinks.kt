package com.itsaky.androidide.assets

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files

/**
 * Symlinking one staged jar at another, for the two places that deduplicate jars across the
 * on-device tool stores: [com.itsaky.androidide.quickbuild.QuickBuildArtifactStager], which links
 * jars the APK deliberately does not carry, and [DistJarDeduplicator], which collapses copies that
 * were extracted twice.
 *
 * The two want different things when a link is impossible - the stager must have the bytes, so it
 * copies; the deduplicator already has them, so it leaves the copy alone - which is why what is
 * shared here is the attempt, not the fallback.
 */
internal object JarLinks {
	private val log = LoggerFactory.getLogger("QB-JarLinks")

	/**
	 * Try to make [target] a symlink to [source].
	 *
	 * @return false when the filesystem refuses links, leaving [target] untouched. Callers decide
	 *   what that costs them.
	 */
	fun trySymlink(
		target: File,
		source: File,
	): Boolean =
		try {
			Files.createSymbolicLink(target.toPath(), source.toPath())
			true
		} catch (e: Exception) {
			// UnsupportedOperationException / IOException / SecurityException all mean the same
			// thing here: no symlink on this filesystem.
			log.warn("Symlink {} -> {} failed ({})", target, source, e.toString())
			false
		}
}
