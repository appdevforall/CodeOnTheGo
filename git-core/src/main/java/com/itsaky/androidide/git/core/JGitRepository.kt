package com.itsaky.androidide.git.core

import com.itsaky.androidide.git.core.models.ChangeType
import com.itsaky.androidide.git.core.models.FileChange
import com.itsaky.androidide.git.core.models.GitBranch
import com.itsaky.androidide.git.core.models.GitCommit
import com.itsaky.androidide.git.core.models.GitStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand.ListMode
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.dircache.DirCacheIterator
import org.eclipse.jgit.lib.BranchConfig
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.PushResult
import org.eclipse.jgit.api.PullResult
import org.eclipse.jgit.api.ResetCommand.ResetType
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.treewalk.AbstractTreeIterator
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.treewalk.filter.PathFilter
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * JGit-based implementation of the [GitRepository] interface.
 */
class JGitRepository(override val rootDir: File) : GitRepository {

    private val log = LoggerFactory.getLogger(JGitRepository::class.java)

    private val repository: Repository = FileRepositoryBuilder()
        .setWorkTree(rootDir)
        .findGitDir(rootDir)
        .build()

    private val git: Git = Git(repository)

    private fun getHeadTree(repository: Repository): AbstractTreeIterator {
        val head = repository.resolve(Constants.HEAD) ?: return EmptyTreeIterator()
        val treeParser = CanonicalTreeParser()
        RevWalk(repository).use { revWalk ->
            val commit = revWalk.parseCommit(head)
            repository.newObjectReader().use { reader ->
                treeParser.reset(reader, commit.tree.id)
            }
        }
        return treeParser
    }

    override suspend fun getStatus(): GitStatus = withContext(Dispatchers.IO) {
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

        val isMerging = repository.repositoryState == RepositoryState.MERGING

        GitStatus(
            isClean = jgitStatus.isClean,
            hasConflicts = jgitStatus.conflicting.isNotEmpty(),
            isMerging = isMerging,
            staged = staged,
            unstaged = unstaged,
            untracked = untracked,
            conflicted = conflicted
        )
    }

    override suspend fun getCurrentBranch(): GitBranch? = withContext(Dispatchers.IO) {
        val head = repository.fullBranch ?: return@withContext null
        val shortName = repository.branch ?: head
        GitBranch(
            name = shortName,
            fullName = head,
            isCurrent = true,
            isRemote = head.startsWith(Constants.R_REMOTES)
        )
    }

    override suspend fun getBranches(): List<GitBranch> = withContext(Dispatchers.IO) {
        val currentBranch = repository.fullBranch
        git.branchList().setListMode(ListMode.ALL).call().map { ref ->
            GitBranch(
                name = Repository.shortenRefName(ref.name),
                fullName = ref.name,
                isCurrent = ref.name == currentBranch,
                isRemote = ref.name.startsWith(Constants.R_REMOTES)
            )
        }
    }

    override suspend fun getHistory(limit: Int): List<GitCommit> = withContext(Dispatchers.IO) {
        try {
            val branchName = repository.branch ?: return@withContext emptyList()
            val trackingBranch = BranchConfig(repository.config, branchName).trackingBranch

            RevWalk(repository).use { walk ->
                val remoteCommit = trackingBranch?.let { repository.resolve(it) }?.let {
                    walk.parseCommit(it)
                }

                git.log().setMaxCount(limit).call().map { revCommit ->
                    val commit = walk.parseCommit(revCommit.id)
                    val isPushed = remoteCommit?.let { walk.isMergedInto(commit, it) } ?: false
                    commit.toGitCommit(isPushed)
                }
            }
        } catch (e: Exception) {
            log.error("Error fetching commit history", e)
            emptyList()
        }
    }

    override suspend fun getDiff(file: File): String = withContext(Dispatchers.IO) {
        val relativePath = file.toRelativeString(rootDir).replace('\\', '/')
        val outputStream = ByteArrayOutputStream()
        DiffFormatter(outputStream).use { formatter ->
            formatter.setRepository(repository)
            val indexTree = DirCacheIterator(repository.readDirCache())
            val workingTree = FileTreeIterator(repository)
            formatter.pathFilter = PathFilter.create(relativePath)
            formatter.format(indexTree, workingTree)
            
            // If empty, check staged diff
            if (outputStream.size() == 0) {
                val headTree = getHeadTree(repository)
                val freshIndexTree = DirCacheIterator(repository.readDirCache())
                formatter.format(headTree, freshIndexTree)
            }
        }
        outputStream.toString()
    }

