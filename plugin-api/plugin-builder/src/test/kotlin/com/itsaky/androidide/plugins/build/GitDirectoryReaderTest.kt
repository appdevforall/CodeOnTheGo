package com.itsaky.androidide.plugins.build

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Covers reading a revision out of `.git` with no `git` binary -- the only path that works on
 * device, where Code on the Go clones through in-process JGit and ships no `git` executable.
 */
class GitDirectoryReaderTest {
	private val sha = "0c505c6c4d2f9a1b3e5d7f90123456789abcdef0"
	private val otherSha = "ee6d454f7e1600112233445566778899aabbccdd"

	@Test
	fun givenADetachedHead_whenReading_thenTheShaIsReturnedDirectly(
		@TempDir root: File,
	) {
		val gitDir = gitDir(root, head = sha)

		assertThat(GitDirectoryReader.readHeadRevision(gitDir)).isEqualTo(sha)
	}

	@Test
	fun givenASymbolicHead_whenTheRefIsLoose_thenItIsFollowed(
		@TempDir root: File,
	) {
		val gitDir = gitDir(root, head = "ref: refs/heads/stage")
		writeFile(gitDir, "refs/heads/stage", "$sha\n")

		assertThat(GitDirectoryReader.readHeadRevision(gitDir)).isEqualTo(sha)
	}

	@Test
	fun givenASymbolicHead_whenTheRefIsOnlyPacked_thenPackedRefsAreScanned(
		@TempDir root: File,
	) {
		val gitDir = gitDir(root, head = "ref: refs/heads/stage")
		writeFile(
			gitDir,
			"packed-refs",
			"""
			# pack-refs with: peeled fully-peeled sorted
			$otherSha refs/heads/main
			$sha refs/heads/stage
			$otherSha refs/tags/v1.0.0
			^$otherSha
			""".trimIndent() + "\n",
		)

		assertThat(GitDirectoryReader.readHeadRevision(gitDir)).isEqualTo(sha)
	}

	@Test
	fun givenBothALooseAndAPackedRef_whenReading_thenTheLooseRefWins(
		@TempDir root: File,
	) {
		val gitDir = gitDir(root, head = "ref: refs/heads/stage")
		writeFile(gitDir, "refs/heads/stage", sha)
		writeFile(gitDir, "packed-refs", "$otherSha refs/heads/stage\n")

		assertThat(GitDirectoryReader.readHeadRevision(gitDir)).isEqualTo(sha)
	}

	@Test
	fun givenADanglingSymbolicHead_whenReading_thenNullIsReturnedRatherThanTheRefName(
		@TempDir root: File,
	) {
		val gitDir = gitDir(root, head = "ref: refs/heads/never-committed")

		assertThat(GitDirectoryReader.readHeadRevision(gitDir)).isNull()
	}

	@Test
	fun givenNoGitDirectory_whenSearching_thenNullIsReturned(
		@TempDir root: File,
	) {
		val module = File(root, "plugins/random-xkcd").apply { mkdirs() }

		assertThat(GitDirectoryReader.findGitDir(module)).isNull()
	}

	@Test
	fun givenAGitDirectoryAboveTheModule_whenSearching_thenTheWalkFindsIt(
		@TempDir root: File,
	) {
		val gitDir = gitDir(root, head = sha)
		val module = File(root, "plugins/random-xkcd").apply { mkdirs() }

		assertThat(GitDirectoryReader.findGitDir(module)?.canonicalFile).isEqualTo(gitDir.canonicalFile)
	}

	@Test
	fun givenADotGitFile_whenSearching_thenTheGitdirPointerIsFollowed(
		@TempDir root: File,
	) {
		val realGitDir = File(root, "store/worktrees/feature").apply { mkdirs() }
		val worktree = File(root, "checkout").apply { mkdirs() }
		File(worktree, ".git").writeText("gitdir: ${realGitDir.absolutePath}\n")

		assertThat(GitDirectoryReader.findGitDir(worktree)?.canonicalFile)
			.isEqualTo(realGitDir.canonicalFile)
	}

	@Test
	fun givenALinkedWorktree_whenItsRefLivesInTheCommonDir_thenItIsStillResolved(
		@TempDir root: File,
	) {
		val commonDir = File(root, "main/.git").apply { mkdirs() }
		writeFile(commonDir, "refs/heads/feature", sha)

		val worktreeGitDir = File(root, "main/.git/worktrees/feature").apply { mkdirs() }
		File(worktreeGitDir, "HEAD").writeText("ref: refs/heads/feature\n")
		File(worktreeGitDir, "commondir").writeText("../..\n")

		assertThat(GitDirectoryReader.readHeadRevision(worktreeGitDir)).isEqualTo(sha)
	}

	@Test
	fun givenAGitArchiveExport_whenSearching_thenThereIsNothingToRead(
		@TempDir root: File,
	) {
		// `git archive` drops .git entirely; the build must degrade, not fail.
		File(root, "build.gradle.kts").writeText("// exported\n")

		assertThat(GitDirectoryReader.findGitDir(root)).isNull()
	}

	private fun gitDir(
		root: File,
		head: String,
	): File {
		val gitDir = File(root, ".git").apply { mkdirs() }
		File(gitDir, "HEAD").writeText("$head\n")
		return gitDir
	}

	private fun writeFile(
		gitDir: File,
		relativePath: String,
		content: String,
	) {
		val file = File(gitDir, relativePath)
		file.parentFile.mkdirs()
		file.writeText(content)
	}
}
