package com.itsaky.androidide.git.core

import com.itsaky.androidide.git.core.models.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for Git models.
 */
class GitModelsTest {

    @Test
    fun testGitStatusEmpty() {
        val status = GitStatus.EMPTY
        assertTrue(status.isClean)
        assertFalse(status.hasConflicts)
        assertTrue(status.staged.isEmpty())
        assertTrue(status.unstaged.isEmpty())
        assertTrue(status.untracked.isEmpty())
        assertTrue(status.conflicted.isEmpty())
    }

    @Test
    fun testFileChange() {
        val change = FileChange("path/to/file.txt", ChangeType.MODIFIED)
        assertEquals("path/to/file.txt", change.path)
        assertEquals(ChangeType.MODIFIED, change.type)
        assertNull(change.oldPath)
    }

    @Test
    fun testGitBranch() {
        val branch = GitBranch(
            name = "main",
            fullName = "refs/heads/main",
            isCurrent = true,
            isRemote = false
        )
        assertEquals("main", branch.name)
        assertTrue(branch.isCurrent)
        assertFalse(branch.isRemote)
    }

    @Test
    fun testGitCommit() {
        val commit = GitCommit(
            hash = "abcdef1234567890",
            shortHash = "abcdef1",
            authorName = "Author",
            authorEmail = "author@example.com",
            message = "Commit message",
            timestamp = 1234567890L,
            parentHashes = emptyList()
        )
        assertEquals("abcdef1234567890", commit.hash)
        assertEquals("abcdef1", commit.shortHash)
        assertEquals("Commit message", commit.message)
    }
}