    override suspend fun stageFiles(files: List<File>) = withContext(Dispatchers.IO) {
        val addCommand = git.add()
        val rmCommand = git.rm()
        var hasAdds = false
        var hasRms = false

        files.forEach { file ->
            val relativePath = file.toRelativeString(rootDir).replace('\\', '/')
            if (file.exists()) {
                addCommand.addFilepattern(relativePath)
                hasAdds = true
            } else {
                rmCommand.addFilepattern(relativePath)
                hasRms = true
            }
        }
        if (hasAdds) addCommand.call()
        if (hasRms) rmCommand.call()
        Unit
    }

    override suspend fun commit(
        message: String,
        authorName: String?,
        authorEmail: String?
    ): GitCommit? = withContext(Dispatchers.IO) {
        val commitCommand = git.commit().setMessage(message)

        if (!authorName.isNullOrBlank() && !authorEmail.isNullOrBlank()) {
            val author = PersonIdent(authorName, authorEmail)
            commitCommand.apply {
                setAuthor(author)
                setCommitter(author)
            }
        }

        val revCommit = commitCommand.call()
        revCommit?.toGitCommit(false)
    }

    private fun RevCommit.toGitCommit(hasBeenPushed: Boolean): GitCommit {
        val author = authorIdent
        return GitCommit(
            hash = name,
            shortHash = name.take(7),
            authorName = author.name,
            authorEmail = author.emailAddress,
            message = fullMessage.trim(),
            timestamp = author.`when`.time,
            parentHashes = parents.map { it.name },
            hasBeenPushed = hasBeenPushed
        )
    }
    
    override suspend fun push(
        remote: String,
        credentialsProvider: CredentialsProvider?,
        progressMonitor: ProgressMonitor?
    ): Iterable<PushResult> = withContext(Dispatchers.IO) {
        val pushCommand = git.push().setRemote(remote)
        
        if (credentialsProvider != null) {
            pushCommand.setCredentialsProvider(credentialsProvider)
        }
        
        if (progressMonitor != null) {
            pushCommand.setProgressMonitor(progressMonitor)
        }

        pushCommand.call()
    }

    override suspend fun getLocalCommitsCount(): Int = withContext(Dispatchers.IO) {
        try {
            val branchName = repository.branch ?: return@withContext 0
            val branch = repository.resolve(Constants.HEAD) ?: return@withContext 0
            val config = BranchConfig(repository.config, branchName)
            val trackingBranch = config.trackingBranch
            val remoteBranch = trackingBranch?.let { repository.resolve(it) }

            RevWalk(repository).use { walk ->
                val localCommit = walk.parseCommit(branch)
                walk.markStart(localCommit)
                
                if (remoteBranch != null) {
                    val remoteCommit = walk.parseCommit(remoteBranch)
                    walk.markUninteresting(remoteCommit)
                }

                var count = 0
                walk.forEach { _ ->
                    count++
                }
                count
            }
        } catch (e: Exception) {
            log.error("Error fetching local commits", e)
            0
        }
    }

    override suspend fun pull(
        remote: String,
        credentialsProvider: CredentialsProvider?,
        progressMonitor: ProgressMonitor?
    ): PullResult = withContext(Dispatchers.IO) {
        val pullCommand = git.pull().setRemote(remote)
        
        if (credentialsProvider != null) {
            pullCommand.setCredentialsProvider(credentialsProvider)
        }
        
        if (progressMonitor != null) {
            pullCommand.setProgressMonitor(progressMonitor)
        }

        pullCommand.call()
    }
    
    override suspend fun merge(branchName: String): MergeResult = withContext(Dispatchers.IO) {
        val branchRef = repository.findRef(branchName) ?: throw IllegalArgumentException("Branch $branchName not found")
        git.merge().include(branchRef).call()
    }

    override suspend fun abortMerge(): Unit = withContext(Dispatchers.IO) {
        // Reset working tree and index to HEAD
        git.reset().setMode(ResetType.HARD).setRef(Constants.HEAD).call()
        
        // Explicitly clear merge-related files to exit the MERGING state
        repository.apply {
            writeMergeHeads(null)
            writeMergeCommitMsg(null)
            writeCherryPickHead(null)
            writeRevertHead(null)
            writeSquashCommitMsg(null)
        }
    }

    override fun close() {
        repository.close()
        git.close()
    }
}
