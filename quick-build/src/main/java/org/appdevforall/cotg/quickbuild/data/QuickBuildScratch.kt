package org.appdevforall.cotg.quickbuild.data

import java.io.File
import java.security.MessageDigest

/**
 * Per-project Quick Build scratch trees on app-private storage (ADFA-4930).
 *
 * The pipeline's intermediates (daemon compile/dex/relink outputs, the executor's
 * payload staging) used to live under `<project>/.androidide/quickbuild/` - on
 * `/storage/emulated`, i.e. FUSE, which pays a ~50x per-file toll versus the app's
 * own ext4-backed private storage. This class owns the relocated layout:
 *
 * `<root>/<projectKey>/` - one tree per project
 * - `work/` - executor payload staging (assets zip)
 * - `out/` - daemon output (classes, dex, relinked resources)
 *
 * [root] is a Context-derived private directory (the app wires
 * `noBackupFilesDir/quickbuild-scratch`); user sources never move - only QB-owned
 * intermediates live here.
 *
 * Lifecycle: a tree exists only while its session does. [remove] deletes it on
 * session teardown, and [sweep] reclaims leftovers from dead sessions (process
 * kills, crashes) - including trees of since-deleted projects, which nothing else
 * would ever clean up now that the tree no longer dies with the project folder.
 * Deleting on teardown is safe: no cross-session content here is load-bearing -
 * every daemon start re-seeds its incremental state from current disk, and the
 * cross-session generation counter deliberately stays OUT of this tree (see
 * [FileGenerationStore]).
 *
 * Pure JVM on purpose - unit tests point [root] at a temp dir.
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
	 * Stable directory key for a project: `<sanitized-basename>-<sha256-prefix>`.
	 *
	 * The basename is only for human debuggability (sanitized to filename-safe ASCII);
	 * uniqueness comes from the 64-bit hash of the normalized absolute path, so two
	 * distinct projects that share a basename (e.g. `a/MyApp` and `b/MyApp`) can never
	 * collide, and the same project always maps to the same tree across sessions.
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

	fun treeFor(projectRoot: File): File = File(root, projectKey(projectRoot))

	/** Executor payload-staging dir (was `<project>/.androidide/quickbuild`). */
	fun workDirFor(projectRoot: File): File = File(treeFor(projectRoot), "work")

	/** Daemon output dir (was `<project>/.androidide/quickbuild/out`). */
	fun outDirFor(projectRoot: File): File = File(treeFor(projectRoot), "out")

	/**
	 * Disk-space guard: null when the private volume has room, else the user-facing
	 * failure message. Called before provisioning so a full volume fails in seconds
	 * with a clear message instead of ENOSPC minutes into the setup build or, worse,
	 * mid-quick-build.
	 *
	 * Heuristic: a fixed floor ([minFreeBytes], default 100 MB) rather than an
	 * estimate from project size. Sizing the project would walk its source tree on
	 * FUSE - the per-file cost this whole class exists to avoid - and intermediates
	 * do not track source size linearly anyway. 100 MB covers the tens-of-MB trees
	 * typical projects produce with headroom, while staying small enough not to
	 * spuriously block the ~1.5 GB-storage device tier this feature targets.
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
	 * Reclaims every tree that has no live session: deletes all directories under
	 * [root] except those keyed by [liveProjectRoots]. Run at session-manager start,
	 * when nothing is live yet, this clears leftovers from dead sessions and from
	 * projects deleted since. Only directories are touched - [root] is wholly
	 * QB-owned, but a stray file is not a tree and is left for whoever wrote it.
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
