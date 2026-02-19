package com.itsaky.androidide.git.core

import com.itsaky.androidide.git.core.models.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand.ListMode
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.io.File

/**
 * JGit-based implementation of the [GitRepository] interface.
 */
class JGitRepository(override val rootDir: File) : GitRepository {

    private val repository: Repository = FileRepositoryBuilder()
        .setWorkTree(rootDir)
        .findGitDir(rootDir)
        .build()

    private val git: Git = Git(repository)

    override fun getStatus(): GitStatus {
        val jgitStatus = git.status().call()
        
        val staged = mutableListOf<FileChange>()
        val unstaged = mutableListOf<FileChange>()
        val untracked = mutableListOf<FileChange>()
        val conflicted = mutableListOf<FileChange>()

        jgitStatus.added.forEach { staged.add(FileChange(it, ChangeType.ADDED)) }
        jgitStatus.changed.forEach { staged.add(FileChange(it, ChangeType.MODIFIED)) }
        jgitStatus.removed.forEach { staged.add(FileChange(it, ChangeType.DELETED)) }

        jgitStatus.modified.forEach { unstaged.add(FileChange(it, ChangeType.MODIFIED)) }
        jgitStatus.missing.forEach { unstaged.add(FileChange(it, ChangeType.DELETED)) }
        
        jgitStatus.untracked.forEach { untracked.add(FileChange(it, ChangeType.UNTRACKED)) }
        
        jgitStatus.conflicting.forEach { conflicted.add(FileChange(it, ChangeType.CONFLICTED)) }

        return GitStatus(
            isClean = jgitStatus.isClean,
            hasConflicts = jgitStatus.conflicting.isNotEmpty(),
            staged = staged,
            unstaged = unstaged,
            untracked = untracked,
            conflicted = conflicted
        )
    }

    override fun getCurrentBranch(): GitBranch? {
        val head = repository.fullBranch ?: return null
        val shortName = repository.branch ?: head
        return GitBranch(
            name = shortName,
            fullName = head,
            isCurrent = true,
            isRemote = head.startsWith(Constants.R_REMOTES)
        )
    }

    override fun getBranches(): List<GitBranch> {
        val currentBranch = repository.fullBranch
        return git.branchList().setListMode(ListMode.ALL).call().map { ref ->
            GitBranch(
                name = Repository.shortenRefName(ref.name),
                fullName = ref.name,
                isCurrent = ref.name == currentBranch,
                isRemote = ref.name.startsWith(Constants.R_REMOTES)
            )
        }
    }

    override fun getHistory(limit: Int): List<GitCommit> {
        return try {
            git.log().setMaxCount(limit).call().map { revCommit ->
                revCommit.toGitCommit()
            }
        } catch (_: org.eclipse.jgit.api.errors.NoHeadException) {
            emptyList()
        }
    }

    override fun getDiff(file: File): String {
        return ""
    }

    private fun RevCommit.toGitCommit(): GitCommit {
        val author = authorIdent
        return GitCommit(
            hash = name,
            shortHash = name.take(7),
            authorName = author.name,
            authorEmail = author.emailAddress,
            message = fullMessage,
            timestamp = author.`when`.time,
            parentHashes = parents.map { it.name }
        )
    }
    
    override fun close() {
        repository.close()
        git.close()
    }
}
