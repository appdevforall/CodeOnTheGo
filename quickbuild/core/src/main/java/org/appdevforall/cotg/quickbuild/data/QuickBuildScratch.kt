package org.appdevforall.cotg.quickbuild.data

import java.io.File
import java.security.MessageDigest

/**
 * Owns the per-project Quick Build scratch trees on app-private storage (ADFA-4930).
 *
 * Pipeline intermediates (daemon compile/dex/relink output, executor payload staging) live
 * here rather than under `<project>/.androidide/quickbuild/`, which sits on FUSE-backed
 * `/storage/emulated` and costs ~50x per file versus the app's own ext4 storage. [root] is a
 * Context-derived private directory (`noBackupFilesDir/quickbuild-scratch`); user sources
 * never move.
 *
 * `<root>/<projectKey>/` - one tree per project
 * - `work/` - executor payload staging (assets zip)
 * - `out/` - daemon output (classes, dex, relinked resources)
 *
 * A tree exists only while its session does: [remove] deletes it on teardown, [sweep]
 * reclaims leftovers from dead sessions, including trees of since-deleted projects that
 * nothing else would clean up. Nothing here needs to survive a session - the daemon re-seeds
 * its incremental state from disk on every start, and the generation counter deliberately
 * lives outside this tree (see [FileGenerationStore]).
 */
class QuickBuildScratch(
	private val root: File,
	private val minFreeBytes: Long = DEFAULT_MIN_FREE_BYTES,
) {
	/** Outcome of [prepare]: a usable tree, or a user-facing reason there is none. */
	sealed interface Preparation {
		data class Ready(
			val dir: File,
		) : Preparation

		data class Failed(
			val message: String,
		) : Preparation
	}

	/**
	 * Derives a project's stable directory key: `<sanitized-basename>-<sha256-prefix>`.
	 *
	 * The basename is only for human debuggability; uniqueness comes from the hash of the
	 * normalized absolute path, so `a/MyApp` and `b/MyApp` cannot collide and a project maps
	 * to the same tree across sessions.
	 */
	fun projectKey(projectRoot: File): String {
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

	/** The project's scratch tree; parent of its `work/` and `out/` dirs. */
	fun treeFor(projectRoot: File): File = File(root, projectKey(projectRoot))

	/** The project's executor payload-staging dir. */
	fun workDirFor(projectRoot: File): File = File(treeFor(projectRoot), "work")

	/** The project's daemon output dir. */
	fun outDirFor(projectRoot: File): File = File(treeFor(projectRoot), "out")

	/**
	 * Checks the private volume for room, returning null when there is enough and the
	 * user-facing failure message when there is not. Called before provisioning so a full
	 * volume fails in seconds rather than as ENOSPC minutes into the proxy app build.
	 *
	 * A fixed floor ([minFreeBytes], default 100 MB) rather than an estimate from project
	 * size: sizing the project means walking its sources on FUSE, and intermediates do not
	 * track source size linearly. 100 MB covers the tens-of-MB trees typical projects
	 * produce without blocking the ~1.5 GB-storage devices this feature targets.
	 */
	fun freeSpaceShortfall(): String? {
		root.mkdirs()
		val usable = root.usableSpace
		if (usable >= minFreeBytes) return null
		return "Quick Build needs about ${minFreeBytes / MB} MB free in app storage " +
			"but only ${usable / MB} MB is available. Free up device storage and try again."
	}

	/**
	 * Creates the project's tree (the pipeline creates its own subdirs) and re-runs
	 * the space guard. Never throws - a failure comes back as [Preparation.Failed]
	 * with the message provisioning surfaces to the user.
	 */
	fun prepare(projectRoot: File): Preparation {
		freeSpaceShortfall()?.let { return Preparation.Failed(it) }
		val tree = treeFor(projectRoot)
		if (!tree.isDirectory && !tree.mkdirs()) {
			return Preparation.Failed(
				"Quick Build could not create its build directory at ${tree.absolutePath}",
			)
		}
		return Preparation.Ready(tree)
	}

	/** Deletes the project's tree; a missing tree is a no-op. Session-teardown hook. */
	fun remove(projectRoot: File) {
		treeFor(projectRoot).deleteRecursively()
	}

	/**
	 * Reclaims every tree that has no live session: deletes all directories under [root]
	 * except those keyed by [liveProjectRoots]. Run at session-manager start, when nothing is
	 * live, it clears leftovers from dead sessions and from projects deleted since. Only
	 * directories are touched; a stray file is not a tree and is left for whoever wrote it.
	 */
	fun sweep(liveProjectRoots: Collection<File>) {
		val liveKeys = liveProjectRoots.map(::projectKey).toSet()
		root.listFiles()?.forEach { child ->
			if (child.isDirectory && child.name !in liveKeys) {
				child.deleteRecursively()
			}
		}
	}

	companion object {
		/** See [freeSpaceShortfall] for why a fixed floor, and why this value. */
		const val DEFAULT_MIN_FREE_BYTES: Long = 100L * 1024 * 1024

		private const val MB = 1024L * 1024
		private const val HASH_CHARS = 16
		private const val MAX_BASENAME_CHARS = 40
	}
}
