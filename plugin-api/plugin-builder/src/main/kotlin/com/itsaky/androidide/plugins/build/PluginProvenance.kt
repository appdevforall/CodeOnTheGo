package com.itsaky.androidide.plugins.build

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Where a resolved commit revision came from. Recorded verbatim in `cgp-build.properties`
 * so a support engineer can tell "we cannot trace this" (`NONE`) from "the author stated it"
 * ([EXPLICIT]) from "we read it off the checkout" ([GIT_COMMAND], [GIT_DIRECTORY]).
 */
enum class RevisionSource(
	val id: String,
) {
	EXPLICIT("explicit"),
	ENVIRONMENT("env"),
	GIT_COMMAND("git"),
	GIT_DIRECTORY("git-dir"),
	NONE("none"),
}

/** Whether a build timestamp is a function of the commit, or merely of the builder's clock. */
enum class TimestampSource(
	val id: String,
) {
	COMMIT("commit"),
	WALL_CLOCK("wall-clock"),
}

/** A revision plus how it was obtained. [source] is `env:GITHUB_SHA`-shaped for [RevisionSource.ENVIRONMENT]. */
data class ResolvedRevision(
	val revision: String,
	val source: String,
)

/** A `yyyyMMddHHmmss` UTC timestamp plus whether it is commit-derived. */
data class ResolvedTimestamp(
	val timestamp: String,
	val source: TimestampSource,
)

/**
 * Everything recorded about one plugin build. Resolved once per project and shared by both
 * variants, so a build does not fork `git` twice for the same answer.
 *
 * [revision] already carries the [PluginProvenance.DIRTY_SUFFIX] marker when applicable. An absent
 * marker means "not observed dirty", never "known clean": the worktree is only inspected when the
 * recorded revision is the one it is actually on. `git-dir` and `none` had no `git` to ask, and an
 * `explicit` or `env` revision may name a different commit entirely, so marking it would blame the
 * wrong source.
 */
data class PluginProvenanceRecord(
	val revision: String,
	val revisionSource: String,
	val timestamp: String,
	val timestampSource: TimestampSource,
	val libsRevision: String?,
) {
	/**
	 * [revision] as SemVer build metadata. The dirty marker becomes a dot-segment because SemVer
	 * allows only one `+` in a version string, and the version already spends it on this suffix.
	 */
	val revisionBuildMetadata: String
		get() = revision.replace(PluginProvenance.DIRTY_SUFFIX, ".dirty")
}

/**
 * Resolution of the provenance recorded in a plugin artifact: which commit it was built from,
 * how that was discovered, and whether the timestamp can be trusted to be reproducible.
 *
 * Everything here is pure JDK on purpose. `plugin-builder`'s published POM is deliberately
 * dependency-free (see its `build.gradle.kts`) so the coordinate resolves offline from the
 * harvested repository during on-device builds. JGit is therefore unavailable here even though
 * the IDE itself bundles it -- which is also why [GitDirectoryReader] exists: there is no `git`
 * binary on device, so reading `.git` by hand is the only way a plugin built inside Code on the
 * Go records a real revision.
 */
object PluginProvenance {
	/** Recorded when no step of the chain produced a revision. A definite statement, not an absent one. */
	const val UNKNOWN_REVISION = "unknown"

	/** Suffix appended when the plugin's own sources differ from the recorded commit. */
	const val DIRTY_SUFFIX = "+dirty"

	/** Env vars carrying a commit SHA, in precedence order: ours, GitHub, GitLab, Jenkins. */
	val REVISION_ENV_VARS = listOf("PLUGIN_VCS_REVISION", "GITHUB_SHA", "CI_COMMIT_SHA", "GIT_COMMIT")

	/**
	 * Env var naming the CodeOnTheGo commit the builder jar was harvested from. The builder cannot
	 * see that checkout, so the release pipeline has to pass it in; without it, a released plugin's
	 * own revision does not identify the code that built it.
	 */
	const val LIBS_REVISION_ENV_VAR = "PLUGIN_LIBS_REVISION"

	private const val ABBREVIATED_LENGTH = 12
	private const val FULL_SHA_LENGTH = 40
	private val SHA_REGEX = Regex("[0-9a-fA-F]{$FULL_SHA_LENGTH}")

	private val TIMESTAMP_FORMATTER =
		DateTimeFormatter
			.ofPattern("yyyyMMddHHmmss")
			.withZone(ZoneOffset.UTC)

	/**
	 * Runs the fallback chain: an explicitly declared revision, then the CI env vars, then `git`
	 * itself, then a hand-read of `.git`, then [UNKNOWN_REVISION].
	 *
	 * Every step is a lambda so the chain is testable without a checkout or a `git` binary. Each
	 * is called at most once and only until one succeeds.
	 */
	fun resolveRevision(
		explicitRevision: String?,
		env: (String) -> String?,
		gitCommandRevision: () -> String?,
		gitDirectoryRevision: () -> String?,
	): ResolvedRevision {
		explicitRevision?.trimmed()?.let {
			return ResolvedRevision(abbreviate(it), RevisionSource.EXPLICIT.id)
		}

		for (name in REVISION_ENV_VARS) {
			env(name)?.trimmed()?.let {
				return ResolvedRevision(abbreviate(it), "${RevisionSource.ENVIRONMENT.id}:$name")
			}
		}

		gitCommandRevision()?.trimmed()?.let {
			return ResolvedRevision(abbreviate(it), RevisionSource.GIT_COMMAND.id)
		}

		gitDirectoryRevision()?.trimmed()?.let {
			return ResolvedRevision(abbreviate(it), RevisionSource.GIT_DIRECTORY.id)
		}

		return ResolvedRevision(UNKNOWN_REVISION, RevisionSource.NONE.id)
	}

