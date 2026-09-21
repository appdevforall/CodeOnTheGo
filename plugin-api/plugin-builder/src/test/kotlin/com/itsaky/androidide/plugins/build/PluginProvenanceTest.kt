package com.itsaky.androidide.plugins.build

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Covers the revision fallback chain, the dirty marker, and the commit-vs-clock timestamp choice.
 *
 * Every step of the chain is injected, so these run without a checkout and without a `git` binary
 * -- which is also the state the on-device builder runs in.
 */
class PluginProvenanceTest {
	private val fullSha = "0c505c6c4d2f9a1b3e5d7f90123456789abcdef0"

	@Test
	fun givenAnExplicitRevision_whenResolving_thenItWinsOverEveryOtherStep() {
		val resolved =
			PluginProvenance.resolveRevision(
				explicitRevision = fullSha,
				env = { fail("env must not be consulted") },
				gitCommandRevision = { fail("git must not be invoked") },
				gitDirectoryRevision = { fail(".git must not be read") },
			)

		assertThat(resolved.revision).isEqualTo("0c505c6c4d2f")
		assertThat(resolved.source).isEqualTo("explicit")
	}

	@Test
	fun givenOnlyGithubSha_whenResolving_thenItIsUsedAndTheSourceNamesTheVariable() {
		val resolved =
			PluginProvenance.resolveRevision(
				explicitRevision = null,
				env = { name -> fullSha.takeIf { name == "GITHUB_SHA" } },
				gitCommandRevision = { fail("git must not be invoked once the env answered") },
				gitDirectoryRevision = { fail(".git must not be read") },
			)

		assertThat(resolved.revision).isEqualTo("0c505c6c4d2f")
		assertThat(resolved.source).isEqualTo("env:GITHUB_SHA")
	}

	@Test
	fun givenBothOurVariableAndGithubSha_whenResolving_thenOursWins() {
		val resolved =
			PluginProvenance.resolveRevision(
				explicitRevision = null,
				env = { name ->
					when (name) {
						"PLUGIN_VCS_REVISION" -> "abcdefabcdef"
						"GITHUB_SHA" -> fullSha
						else -> null
					}
				},
				gitCommandRevision = { null },
				gitDirectoryRevision = { null },
			)

		assertThat(resolved.revision).isEqualTo("abcdefabcdef")
		assertThat(resolved.source).isEqualTo("env:PLUGIN_VCS_REVISION")
	}

	@Test
	fun givenAForeignCiVariable_whenResolving_thenGitlabAndJenkinsAreRecognised() {
		for (name in listOf("CI_COMMIT_SHA", "GIT_COMMIT")) {
			val resolved =
				PluginProvenance.resolveRevision(
					explicitRevision = null,
					env = { candidate -> fullSha.takeIf { candidate == name } },
					gitCommandRevision = { null },
					gitDirectoryRevision = { null },
				)

			assertThat(resolved.source).isEqualTo("env:$name")
		}
	}

	@Test
	fun givenNoEnvironment_whenGitAnswers_thenTheGitOutputIsUsedVerbatim() {
		val resolved =
			PluginProvenance.resolveRevision(
				explicitRevision = null,
				env = { null },
				gitCommandRevision = { "0c505c6c4d2f\n" },
				gitDirectoryRevision = { fail(".git must not be read once git answered") },
			)

		assertThat(resolved.revision).isEqualTo("0c505c6c4d2f")
		assertThat(resolved.source).isEqualTo("git")
	}

	@Test
	fun givenNoGitBinary_whenTheGitDirectoryIsReadable_thenTheRevisionStillResolves() {
		val resolved =
			PluginProvenance.resolveRevision(
				explicitRevision = null,
				env = { null },
				gitCommandRevision = { null },
				gitDirectoryRevision = { fullSha },
			)

		assertThat(resolved.revision).isEqualTo("0c505c6c4d2f")
		assertThat(resolved.source).isEqualTo("git-dir")
	}

	@Test
	fun givenNothingResolves_whenResolving_thenUnknownIsRecordedWithAnExplicitSource() {
		val resolved =
			PluginProvenance.resolveRevision(
				explicitRevision = null,
				env = { null },
				gitCommandRevision = { null },
				gitDirectoryRevision = { null },
			)

		assertThat(resolved.revision).isEqualTo("unknown")
		assertThat(resolved.source).isEqualTo("none")
	}

	@Test
	fun givenBlankCandidates_whenResolving_thenTheyAreSkippedRatherThanRecorded() {
		val resolved =
			PluginProvenance.resolveRevision(
				explicitRevision = "   ",
				env = { "" },
				gitCommandRevision = { "\n" },
				gitDirectoryRevision = { "  \t " },
			)

		assertThat(resolved.revision).isEqualTo("unknown")
		assertThat(resolved.source).isEqualTo("none")
	}

	@Test
	fun givenANonShaRevision_whenAbbreviating_thenItIsNotTruncated() {
		val tag = "v3.0.0-42-gdeadbee"

		assertThat(PluginProvenance.abbreviate(tag)).isEqualTo(tag)
		assertThat(PluginProvenance.abbreviate("0c505c6")).isEqualTo("0c505c6")
	}

	@Test
	fun givenADirtyWorktree_whenLabelling_thenTheMarkerIsAppended() {
		assertThat(PluginProvenance.label("0c505c6c4d2f", dirty = true)).isEqualTo("0c505c6c4d2f+dirty")
		assertThat(PluginProvenance.label("0c505c6c4d2f", dirty = false)).isEqualTo("0c505c6c4d2f")
	}

	@Test
	fun givenAnUnknownRevision_whenLabelling_thenItIsNeverMarkedDirty() {
		assertThat(PluginProvenance.label("unknown", dirty = true)).isEqualTo("unknown")
	}

	@Test
	fun givenACommitDate_whenResolvingTheTimestamp_thenItIsUsedAndFlaggedAsCommitDerived() {
		// 2026-09-01T16:15:00Z
		val resolved = PluginProvenance.resolveTimestamp(commitEpochSeconds = 1_788_279_300L, nowEpochSeconds = 0L)

		assertThat(resolved.timestamp).isEqualTo("20260901161500")
		assertThat(resolved.source).isEqualTo(TimestampSource.COMMIT)
	}

	@Test
	fun givenNoCommitDate_whenResolvingTheTimestamp_thenTheClockIsUsedAndFlaggedAsSuch() {
		val resolved = PluginProvenance.resolveTimestamp(commitEpochSeconds = null, nowEpochSeconds = 1_788_279_300L)

		assertThat(resolved.timestamp).isEqualTo("20260901161500")
		assertThat(resolved.source).isEqualTo(TimestampSource.WALL_CLOCK)
	}

	@Test
	fun givenAFixedEpoch_whenFormatting_thenTheStampIsUtcRatherThanLocal() {
		// 1970-01-01T00:00:00Z. A local-time formatter would render an offset hour here.
		assertThat(PluginProvenance.format(0L)).isEqualTo("19700101000000")
	}

	@Test
	fun givenADirtyRevision_whenUsedAsVersionMetadata_thenOnlyOnePlusSignRemains() {
		val record =
			PluginProvenanceRecord(
				revision = "0c505c6c4d2f+dirty",
				revisionSource = "git",
				timestamp = "20260901161500",
				timestampSource = TimestampSource.COMMIT,
				libsRevision = null,
			)

		assertThat(record.revisionBuildMetadata).isEqualTo("0c505c6c4d2f.dirty")
	}

	private fun fail(message: String): Nothing = throw AssertionError(message)
}
