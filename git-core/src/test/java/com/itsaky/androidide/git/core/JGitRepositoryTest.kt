package com.itsaky.androidide.git.core

import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JGitRepositoryTest {
	@get:Rule
	val tempFolder = TemporaryFolder()

	private lateinit var repoDir: File
	private lateinit var jgitRepo: JGitRepository

	@Before
	fun setUp() {
		repoDir = tempFolder.newFolder("test-repo")
		val git = Git.init().setDirectory(repoDir).call()

		// Create an initial commit so HEAD points to a valid commit
		val dummyFile = File(repoDir, "file.txt")
		dummyFile.writeText("initial content")
		git.add().addFilepattern("file.txt").call()
		git
			.commit()
			.setMessage("Initial commit")
			.setAuthor("Test", "test@example.com")
			.call()

		jgitRepo = JGitRepository(repoDir)
	}

	@Test
	fun testGetCurrentBranchAndGetBranches() =
		runBlocking {
			val currentBranch = jgitRepo.getCurrentBranch()
			assertNotNull(currentBranch)
			assertTrue(currentBranch!!.isCurrent)

			val branches = jgitRepo.getBranches()
			assertFalse(branches.isEmpty())
			assertTrue(branches.any { it.isCurrent })
		}

	@Test
	fun testCreateAndCheckoutBranch() =
		runBlocking {
			val newBranchName = "feature-test"
			jgitRepo.checkout(newBranchName, createNew = true)

			val currentBranch = jgitRepo.getCurrentBranch()
			assertNotNull(currentBranch)
			assertEquals(newBranchName, currentBranch!!.name)

			val branches = jgitRepo.getBranches()
			assertTrue(branches.any { it.name == newBranchName && it.isCurrent })
		}

	@Test
	fun testSwitchExistingBranches() =
		runBlocking {
			val initialBranch = jgitRepo.getCurrentBranch()!!.name

			// Create feature branch
			jgitRepo.checkout("feature-1", createNew = true)
			assertEquals("feature-1", jgitRepo.getCurrentBranch()!!.name)

			// Switch back to initial branch
			jgitRepo.checkout(initialBranch, createNew = false)
			assertEquals(initialBranch, jgitRepo.getCurrentBranch()!!.name)
		}

	@Test
	fun testMergeFastForward() =
		runBlocking {
			val initialBranch = jgitRepo.getCurrentBranch()!!.name

			// Create feature branch and make a commit
			jgitRepo.checkout("feature-merge", createNew = true)
			val featureFile = File(repoDir, "feature.txt")
			featureFile.writeText("feature content")
			jgitRepo.stageFiles(listOf(featureFile))
			jgitRepo.commit("Feature commit", "Test", "test@example.com")

			// Switch back to initial branch and merge
			jgitRepo.checkout(initialBranch, createNew = false)
			val result = jgitRepo.merge("feature-merge")
			assertTrue(result.mergeStatus.isSuccessful)
			assertTrue(File(repoDir, "feature.txt").exists())
		}

	@Test
	fun testAbortMerge() =
		runBlocking {
			val initialBranch = jgitRepo.getCurrentBranch()!!.name

			// Create feature branch and change file.txt
			jgitRepo.checkout("feature-conflict", createNew = true)
			val file = File(repoDir, "file.txt")
			file.writeText("feature conflict content")
			jgitRepo.stageFiles(listOf(file))
			jgitRepo.commit("Feature conflict commit", "Test", "test@example.com")

			// Switch to initial branch and make a conflicting change
			jgitRepo.checkout(initialBranch, createNew = false)
			file.writeText("initial conflicting content")
			jgitRepo.stageFiles(listOf(file))
			jgitRepo.commit("Main conflicting commit", "Test", "test@example.com")

			// Attempt merge -> CONFLICTING
			val mergeResult = jgitRepo.merge("feature-conflict")
			assertEquals(org.eclipse.jgit.api.MergeResult.MergeStatus.CONFLICTING, mergeResult.mergeStatus)
			val statusBeforeAbort = jgitRepo.getStatus()
			assertTrue(statusBeforeAbort.isMerging)
			assertTrue(statusBeforeAbort.hasConflicts)

			// Abort merge -> verify clean state
			jgitRepo.abortMerge()
			val statusAfterAbort = jgitRepo.getStatus()
			assertFalse(statusAfterAbort.isMerging)
			assertFalse(statusAfterAbort.hasConflicts)
			assertEquals("initial conflicting content", file.readText())
		}
}
