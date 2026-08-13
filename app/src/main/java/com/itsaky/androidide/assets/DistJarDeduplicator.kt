package com.itsaky.androidide.assets

import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Replaces jars in the extracted offline Maven repo with symlinks to the identical jar in the
 * extracted Gradle distribution, once both are on disk.
 *
 * The two stores are harvested independently and overlap by construction: a user project resolves
 * its Kotlin plugin from the Maven repo, and the distribution embeds the same Kotlin to run its own
 * builds. Post-toolchain-bump the largest single overlap is `kotlin-compiler-embeddable`, around
 * 60 MB of identical bytes on a device that has little to spare.
 *
 * **Identity is decided by content, never by name.** The two stores version independently, so a
 * matching file name means nothing on its own - and linking a same-named jar that is not the same
 * jar would corrupt a user's build in a way that surfaces far from here. Size is only a cheap
 * pre-filter; the decision is SHA-256.
 *
 * This is a disk-space optimisation, not a correctness requirement, so it never fails an install.
 * A missing distribution, an unreadable jar, or a filesystem that refuses symlinks all mean the
 * duplicate simply stays on disk.
 */
object DistJarDeduplicator {
	private val log = LoggerFactory.getLogger("QB-DistDedup")

	/** Result of one pass, for logging and for the tests to assert on. */
	data class Outcome(
		val linked: Int,
		val bytesReclaimed: Long,
	)

	/**
	 * @param mavenRepo the extracted `localMvnRepository` tree, walked recursively.
	 * @param distLib the extracted distribution's `lib/`, whose jars are the link targets.
	 */
	fun deduplicate(
		mavenRepo: File,
		distLib: File,
	): Outcome {
		if (!mavenRepo.isDirectory || !distLib.isDirectory) {
			log.info("Nothing to deduplicate: mavenRepo={} distLib={}", mavenRepo, distLib)
			return Outcome(0, 0)
		}

		// Size buckets first: hashing 200 MB of Maven repo against 150 MB of distribution would
		// cost more than the disk it saves, and a size mismatch already rules a pair out.
		val distBySize = mutableMapOf<Long, MutableList<File>>()
		distLib.listFiles()?.forEach { candidate ->
			if (candidate.isFile && candidate.name.endsWith(".jar")) {
				distBySize.getOrPut(candidate.length()) { mutableListOf() }.add(candidate)
			}
		}
		if (distBySize.isEmpty()) {
			log.info("No jars in {}", distLib)
			return Outcome(0, 0)
		}

		val hashes = mutableMapOf<File, String?>()
		var linked = 0
		var reclaimed = 0L

		mavenRepo.walkTopDown().forEach { candidate ->
			if (!candidate.isFile || !candidate.name.endsWith(".jar")) return@forEach
			// Already linked by an earlier pass; installs re-extract and re-run, so this is the
			// common case on every install after the first.
			if (Files.isSymbolicLink(candidate.toPath())) return@forEach

			val sameSize = distBySize[candidate.length()] ?: return@forEach
			val candidateHash = hashes.getOrPut(candidate) { sha256(candidate) } ?: return@forEach
			val twin =
				sameSize.firstOrNull { hashes.getOrPut(it) { sha256(it) } == candidateHash }
					?: return@forEach

			if (replaceWithLink(candidate, twin)) {
				linked++
				reclaimed += twin.length()
			}
		}

		log.info("Linked {} duplicate jars to {}, reclaiming {} bytes", linked, distLib, reclaimed)
		return Outcome(linked, reclaimed)
	}

	/**
	 * Swap [duplicate] for a symlink to [source] without ever having neither on disk: the link is
	 * built beside the file and moved over it, so a failure at any point leaves the original copy
	 * exactly where it was.
	 */
	private fun replaceWithLink(
		duplicate: File,
		source: File,
	): Boolean {
		val staging = File(duplicate.parentFile, "${duplicate.name}.dedup")
		staging.delete()
		if (!JarLinks.trySymlink(staging, source)) {
			return false
		}
		return try {
			Files.move(
				staging.toPath(),
				duplicate.toPath(),
				StandardCopyOption.REPLACE_EXISTING,
				StandardCopyOption.ATOMIC_MOVE,
			)
			true
		} catch (e: IOException) {
			log.warn("Could not swap {} for a link to {} ({})", duplicate, source, e.toString())
			staging.delete()
			false
		}
	}

	private fun sha256(file: File): String? =
		try {
			val digest = MessageDigest.getInstance("SHA-256")
			file.inputStream().use { input ->
				val buffer = ByteArray(1 shl 16)
				while (true) {
					val read = input.read(buffer)
					if (read < 0) break
					digest.update(buffer, 0, read)
				}
			}
			digest.digest().joinToString("") { "%02x".format(it) }
		} catch (e: IOException) {
			log.warn("Could not read {} ({})", file, e.toString())
			null
		}
}