	/** [revision] with [DIRTY_SUFFIX] when the worktree is dirty. Never marks [UNKNOWN_REVISION] dirty. */
	fun label(
		revision: String,
		dirty: Boolean,
	): String =
		if (dirty && revision != UNKNOWN_REVISION) {
			revision + DIRTY_SUFFIX
		} else {
			revision
		}

	/**
	 * Prefers the committer date of the commit being built over the wall clock, so two builds of
	 * one commit produce one artifact rather than two. See
	 * docs/adr/0012-volatile-build-metadata-out-of-abis.md; the ticket's stated rationale is
	 * reproducibility, and a clock reading would undercut it. UTC, so the stamp is not a function
	 * of the builder's timezone either.
	 */
	fun resolveTimestamp(
		commitEpochSeconds: Long?,
		nowEpochSeconds: Long,
	): ResolvedTimestamp =
		if (commitEpochSeconds != null) {
			ResolvedTimestamp(format(commitEpochSeconds), TimestampSource.COMMIT)
		} else {
			ResolvedTimestamp(format(nowEpochSeconds), TimestampSource.WALL_CLOCK)
		}

	fun format(epochSeconds: Long): String = TIMESTAMP_FORMATTER.format(Instant.ofEpochSecond(epochSeconds))

	/**
	 * Shortens a full SHA to 12 characters, the length `git rev-parse --short=12` produces.
	 * Anything that is not a bare hex SHA -- a tag, a `describe` output, an author-supplied
	 * label -- is passed through untouched rather than silently truncated.
	 */
	fun abbreviate(revision: String): String =
		if (revision.length == FULL_SHA_LENGTH && SHA_REGEX.matches(revision)) {
			revision.substring(0, ABBREVIATED_LENGTH)
		} else {
			revision
		}

	private fun String.trimmed(): String? = trim().takeIf { it.isNotEmpty() }
}

/**
 * Reads a commit revision straight out of `.git`, with no `git` binary and no library.
 *
 * This is the step that makes provenance work for the commonest community path: a project cloned
 * onto the device by Code on the Go's in-process JGit and built there. `ShellUtils.which("git")`
 * has no call site in the IDE because there is no `git` executable to find.
 */
object GitDirectoryReader {
	private const val GITDIR_PREFIX = "gitdir:"
	private const val REF_PREFIX = "ref:"
	private const val FULL_SHA_LENGTH = 40
	private val SHA_REGEX = Regex("[0-9a-f]{$FULL_SHA_LENGTH}")

	/**
	 * Walks up from [startDir] to the first `.git`. Handles `.git` as a *file* containing
	 * `gitdir: <path>`, which is how git records worktrees and submodules.
	 */
	fun findGitDir(startDir: File): File? {
		var dir: File? = startDir.absoluteFile
		while (dir != null) {
			val candidate = File(dir, ".git")
			when {
				candidate.isDirectory -> return candidate
				candidate.isFile -> return readGitDirFile(candidate)
			}
			dir = dir.parentFile
		}
		return null
	}

	/** The SHA `HEAD` points at, resolving a symbolic ref through loose refs then `packed-refs`. */
	fun readHeadRevision(gitDir: File): String? {
		val head = readTrimmed(File(gitDir, "HEAD")) ?: return null
		if (SHA_REGEX.matches(head)) {
			// Detached HEAD, which is what a CI checkout normally leaves behind.
			return head
		}
		if (!head.startsWith(REF_PREFIX)) return null

		val ref = head.removePrefix(REF_PREFIX).trim().ifEmpty { return null }
		// A linked worktree keeps its own HEAD but shares refs with the main .git via commondir.
		val refRoots = listOfNotNull(gitDir, commonDir(gitDir)).distinct()
		return refRoots.firstNotNullOfOrNull { readLooseRef(it, ref) }
			?: refRoots.firstNotNullOfOrNull { readPackedRef(it, ref) }
	}

	private fun readGitDirFile(gitFile: File): File? {
		val line =
			runCatching { gitFile.readLines() }
				.getOrNull()
				?.firstOrNull { it.startsWith(GITDIR_PREFIX) }
				?: return null
		val path = line.removePrefix(GITDIR_PREFIX).trim()
		if (path.isEmpty()) return null
		return resolveAgainst(gitFile.parentFile, path)
	}

	private fun commonDir(gitDir: File): File? {
		val path = readTrimmed(File(gitDir, "commondir")) ?: return null
		return resolveAgainst(gitDir, path)
	}

	private fun resolveAgainst(
		base: File,
		path: String,
	): File? {
		val resolved = File(path).let { if (it.isAbsolute) it else File(base, path) }
		return runCatching { resolved.canonicalFile }
			.getOrNull()
			?.takeIf { it.isDirectory }
	}

	private fun readLooseRef(
		gitDir: File,
		ref: String,
	): String? = readTrimmed(File(gitDir, ref))?.takeIf { SHA_REGEX.matches(it) }

	private fun readPackedRef(
		gitDir: File,
		ref: String,
	): String? {
		val packed = File(gitDir, "packed-refs")
		val lines = runCatching { packed.readLines() }.getOrNull() ?: return null
		for (line in lines) {
			if (line.startsWith("#") || line.startsWith("^")) continue
			val sha = line.substringBefore(' ', "").trim()
			val name = line.substringAfter(' ', "").trim()
			if (name == ref && SHA_REGEX.matches(sha)) return sha
		}
		return null
	}

	private fun readTrimmed(file: File): String? =
		runCatching { file.readText() }
			.getOrNull()
			?.trim()
			?.takeIf { it.isNotEmpty() }
}
