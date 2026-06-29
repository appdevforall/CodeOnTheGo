package com.itsaky.androidide.git.core

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.git.core.models.*
import org.junit.Assert.*
import org.junit.Test

class GitModelsTest {

    // --- GitStatus ---

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
    fun `GitStatus EMPTY is not merging`() {
        assertFalse(GitStatus.EMPTY.isMerging)
    }

    @Test
    fun `GitStatus with staged files is not clean`() {
        val status = GitStatus(
            isClean = false,
            hasConflicts = false,
            isMerging = false,
            staged = listOf(FileChange("src/Main.kt", ChangeType.ADDED)),
            unstaged = emptyList(),
            untracked = emptyList(),
            conflicted = emptyList()
        )
        assertFalse(status.isClean)
        assertThat(status.staged).hasSize(1)
    }

    @Test
    fun `GitStatus with conflicts sets both flags`() {
        val status = GitStatus(
            isClean = false,
            hasConflicts = true,
            isMerging = true,
            staged = emptyList(),
            unstaged = emptyList(),
            untracked = emptyList(),
            conflicted = listOf(FileChange("Conflict.kt", ChangeType.MODIFIED))
        )
        assertTrue(status.hasConflicts)
        assertTrue(status.isMerging)
        assertThat(status.conflicted).hasSize(1)
    }

    @Test
    fun `GitStatus equality is value-based`() {
        val s1 = GitStatus.EMPTY
        val s2 = GitStatus(
            isClean = true,
            hasConflicts = false,
            isMerging = false,
            staged = emptyList(),
            unstaged = emptyList(),
            untracked = emptyList(),
            conflicted = emptyList()
        )
        assertThat(s1).isEqualTo(s2)
    }

    // --- FileChange ---

    @Test
    fun testFileChange() {
        val change = FileChange("path/to/file.txt", ChangeType.MODIFIED)
        assertEquals("path/to/file.txt", change.path)
        assertEquals(ChangeType.MODIFIED, change.type)
        assertNull(change.oldPath)
    }

    @Test
    fun `FileChange with ADDED type`() {
        val change = FileChange("New.kt", ChangeType.ADDED)
        assertThat(change.type).isEqualTo(ChangeType.ADDED)
        assertThat(change.oldPath).isNull()
    }

    @Test
    fun `FileChange with DELETED type`() {
        val change = FileChange("Old.kt", ChangeType.DELETED)
        assertThat(change.type).isEqualTo(ChangeType.DELETED)
    }

    @Test
    fun `FileChange with RENAMED type carries oldPath`() {
        val change = FileChange("NewName.kt", ChangeType.RENAMED, oldPath = "OldName.kt")
        assertThat(change.type).isEqualTo(ChangeType.RENAMED)
        assertThat(change.oldPath).isEqualTo("OldName.kt")
    }

    @Test
    fun `FileChange equality is value-based`() {
        val c1 = FileChange("a.kt", ChangeType.MODIFIED)
        val c2 = FileChange("a.kt", ChangeType.MODIFIED)
        assertThat(c1).isEqualTo(c2)
    }

    @Test
    fun `FileChange with different paths are not equal`() {
        assertThat(FileChange("a.kt", ChangeType.ADDED))
            .isNotEqualTo(FileChange("b.kt", ChangeType.ADDED))
    }

    // --- GitBranch ---

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
    fun `GitBranch remote branch carries remoteName`() {
        val branch = GitBranch(
            name = "origin/main",
            fullName = "refs/remotes/origin/main",
            isCurrent = false,
            isRemote = true,
            remoteName = "origin"
        )
        assertThat(branch.isRemote).isTrue()
        assertThat(branch.remoteName).isEqualTo("origin")
    }

    @Test
    fun `GitBranch local branch has null remoteName by default`() {
        val branch = GitBranch(
            name = "feature",
            fullName = "refs/heads/feature",
            isCurrent = false,
            isRemote = false
        )
        assertThat(branch.remoteName).isNull()
    }

    @Test
    fun `GitBranch equality is value-based`() {
        val b1 = GitBranch("main", "refs/heads/main", true, false)
        val b2 = GitBranch("main", "refs/heads/main", true, false)
        assertThat(b1).isEqualTo(b2)
    }

    // --- GitCommit ---

    @Test
    fun testGitCommit() {
        val commit = GitCommit(
            hash = "abcdef1234567890",
            shortHash = "abcdef1",
            authorName = "Author",
            authorEmail = "author@example.com",
            message = "Commit message",
            timestamp = 1234567890L,
            parentHashes = emptyList(),
            hasBeenPushed = false
        )
        assertEquals("abcdef1234567890", commit.hash)
        assertEquals("abcdef1", commit.shortHash)
        assertEquals("Commit message", commit.message)
    }

    @Test
    fun `GitCommit with parent hashes for merge commit`() {
        val commit = GitCommit(
            hash = "aaa111",
            shortHash = "aaa",
            authorName = "Dev",
            authorEmail = "dev@example.com",
            message = "Merge branch 'feature'",
            timestamp = 1000L,
            parentHashes = listOf("bbb222", "ccc333"),
            hasBeenPushed = true
        )
        assertThat(commit.parentHashes).hasSize(2)
        assertThat(commit.hasBeenPushed).isTrue()
    }

    @Test
    fun `GitCommit equality is value-based`() {
        val c1 = GitCommit("h1", "h", "A", "a@b.c", "msg", 0L, emptyList(), false)
        val c2 = GitCommit("h1", "h", "A", "a@b.c", "msg", 0L, emptyList(), false)
        assertThat(c1).isEqualTo(c2)
    }

    @Test
    fun `GitCommit with different hashes are not equal`() {
        val c1 = GitCommit("h1", "h", "A", "a@b.c", "msg", 0L, emptyList(), false)
        val c2 = GitCommit("h2", "h", "A", "a@b.c", "msg", 0L, emptyList(), false)
        assertThat(c1).isNotEqualTo(c2)
    }

    // --- CommitHistoryUiState ---

    @Test
    fun `CommitHistoryUiState Loading is the loading state`() {
        val state: CommitHistoryUiState = CommitHistoryUiState.Loading
        assertThat(state).isInstanceOf(CommitHistoryUiState.Loading::class.java)
    }

    @Test
    fun `CommitHistoryUiState Empty has no commits`() {
        val state: CommitHistoryUiState = CommitHistoryUiState.Empty
        assertThat(state).isInstanceOf(CommitHistoryUiState.Empty::class.java)
    }

    @Test
    fun `CommitHistoryUiState Success carries commit list`() {
        val commit = GitCommit("abc", "abc", "Dev", "d@e.f", "init", 0L, emptyList(), false)
        val state = CommitHistoryUiState.Success(listOf(commit))
        assertThat(state.commits).hasSize(1)
        assertThat(state.commits[0]).isEqualTo(commit)
    }

    @Test
    fun `CommitHistoryUiState Error carries message`() {
        val state = CommitHistoryUiState.Error("network failure")
        assertThat(state.message).isEqualTo("network failure")
    }

    @Test
    fun `CommitHistoryUiState Error with null message`() {
        val state = CommitHistoryUiState.Error(null)
        assertThat(state.message).isNull()
    }

    @Test
    fun `CommitHistoryUiState Success equality is value-based`() {
        val commit = GitCommit("abc", "abc", "Dev", "d@e.f", "init", 0L, emptyList(), false)
        val s1 = CommitHistoryUiState.Success(listOf(commit))
        val s2 = CommitHistoryUiState.Success(listOf(commit))
        assertThat(s1).isEqualTo(s2)
    }
}
