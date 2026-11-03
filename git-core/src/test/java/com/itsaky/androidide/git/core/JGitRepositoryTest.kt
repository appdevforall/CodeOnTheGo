package com.itsaky.androidide.git.core

import com.itsaky.androidide.git.core.models.ChangeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests verifying [JGitRepository] core operations and contracts.
 *
 * Each test executes against an isolated disposable Git repository created in [TemporaryFolder].
 * Verifies that branch checkout resolves local and remote tracking refs correctly,
 * handles naming collisions cleanly, and ensures that aborting a merge completely restores
 * the pre-merge working tree state back to HEAD.
 */
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

			// Now test when "upstream-release" already exists and we check out upstream/release again
			jgitRepo.checkout("origin/release", createNew = false)
			assertEquals("release", jgitRepo.getCurrentBranch()?.name)

			// Checking out upstream/release should reuse upstream-release because it tracks upstream/release
			jgitRepo.checkout("upstream/release", createNew = false)
			assertEquals("upstream-release", jgitRepo.getCurrentBranch()?.name)
		}

	@Test
	fun testGetBranchesFiltersOutOriginHead() =
		runBlocking {
			val headCommit =
				org.eclipse.jgit.storage.file.FileRepositoryBuilder().setWorkTree(repoDir).findGitDir(repoDir).build().use { repo ->
					repo.resolve(org.eclipse.jgit.lib.Constants.HEAD)
				}
			org.eclipse.jgit.storage.file.FileRepositoryBuilder().setWorkTree(repoDir).findGitDir(repoDir).build().use { repo ->
				// Create refs/remotes/origin/main
				val refMain = repo.updateRef("refs/remotes/origin/main")
				refMain.setNewObjectId(headCommit)
				refMain.update()

				// Create symbolic or direct refs/remotes/origin/HEAD
				val refHead = repo.updateRef("refs/remotes/origin/HEAD")
				refHead.setNewObjectId(headCommit)
				refHead.update()
			}

			val branches = jgitRepo.getBranches()
			val branchNames = branches.map { it.name }
			assertTrue(branchNames.contains("origin/main"))
			assertFalse("origin/HEAD should be filtered out", branchNames.contains("origin/HEAD"))
			assertFalse("refs/remotes/origin/HEAD should be filtered out", branches.any { it.fullName.endsWith("/HEAD") })
		}

	@Test
	fun testCheckoutRemoteReusesUntrackedLocalBranch() =
		runBlocking {
			val initialBranch = jgitRepo.getCurrentBranch()!!.name

			// A branch created locally has no upstream configured
			jgitRepo.checkout("release", createNew = true)
			jgitRepo.checkout(initialBranch, createNew = false)

			val headCommit =
				org.eclipse.jgit.storage.file.FileRepositoryBuilder().setWorkTree(repoDir).findGitDir(repoDir).build().use { repo ->
					repo.resolve(org.eclipse.jgit.lib.Constants.HEAD)
				}
			org.eclipse.jgit.storage.file.FileRepositoryBuilder().setWorkTree(repoDir).findGitDir(repoDir).build().use { repo ->
				val refUpdate = repo.updateRef("refs/remotes/origin/release")
				refUpdate.setNewObjectId(headCommit)
				refUpdate.update()
			}

			// The untracked local "release" is the counterpart, so reuse it instead of forking "origin-release"
			jgitRepo.checkout("origin/release", createNew = false)
			assertEquals("release", jgitRepo.getCurrentBranch()!!.name)
			assertFalse(
				"origin-release should not be created",
				jgitRepo.getBranches().any { it.name == "origin-release" },
			)
		}

	@Test
	fun testCommitWatermarkDefaultsToTrue() =
		runBlocking {
			assertTrue(jgitRepo.isCommitWatermarkEnabled())
		}

	@Test
	fun testSetCommitWatermarkPersistsInGitConfig() =
		runBlocking {
			jgitRepo.setCommitWatermarkEnabled(false)
			assertFalse(jgitRepo.isCommitWatermarkEnabled())

			val configFile = File(repoDir, ".git/config")
			assertTrue("config file must exist", configFile.exists())
			val configContent = configFile.readText()
			assertTrue("config must contain cotg section", configContent.contains("[cotg]"))
			assertTrue("config must set commit-watermark to false", configContent.contains("commit-watermark = false"))

			// Verify a new repository instance reading from disk also sees false
			JGitRepository(repoDir).use { freshRepo ->
				assertFalse(freshRepo.isCommitWatermarkEnabled())
			}
		}

	@Test
	fun testToggleCommitWatermarkBackToTrue() =
		runBlocking {
			jgitRepo.setCommitWatermarkEnabled(false)
			assertFalse(jgitRepo.isCommitWatermarkEnabled())

			jgitRepo.setCommitWatermarkEnabled(true)
			assertTrue(jgitRepo.isCommitWatermarkEnabled())

			// Verify a new repository instance reading from disk also sees true
			JGitRepository(repoDir).use { freshRepo ->
				assertTrue(freshRepo.isCommitWatermarkEnabled())
			}
		}

	@Test
	fun testConcurrentWatermarkWritesSerializeWithoutLockCollision() =
		runBlocking {
			val jobs =
				List(10) { index ->
					async(Dispatchers.IO) {
						jgitRepo.setCommitWatermarkEnabled(index % 2 == 0)
					}
				}
			jobs.awaitAll()
			// Should complete without throwing LockFailedException and return a valid boolean
			val isEnabled = jgitRepo.isCommitWatermarkEnabled()
			assertTrue(isEnabled || !isEnabled)
		}

	@Test(expected = Exception::class)
	fun testSetCommitWatermarkPropagatesExceptionOnFailure() =
		runBlocking {
			val lockFile = File(repoDir, ".git/config.lock")
			lockFile.mkdir() // Making the lock path a directory causes LockFile creation to fail
			jgitRepo.setCommitWatermarkEnabled(false)
		}

	@Test(expected = Exception::class)
	fun testIsCommitWatermarkPropagatesExceptionOnFailure() =
		runBlocking {
			val configFile = File(repoDir, ".git/config")
			configFile.writeText("[unclosed_section\nkey = value")
			jgitRepo.isCommitWatermarkEnabled()
			Unit
		}

	@Test
	fun testStageFilesHandlesCaseOnlyFolderRename() =
		runBlocking {
			val subDir = File(repoDir, "myFolder")
			subDir.mkdir()
			val file = File(subDir, "test.txt")
			file.writeText("content")
			jgitRepo.stageFiles(listOf(file))
			jgitRepo.commit("Initial commit with myFolder", "User", "user@test.com")

			// Perform case-only rename: myFolder -> MyFolder
			val tempDir = File(repoDir, "myFolder_temp")
			val newDir = File(repoDir, "MyFolder")
			assertTrue(subDir.renameTo(tempDir))
			assertTrue(tempDir.renameTo(newDir))

			val statusBefore = jgitRepo.getStatus()
			val renamedChange = statusBefore.unstaged.find { it.path == "MyFolder/test.txt" }
			assertNotNull("Renamed change should be in unstaged", renamedChange)
			assertEquals(ChangeType.RENAMED, renamedChange!!.type)
			assertEquals("myFolder/test.txt", renamedChange.oldPath)
			assertFalse(
				"Old deleted case should not be separately listed in unstaged",
				statusBefore.unstaged.any { it.path == "myFolder/test.txt" },
			)
			assertFalse(
				"New case should not be in untracked",
				statusBefore.untracked.any { it.path == "MyFolder/test.txt" },
			)

			// Staging only the new path automatically evicts the old casing
			val newFile = File(repoDir, "MyFolder/test.txt")
			jgitRepo.stageFiles(listOf(newFile))

			val statusStaged = jgitRepo.getStatus()
			val stagedRenamed = statusStaged.staged.find { it.path == "MyFolder/test.txt" }
			assertNotNull("Renamed change should be in staged", stagedRenamed)
			assertEquals(ChangeType.RENAMED, stagedRenamed!!.type)
			assertEquals("myFolder/test.txt", stagedRenamed.oldPath)
			assertTrue("Unstaged should be empty after staging rename", statusStaged.unstaged.isEmpty())

			jgitRepo.commit("Rename myFolder to MyFolder", "User", "user@test.com")

			val statusAfter = jgitRepo.getStatus()
			assertTrue(statusAfter.isClean)
			assertTrue(statusAfter.unstaged.isEmpty())
			assertTrue(statusAfter.untracked.isEmpty())
			assertTrue(File(repoDir, "MyFolder/test.txt").exists())
		}

	@Test
	fun testCaseOnlyFileRenameAggregatedAsRenamed() =
		runBlocking {
			val file = File(repoDir, "hello.txt")
			file.writeText("hello world")
			jgitRepo.stageFiles(listOf(file))
			jgitRepo.commit("Initial commit with hello.txt", "User", "user@test.com")

			// Perform case-only file rename: hello.txt -> Hello.txt
			val tempFile = File(repoDir, "hello_temp.txt")
			val newFile = File(repoDir, "Hello.txt")
			assertTrue(file.renameTo(tempFile))
			assertTrue(tempFile.renameTo(newFile))

			val statusBefore = jgitRepo.getStatus()
			val renamedChange = statusBefore.unstaged.find { it.path == "Hello.txt" }
			assertNotNull("Renamed change should be in unstaged", renamedChange)
			assertEquals(ChangeType.RENAMED, renamedChange!!.type)
			assertEquals("hello.txt", renamedChange.oldPath)
			assertFalse(
				"Old deleted case should not be separately listed",
				statusBefore.unstaged.any { it.path == "hello.txt" },
			)
			assertFalse(
				"New case should not be in untracked",
				statusBefore.untracked.any { it.path == "Hello.txt" },
			)

			// Stage only new file -> auto evicts old casing
			jgitRepo.stageFiles(listOf(newFile))
			val statusStaged = jgitRepo.getStatus()
			val stagedRenamed = statusStaged.staged.find { it.path == "Hello.txt" }
			assertNotNull("Renamed change should be in staged", stagedRenamed)
			assertEquals(ChangeType.RENAMED, stagedRenamed!!.type)
			assertEquals("hello.txt", stagedRenamed.oldPath)

			jgitRepo.commit("Rename hello.txt to Hello.txt", "User", "user@test.com")
			val statusAfter = jgitRepo.getStatus()
			assertTrue(statusAfter.isClean)
			assertTrue(File(repoDir, "Hello.txt").exists())
		}

	@Test
	fun testStatusCleanWhenNewCaseCommittedExternally() =
		runBlocking {
			val subDir = File(repoDir, "java")
			subDir.mkdir()
			val file = File(subDir, "MainActivity.java")
			file.writeText("content")
			jgitRepo.stageFiles(listOf(file))
			jgitRepo.commit("Initial commit with java", "User", "user@test.com")

			// Perform case-only rename: java -> Java
			val tempDir = File(repoDir, "temp_dir")
			val newDir = File(repoDir, "Java")
			assertTrue(subDir.renameTo(tempDir))
			assertTrue(tempDir.renameTo(newDir))

			// Before terminal commit, UI pair detection shows case-rename as RENAMED
			val statusBefore = jgitRepo.getStatus()
			val renamedChange = statusBefore.unstaged.find { it.path == "Java/MainActivity.java" }
			assertNotNull("Renamed change should be in unstaged", renamedChange)
			assertEquals(ChangeType.RENAMED, renamedChange!!.type)
			assertEquals("java/MainActivity.java", renamedChange.oldPath)
			assertFalse(statusBefore.unstaged.any { it.path == "java/MainActivity.java" })
			assertFalse(statusBefore.untracked.any { it.path == "Java/MainActivity.java" })

			// Simulate terminal commit that only added the new casing
			Git.open(repoDir).use { git ->
				git.add().addFilepattern("Java/MainActivity.java").call()
				git.commit().setMessage("terminal commit only new case").call()
			}

			// Verify status is clean after terminal commit (ghost old case is not shown as deleted)
			val statusAfter = jgitRepo.getStatus()
			assertTrue("Status should be clean after terminal commit", statusAfter.isClean)
			assertTrue("Unstaged should be empty", statusAfter.unstaged.isEmpty())
			assertTrue("Staged should be empty", statusAfter.staged.isEmpty())
			assertTrue(File(repoDir, "Java/MainActivity.java").exists())
		}

	@Test
	fun testCaseRenameFileExternalProcess() =
		runBlocking {
			val file = File(repoDir, "file.txt")
			file.writeText("hello")
			jgitRepo.stageFiles(listOf(file))
			jgitRepo.commit("Initial commit with file.txt", "User", "user@test.com")

			// Perform case-only rename: file.txt -> File.txt
			val tempFile = File(repoDir, "temp.txt")
			val newFile = File(repoDir, "File.txt")
			assertTrue(file.renameTo(tempFile))
			assertTrue(tempFile.renameTo(newFile))

			// User is on Git tab: getStatus() is called on jgitRepo
			val statusBefore = jgitRepo.getStatus()
			val renamed = statusBefore.unstaged.find { it.path == "File.txt" }
			assertNotNull("Should show 1 change - renamed file", renamed)
			assertEquals(ChangeType.RENAMED, renamed!!.type)
			assertEquals("file.txt", renamed.oldPath)

			// External commit that stages new casing
			Git.open(repoDir).use { git ->
				git.add().addFilepattern("File.txt").call()
				git.commit().setMessage("terminal commit file rename").call()
			}

			// User comes back to Git tab: getStatus() is called again on the same jgitRepo instance
			val statusAfter = jgitRepo.getStatus()
			assertTrue("Status should be clean after external commit", statusAfter.isClean)
			assertTrue("Unstaged should be empty", statusAfter.unstaged.isEmpty())
			assertTrue("Staged should be empty", statusAfter.staged.isEmpty())
		}

	@Test
	fun testFindRealCasingOnDisk() {
		val subDir = File(repoDir, "myPackage")
		subDir.mkdir()
		val file = File(subDir, "MyClass.java")
		file.writeText("class MyClass {}")

		// Query with different case for directory and file
		val resolved = jgitRepo.findRealCasingOnDisk(repoDir, "mypackage/myclass.java")
		assertEquals("myPackage/MyClass.java", resolved)

		// Query non-existent file
		val notFound = jgitRepo.findRealCasingOnDisk(repoDir, "myPackage/NonExistent.java")
		assertNull(notFound)
	}

	@Test
	fun testStageFilesStandardDeletion() =
		runBlocking {
			val deleteFile = File(repoDir, "delete_me.txt")
			deleteFile.writeText("to be deleted")
			jgitRepo.stageFiles(listOf(deleteFile))
			jgitRepo.commit("Add delete_me.txt", "User", "user@test.com")

			assertTrue(deleteFile.delete())

			val statusBefore = jgitRepo.getStatus()
			assertTrue(statusBefore.unstaged.any { it.path == "delete_me.txt" && it.type == ChangeType.DELETED })

			jgitRepo.stageFiles(listOf(deleteFile))
			jgitRepo.commit("Remove delete_me.txt", "User", "user@test.com")

			val statusAfter = jgitRepo.getStatus()
			assertTrue(statusAfter.isClean)
			assertTrue(statusAfter.unstaged.isEmpty())
		}

	@Test
	fun testRenamePathFolderMultipleTimes() =
		runBlocking {
			val subDir = File(repoDir, "subDir")
			subDir.mkdir()
			val file = File(subDir, "test.txt")
			file.writeText("hello from subDir")
			jgitRepo.stageFiles(listOf(file))
			jgitRepo.commit("Initial commit with subDir", "User", "user@test.com")

			// 1st Rename: subDir -> SubDir
			val tempDir = File(repoDir, "subDir_temp")
			val newDir = File(repoDir, "SubDir")
			assertTrue(subDir.renameTo(tempDir))
			assertTrue(tempDir.renameTo(newDir))

			val renamed1 = jgitRepo.renamePath(subDir, newDir)
			assertTrue(renamed1)

			val status1 = jgitRepo.getStatus()
			val staged1 = status1.staged.find { it.path == "SubDir/test.txt" }
			assertNotNull("Should be staged as renamed", staged1)
			assertEquals(ChangeType.RENAMED, staged1!!.type)
			assertEquals("subDir/test.txt", staged1.oldPath)
			assertTrue("Unstaged should be empty", status1.unstaged.isEmpty())
			assertTrue("Untracked should be empty", status1.untracked.isEmpty())

			jgitRepo.commit("Rename 1: subDir -> SubDir", "User", "user@test.com")
			assertTrue(jgitRepo.getStatus().isClean)

			// 2nd Rename: SubDir -> subDir
			assertTrue(newDir.renameTo(tempDir))
			assertTrue(tempDir.renameTo(subDir))

			val renamed2 = jgitRepo.renamePath(newDir, subDir)
			assertTrue(renamed2)

			val status2 = jgitRepo.getStatus()
			val staged2 = status2.staged.find { it.path == "subDir/test.txt" }
			assertNotNull("Should be staged as renamed on 2nd rename", staged2)
			assertEquals(ChangeType.RENAMED, staged2!!.type)
			assertEquals("SubDir/test.txt", staged2.oldPath)
			assertTrue("Unstaged should be empty", status2.unstaged.isEmpty())
			assertTrue("Untracked should be empty", status2.untracked.isEmpty())

			jgitRepo.commit("Rename 2: SubDir -> subDir", "User", "user@test.com")
			assertTrue(jgitRepo.getStatus().isClean)

			// 3rd Rename: subDir -> SubDir
			assertTrue(subDir.renameTo(tempDir))
			assertTrue(tempDir.renameTo(newDir))

			val renamed3 = jgitRepo.renamePath(subDir, newDir)
			assertTrue(renamed3)

			val status3 = jgitRepo.getStatus()
			val staged3 = status3.staged.find { it.path == "SubDir/test.txt" }
			assertNotNull("Should be staged as renamed on 3rd rename", staged3)
			assertEquals(ChangeType.RENAMED, staged3!!.type)
			assertEquals("subDir/test.txt", staged3.oldPath)
		}

	@Test
	fun testRenamePathFile() =
		runBlocking {
			val file = File(repoDir, "myFile.txt")
			file.writeText("hello")
			jgitRepo.stageFiles(listOf(file))
			jgitRepo.commit("Initial commit with myFile.txt", "User", "user@test.com")

			// 1st Rename: myFile.txt -> MyFile.txt
			val tempFile = File(repoDir, "myFile_temp.txt")
			val newFile = File(repoDir, "MyFile.txt")
			assertTrue(file.renameTo(tempFile))
			assertTrue(tempFile.renameTo(newFile))

			val renamed1 = jgitRepo.renamePath(file, newFile)
			assertTrue(renamed1)

			val status1 = jgitRepo.getStatus()
			val staged1 = status1.staged.find { it.path == "MyFile.txt" }
			assertNotNull(staged1)
			assertEquals(ChangeType.RENAMED, staged1!!.type)
			assertEquals("myFile.txt", staged1.oldPath)

			jgitRepo.commit("Rename myFile to MyFile", "User", "user@test.com")
			assertTrue(jgitRepo.getStatus().isClean)

			// 2nd Rename: MyFile.txt -> myFile.txt
			assertTrue(newFile.renameTo(tempFile))
			assertTrue(tempFile.renameTo(file))

			val renamed2 = jgitRepo.renamePath(newFile, file)
			assertTrue(renamed2)

			val status2 = jgitRepo.getStatus()
			val staged2 = status2.staged.find { it.path == "myFile.txt" }
			assertNotNull(staged2)
			assertEquals(ChangeType.RENAMED, staged2!!.type)
			assertEquals("MyFile.txt", staged2.oldPath)
		}
}
