package com.itsaky.androidide.plugins.build

import java.io.File

/**
 * Runs one `git` invocation and hands back its trimmed stdout, or `null` when git is absent, the
 * command failed, or it printed nothing. Empty output collapses to `null`, so a non-null result
 * means "git answered".
 *
 * The seam that keeps [ProvenanceResolver] free of Gradle: the policy below is the part that can
 * be wrong, and it is testable against a fake without a checkout, a `git` binary, or a build.
 */
fun interface GitClient {
	fun run(vararg args: String): String?
}

/**
 * Turns a project's surroundings into the [PluginProvenanceRecord] packaged into its `.cgp`: runs
 * the revision fallback chain, decides whether `+dirty` is attributable, and prefers the commit's
 * own date over the clock.
 */
class ProvenanceResolver(
	private val git: GitClient,
	private val env: (String) -> String?,
	private val projectDir: File,
	private val clockEpochSeconds: () -> Long,
) {
	fun resolve(explicitRevision: String?): PluginProvenanceRecord {
		// Lazy, not eager: on device there is no git binary to fork, and the chain may answer
		// from the environment before it ever needs to ask.
		val headRevision by lazy { git.run("rev-parse", "--short=12", "HEAD") }

		val resolved =
			PluginProvenance.resolveRevision(
				explicitRevision = explicitRevision,
				env = env,
				gitCommandRevision = { headRevision },
				gitDirectoryRevision = {
					GitDirectoryReader.findGitDir(projectDir)?.let(GitDirectoryReader::readHeadRevision)
				},
			)

		// `+dirty` is a claim about the worktree, so it is only attributable when the revision
		// recorded is the one the worktree is actually on -- an explicit or env-supplied revision
		// may name a different commit entirely, and marking that one dirty would blame the wrong
		// commit. The status is scoped to the plugin's own directory: an unscoped one reports the
		// release pipeline's refreshed libs/*.jar as dirt and would stamp `+dirty` on every
		// officially released plugin, draining the flag of any signal.
		val recordsHead = headRevision != null && resolved.revision == headRevision
		val dirty = recordsHead && git.run("status", "--porcelain", "--", ".") != null

		val timestamp =
			PluginProvenance.resolveTimestamp(
				commitEpochSeconds = commitEpochSeconds(resolved.revision, headRevision),
				nowEpochSeconds = clockEpochSeconds(),
			)

		return PluginProvenanceRecord(
			revision = PluginProvenance.label(resolved.revision, dirty),
			revisionSource = resolved.source,
			timestamp = timestamp.timestamp,
			timestampSource = timestamp.source,
			libsRevision = env(PluginProvenance.LIBS_REVISION_ENV_VAR)?.trim()?.takeIf { it.isNotEmpty() },
		)
	}

	/** Committer date of [revision], or null when there is no git to ask or nothing worth asking about. */
	private fun commitEpochSeconds(
		revision: String,
		headRevision: String?,
	): Long? {
		if (headRevision == null || revision == PluginProvenance.UNKNOWN_REVISION) return null
		return git.run("show", "-s", "--format=%ct", revision)?.toLongOrNull()
	}
}
