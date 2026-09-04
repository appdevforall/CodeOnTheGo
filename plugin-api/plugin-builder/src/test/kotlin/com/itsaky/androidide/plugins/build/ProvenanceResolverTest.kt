package com.itsaky.androidide.plugins.build

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Covers the decisions the resolver makes on top of the pure chain: when `+dirty` is attributable,
 * when the commit's own date is reachable, and which git invocations are worth making at all.
 */
class ProvenanceResolverTest {
	private val headSha = "0c505c6c4d2f"
	private val fullSha = "0c505c6c4d2f9a1b3e5d7f90123456789abcdef0"
	private val otherFullSha = "ee6d454f7e1600112233445566778899aabbccdd"
	private val commitEpoch = 1_756_745_700L
	private val clockEpoch = 1_900_000_000L

	@Test
	fun givenACleanCheckout_whenResolving_thenTheCommitAndItsOwnDateAreRecorded(
		@TempDir dir: File,
	) {
		val git = FakeGit(revParse = headSha, status = null, showEpoch = commitEpoch)

		val record = resolver(git, dir).resolve(explicitRevision = null)

		assertThat(record.revision).isEqualTo(headSha)
		assertThat(record.revisionSource).isEqualTo(RevisionSource.GIT_COMMAND.id)
		assertThat(record.timestampSource).isEqualTo(TimestampSource.COMMIT)
		assertThat(record.timestamp).isEqualTo(PluginProvenance.format(commitEpoch))
	}

	@Test
	fun givenUncommittedChangesInTheModule_whenResolving_thenTheRevisionIsMarkedDirty(
		@TempDir dir: File,
	) {
		val git = FakeGit(revParse = headSha, status = " M src/main/AndroidManifest.xml", showEpoch = commitEpoch)

		val record = resolver(git, dir).resolve(explicitRevision = null)

		assertThat(record.revision).isEqualTo(headSha + PluginProvenance.DIRTY_SUFFIX)
	}

	@Test
	fun givenTheDirtyCheck_whenItRuns_thenItIsScopedToThePluginsOwnDirectory(
		@TempDir dir: File,
	) {
		// An unscoped status reports the release pipeline's refreshed libs/*.jar as dirt and
		// would stamp +dirty on every officially released plugin.
		val git = FakeGit(revParse = headSha, status = null, showEpoch = commitEpoch)

		resolver(git, dir).resolve(explicitRevision = null)

		assertThat(git.invocations).contains("status --porcelain -- .")
	}

	@Test
	fun givenAnEnvShaMatchingHead_whenTheTreeIsDirty_thenTheMarkerStillApplies(
		@TempDir dir: File,
	) {
		val git = FakeGit(revParse = headSha, status = " M build.gradle.kts", showEpoch = commitEpoch)

		val record = resolver(git, dir, env = mapOf("GITHUB_SHA" to fullSha)).resolve(explicitRevision = null)

		assertThat(record.revisionSource).isEqualTo("env:GITHUB_SHA")
		assertThat(record.revision).isEqualTo(headSha + PluginProvenance.DIRTY_SUFFIX)
	}

	@Test
	fun givenAnEnvShaThatIsNotHead_whenTheTreeIsDirty_thenTheStatedCommitIsNotBlamed(
		@TempDir dir: File,
	) {
		// +dirty is a claim about the worktree. Attaching it to a commit the worktree is not on
		// would point a support engineer at the wrong source.
		val git = FakeGit(revParse = headSha, status = " M build.gradle.kts", showEpoch = commitEpoch)

		val record = resolver(git, dir, env = mapOf("GITHUB_SHA" to otherFullSha)).resolve(explicitRevision = null)

		assertThat(record.revision).isEqualTo("ee6d454f7e16")
		assertThat(record.revision).doesNotContain(PluginProvenance.DIRTY_SUFFIX)
		assertThat(git.invocations).doesNotContain("status --porcelain -- .")
	}

