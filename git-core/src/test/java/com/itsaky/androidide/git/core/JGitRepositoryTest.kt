package com.itsaky.androidide.git.core

import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.Assert.*
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
        git.commit().setMessage("Initial commit").setAuthor("Test", "test@example.com").call()

        jgitRepo = JGitRepository(repoDir)
    }

    @Test
    fun testGetCurrentBranchAndGetBranches() = runBlocking {
        val currentBranch = jgitRepo.getCurrentBranch()
        assertNotNull(currentBranch)
        assertTrue(currentBranch!!.isCurrent)

        val branches = jgitRepo.getBranches()
        assertFalse(branches.isEmpty())
        assertTrue(branches.any { it.isCurrent })
    }

    @Test
    fun testCreateAndCheckoutBranch() = runBlocking {
        val newBranchName = "feature-test"
        jgitRepo.checkout(newBranchName, createNew = true)

        val currentBranch = jgitRepo.getCurrentBranch()
        assertNotNull(currentBranch)
        assertEquals(newBranchName, currentBranch!!.name)

        val branches = jgitRepo.getBranches()
        assertTrue(branches.any { it.name == newBranchName && it.isCurrent })
    }

    @Test
    fun testSwitchExistingBranches() = runBlocking {
        val initialBranch = jgitRepo.getCurrentBranch()!!.name

        // Create feature branch
        jgitRepo.checkout("feature-1", createNew = true)
        assertEquals("feature-1", jgitRepo.getCurrentBranch()!!.name)

        // Switch back to initial branch
        jgitRepo.checkout(initialBranch, createNew = false)
        assertEquals(initialBranch, jgitRepo.getCurrentBranch()!!.name)
    }
}
