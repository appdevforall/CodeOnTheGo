package org.appdevforall.cotg.quickbuild.data

import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildMessage
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest

/**
 * Owns the per-project Quick Build scratch trees, `<root>/<projectKey>/{work,out}`.
 *
 * Pipeline intermediates live here on app-private storage rather than under
 * `<project>/.androidide/quickbuild/`, which sits on FUSE-backed `/storage/emulated` and costs
 * ~50x per file (ADFA-4930); user sources never move. A tree exists only while its session
 * does, and nothing in it needs to survive one.
 *
 * @property root parent of every per-project tree, created on demand; must be on app-private
 *   storage, since `/storage/emulated` gives up the whole point of this class.
 * @property minFreeBytes free-space floor in bytes that [freeSpaceShortfall] enforces on
 *   [root]'s volume, injectable so tests can drive the shortfall path.
 */
class QuickBuildScratch(
	private val root: File,
	private val minFreeBytes: Long = DEFAULT_MIN_FREE_BYTES,
) {
	/** Outcome of [prepare]: a usable tree, or a user-facing reason there is none. */
	sealed interface Preparation {
		/**
		 * The project has a usable scratch tree.
		 *
		 * @property dir the tree itself; its `work/` and `out/` subdirs are created by the
		 *   pipeline steps that need them, not by [prepare].
		 */
		data class Ready(
			val dir: File,
		) : Preparation

		/**
		 * There is no usable tree, and the build must not start.
		 *
		 * @property message the reason, already phrased for the user - provisioning surfaces
		 *   it verbatim rather than mapping it to another string.
		 */
		data class Failed(
			val message: QuickBuildMessage,
		) : Preparation
	}

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

	/**
	 * The project's scratch tree; parent of its `work/` and `out/` dirs.
	 *
	 * @param projectRoot the project's root directory.
	 * @return the tree's path, computed not created - only [prepare] creates it.
	 */
	fun treeFor(projectRoot: File): File = File(root, projectKey(projectRoot))

	/**
	 * The project's executor payload-staging dir.
	 *
	 * @param projectRoot the project's root directory.
	 * @return the `work/` path; the executor creates it when it first stages a payload.
	 */
	fun workDirFor(projectRoot: File): File = File(treeFor(projectRoot), "work")

	/**
	 * The project's daemon output dir.
	 *
	 * @param projectRoot the project's root directory.
	 * @return the `out/` path, passed to the daemon as its `outDir`; the daemon creates it.
	 */
	fun outDirFor(projectRoot: File): File = File(treeFor(projectRoot), "out")

	/**
	 * Checks the private volume for room, so a full volume fails in seconds rather than as
	 * ENOSPC minutes into the proxy app build. A fixed floor ([minFreeBytes], default 100 MB)
	 * rather than an estimate from project size: sizing the project means walking its sources
	 * on FUSE, and intermediates do not track source size linearly.
	 *
	 * @return null when there is room, else the user-facing message to surface; creates [root]
	 *   as a side effect, since usable space cannot be read through a directory that is not there.
	 */
	fun freeSpaceShortfall(): QuickBuildMessage? {
		root.mkdirs()
		val usable = root.usableSpace
		if (usable >= minFreeBytes) return null
		return QuickBuildMessage.NotEnoughStorage(
			requiredMb = minFreeBytes / MB,
			availableMb = usable / MB,
		)
	}

	/**
	 * Creates the project's tree (the pipeline creates its own subdirs) and re-runs
	 * the space guard. Never throws - a failure comes back as [Preparation.Failed]
	 * with the message provisioning surfaces to the user.
	 *
	 * @param projectRoot the project's root directory.
	 * @return [Preparation.Ready] with the tree, or [Preparation.Failed] on a space shortfall or
	 *   an unwritable location; an already-existing tree is reused, not cleared.
	 */
	fun prepare(projectRoot: File): Preparation {
		freeSpaceShortfall()?.let { return Preparation.Failed(it) }
		val tree = treeFor(projectRoot)
		if (!tree.isDirectory && !tree.mkdirs()) {
			return Preparation.Failed(QuickBuildMessage.ScratchDirUnavailable(tree.absolutePath))
		}
		return Preparation.Ready(tree)
	}

	/**
	 * Deletes the project's tree; a missing tree is a no-op. Session-teardown hook.
	 *
	 * Never throws - teardown has to finish. A tree that will not delete is logged at error,
	 * because the next session for that project reuses whatever is left.
	 *
	 * @param projectRoot the project whose tree to delete; its own directory, and the generation
	 *   counter inside it, are untouched.
	 */
	fun remove(projectRoot: File) {
		val tree = treeFor(projectRoot)
		// deleteRecursively() also returns false for a tree that was never there, which is a
		// documented no-op - so the residue, not the return value alone, is the failure.
		if (!tree.deleteRecursively() && tree.exists()) {
			log.error(
				"Quick Build: could not fully delete the scratch tree {}; the next session for " +
					"this project reuses what is left, so its build may start from stale intermediates",
				tree.absolutePath,
			)
		}
	}

	/**
	 * Reclaims every tree under [root]. Called only at session-manager start, when nothing is
	 * live, so it clears leftovers from dead sessions and from projects deleted since. Only
	 * directories are touched; a stray file is not a tree and is left for whoever wrote it.
	 *
	 * A running session's tree is [remove]d at its own teardown, which is why this needs no
	 * spare-list: there is nothing live for it to protect.
	 */
	fun sweep() {
		root.listFiles()?.forEach { child ->
			if (child.isDirectory && !child.deleteRecursively() && child.exists()) {
				// Not fatal: nothing live depends on a leftover, and the project it belongs
				// to gets the same reuse behaviour as any warm tree. Logged because a tree
				// that never clears is disk this class promises to reclaim.
				log.warn(
					"Quick Build: could not reclaim the leftover scratch tree {}; it stays on disk",
					child.absolutePath,
				)
			}
		}
	}

	companion object {
		private val log = LoggerFactory.getLogger("QB-Scratch")

		/** See [freeSpaceShortfall] for why a fixed floor, and why this value. */
		const val DEFAULT_MIN_FREE_BYTES: Long = 100L * 1024 * 1024

		private const val MB = 1024L * 1024
		private const val HASH_CHARS = 16
		private const val MAX_BASENAME_CHARS = 40
	}
}
