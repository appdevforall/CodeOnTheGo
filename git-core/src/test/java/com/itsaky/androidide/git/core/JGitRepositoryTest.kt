package com.itsaky.androidide.git.core

import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.After
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

		// Create an initial commit so HEAD points to a valid commit
		val dummyFile = File(repoDir, "file.txt")
		dummyFile.writeText("initial content")

		Git.init().setDirectory(repoDir).call().use { git ->
			git.add().addFilepattern("file.txt").call()
			git
				.commit()
				.setMessage("Initial commit")
				.setAuthor("Test", "test@example.com")
				.call()
		}

		jgitRepo = JGitRepository(repoDir)
	}

	@After
	fun tearDown() {
		jgitRepo.close()
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

	@Test
	fun testCheckoutRemoteTrackingBranch() =
		runBlocking {
			// Manually create a remote ref refs/remotes/origin/release
			val headCommit =
				org.eclipse.jgit.storage.file.FileRepositoryBuilder().setWorkTree(repoDir).findGitDir(repoDir).build().use { repo ->
					repo.resolve(org.eclipse.jgit.lib.Constants.HEAD)
				}
			org.eclipse.jgit.storage.file.FileRepositoryBuilder().setWorkTree(repoDir).findGitDir(repoDir).build().use { repo ->
				val refUpdate = repo.updateRef("refs/remotes/origin/release")
				refUpdate.setNewObjectId(headCommit)
				refUpdate.update()
			}

			// Checkout remote branch -> should create local branch "release"
			jgitRepo.checkout("origin/release", createNew = false)
			val currentBranch = jgitRepo.getCurrentBranch()
			assertNotNull(currentBranch)
			assertEquals("release", currentBranch!!.name)

			// Now create another remote ref with the same short name under a different remote "upstream/release"
			org.eclipse.jgit.storage.file.FileRepositoryBuilder().setWorkTree(repoDir).findGitDir(repoDir).build().use { repo ->
				val refUpdate = repo.updateRef("refs/remotes/upstream/release")
				refUpdate.setNewObjectId(headCommit)
				refUpdate.update()
			}

			// Checkout upstream/release -> local "release" exists and tracks origin/release, so it should create "upstream-release"
			jgitRepo.checkout("upstream/release", createNew = false)
			val newCurrentBranch = jgitRepo.getCurrentBranch()
			assertNotNull(newCurrentBranch)
			assertEquals("upstream-release", newCurrentBranch!!.name)
		}
}