	@Test
	fun givenAnExplicitRevision_whenResolving_thenItIsRecordedVerbatimAndNeverMarkedDirty(
		@TempDir dir: File,
	) {
		val git = FakeGit(revParse = headSha, status = " M build.gradle.kts", showEpoch = commitEpoch)

		val record = resolver(git, dir).resolve(explicitRevision = "v2.1.0")

		assertThat(record.revision).isEqualTo("v2.1.0")
		assertThat(record.revisionSource).isEqualTo(RevisionSource.EXPLICIT.id)
		assertThat(git.invocations).doesNotContain("status --porcelain -- .")
	}

	@Test
	fun givenNoGitBinary_whenTheCheckoutIsReadable_thenTheRevisionComesFromDiskAndTheClock(
		@TempDir dir: File,
	) {
		// The on-device path: Code on the Go ships JGit in-process, so nothing can fork `git`
		// and the commit's date is out of reach even though its sha is not.
		File(dir, ".git").apply { mkdirs() }.let { File(it, "HEAD").writeText("$fullSha\n") }
		val git = FakeGit(revParse = null, status = null, showEpoch = null)

		val record = resolver(git, dir).resolve(explicitRevision = null)

		assertThat(record.revision).isEqualTo(headSha)
		assertThat(record.revisionSource).isEqualTo(RevisionSource.GIT_DIRECTORY.id)
		assertThat(record.timestampSource).isEqualTo(TimestampSource.WALL_CLOCK)
		assertThat(record.timestamp).isEqualTo(PluginProvenance.format(clockEpoch))
	}

	@Test
	fun givenASourceArchive_whenResolving_thenTheRecordSaysUnknownAndNoCommitDateIsSought(
		@TempDir dir: File,
	) {
		val git = FakeGit(revParse = null, status = null, showEpoch = null)

		val record = resolver(git, dir).resolve(explicitRevision = null)

		assertThat(record.revision).isEqualTo(PluginProvenance.UNKNOWN_REVISION)
		assertThat(record.revisionSource).isEqualTo(RevisionSource.NONE.id)
		assertThat(git.invocations.none { it.startsWith("show ") }).isTrue()
	}

	@Test
	fun givenTheLibsRevisionVariable_whenResolving_thenThePairingIsRecorded(
		@TempDir dir: File,
	) {
		val git = FakeGit(revParse = headSha, status = null, showEpoch = commitEpoch)
		val env = mapOf(PluginProvenance.LIBS_REVISION_ENV_VAR to "  ee6d454f7e16  ")

		assertThat(resolver(git, dir, env).resolve(explicitRevision = null).libsRevision).isEqualTo("ee6d454f7e16")
	}

	@Test
	fun givenNoLibsRevisionVariable_whenResolving_thenTheFieldStaysAbsentRatherThanBlank(
		@TempDir dir: File,
	) {
		val git = FakeGit(revParse = headSha, status = null, showEpoch = commitEpoch)

		assertThat(resolver(git, dir, env = mapOf(PluginProvenance.LIBS_REVISION_ENV_VAR to "   ")).resolve(null).libsRevision)
			.isNull()
	}

	@Test
	fun givenTwoVariants_whenEachResolves_thenGitIsAskedForHeadOnlyOncePerResolution(
		@TempDir dir: File,
	) {
		val git = FakeGit(revParse = headSha, status = null, showEpoch = commitEpoch)

		resolver(git, dir).resolve(explicitRevision = null)

		assertThat(git.invocations.count { it.startsWith("rev-parse ") }).isEqualTo(1)
	}

	private fun resolver(
		git: GitClient,
		dir: File,
		env: Map<String, String> = emptyMap(),
	) = ProvenanceResolver(
		git = git,
		env = env::get,
		projectDir = dir,
		clockEpochSeconds = { clockEpoch },
	)

	/** Answers the three invocations the resolver can make, and records what it was asked. */
	private class FakeGit(
		private val revParse: String?,
		private val status: String?,
		private val showEpoch: Long?,
	) : GitClient {
		val invocations = mutableListOf<String>()

		override fun run(vararg args: String): String? {
			invocations += args.joinToString(" ")
			return when (args.firstOrNull()) {
				"rev-parse" -> revParse
				"status" -> status
				"show" -> showEpoch?.toString()
				else -> null
			}
		}
	}
}
