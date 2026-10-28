package com.itsaky.androidide.git.core

import com.itsaky.androidide.git.core.models.ChangeType
import com.itsaky.androidide.git.core.models.FileChange
import com.itsaky.androidide.git.core.models.GitBranch
import com.itsaky.androidide.git.core.models.GitCommit
import com.itsaky.androidide.git.core.models.GitStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.CheckoutCommand
import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand.ListMode
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.api.PullResult
import org.eclipse.jgit.api.ResetCommand.ResetType
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.dircache.DirCacheIterator
import org.eclipse.jgit.lib.BranchConfig
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.PushResult
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
class JGitRepository(
	override val rootDir: File,
) : GitRepository {
	companion object {
		private const val CONFIG_SECTION_COTG = "cotg"
		private const val CONFIG_KEY_WATERMARK = "commit-watermark"
	}

	private val log = LoggerFactory.getLogger(JGitRepository::class.java)
	private val configMutex = Mutex()

	private val repository: Repository =
		FileRepositoryBuilder()
			.setWorkTree(rootDir)
			.findGitDir(rootDir)
			.build()

	private val git: Git = Git(repository)

	override val gitDir: File
		get() = repository.directory ?: File(rootDir, ".git")

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

	override suspend fun getStatus(): GitStatus =
		withContext(Dispatchers.IO) {
			val jgitStatus = git.status().call()

			val staged = mutableListOf<FileChange>()
			val unstaged = mutableListOf<FileChange>()
			val untracked = mutableListOf<FileChange>()
			val conflicted = mutableListOf<FileChange>()

			// Track unique paths to avoid duplicates across categories
			// Priority: Conflicted > Staged > Unstaged > Untracked
			val processedPaths = mutableSetOf<String>()

			// 1. Conflicted (Highest Priority)
			jgitStatus.conflicting.forEach {
				if (processedPaths.add(it)) {
					conflicted.add(FileChange(it, ChangeType.CONFLICTED))
				}
			}

			// 2. Staged files (Added, Changed, Removed)
			val stagedAdded = jgitStatus.added.toMutableSet()
			val stagedRemoved = jgitStatus.removed.toMutableSet()
			val stagedRenames = mutableMapOf<String, String>()

			stagedAdded.forEach { addedPath ->
				val matchingRemoved =
					stagedRemoved.find { it.equals(addedPath, ignoreCase = true) && it != addedPath }
				if (matchingRemoved != null) {
					stagedRenames[addedPath] = matchingRemoved
				}
			}
			stagedRenames.forEach { (newPath, oldPath) ->
				stagedAdded.remove(newPath)
				stagedRemoved.remove(oldPath)
				if (processedPaths.add(newPath)) {
					processedPaths.add(oldPath)
					staged.add(FileChange(path = newPath, type = ChangeType.RENAMED, oldPath = oldPath))
				}
			}

			stagedAdded.forEach { if (processedPaths.add(it)) staged.add(FileChange(it, ChangeType.ADDED)) }
			jgitStatus.changed.forEach { if (processedPaths.add(it)) staged.add(FileChange(it, ChangeType.MODIFIED)) }
			stagedRemoved.forEach { if (processedPaths.add(it)) staged.add(FileChange(it, ChangeType.DELETED)) }

			// 3. Unstaged files (Modified, Missing) & Untracked
			val missingSet = jgitStatus.missing.toMutableSet()
			val untrackedSet = jgitStatus.untracked.toMutableSet()
			val unstagedRenames = mutableMapOf<String, String>()

			untrackedSet.forEach { untrackedPath ->
				val matchingMissing =
					missingSet.find { it.equals(untrackedPath, ignoreCase = true) && it != untrackedPath }
				if (matchingMissing != null) {
					unstagedRenames[untrackedPath] = matchingMissing
				}
			}
			unstagedRenames.forEach { (newPath, oldPath) ->
				untrackedSet.remove(newPath)
				missingSet.remove(oldPath)
				if (processedPaths.add(newPath)) {
					processedPaths.add(oldPath)
					unstaged.add(FileChange(path = newPath, type = ChangeType.RENAMED, oldPath = oldPath))
				}
			}

			// Filter out ghost missing entries where the file exists on disk with different casing
			// (e.g. after a case-only folder/file rename was committed externally or via terminal)
			if (missingSet.isNotEmpty()) {
				val dirCache = repository.readDirCache()
				val trackedPaths = (0 until dirCache.entryCount).map { dirCache.getEntry(it).pathString }

				missingSet.removeAll { missingPath ->
					val counterpart =
						trackedPaths.find {
							it.equals(missingPath, ignoreCase = true) && it != missingPath
						}
					if (counterpart != null && File(rootDir, counterpart).exists()) {
						true
					} else {
						val realCasing = findRealCasingOnDisk(rootDir, missingPath)
						realCasing != null && realCasing != missingPath
					}
				}
			}

			jgitStatus.modified.forEach { if (processedPaths.add(it)) unstaged.add(FileChange(it, ChangeType.MODIFIED)) }
			missingSet.forEach { if (processedPaths.add(it)) unstaged.add(FileChange(it, ChangeType.DELETED)) }

			// 4. Untracked files
			untrackedSet.forEach { if (processedPaths.add(it)) untracked.add(FileChange(it, ChangeType.UNTRACKED)) }

			val isMerging = repository.repositoryState == RepositoryState.MERGING
			val isClean = staged.isEmpty() && unstaged.isEmpty() && untracked.isEmpty() && conflicted.isEmpty()

			GitStatus(
				isClean = isClean,
				hasConflicts = conflicted.isNotEmpty(),
				isMerging = isMerging,
				staged = staged,
				unstaged = unstaged,
				untracked = untracked,
				conflicted = conflicted,
			)
		}

	override suspend fun getCurrentBranch(): GitBranch? =
		withContext(Dispatchers.IO) {
			val head = repository.fullBranch ?: return@withContext null
			val shortName = repository.branch ?: head
			GitBranch(
				name = shortName,
				fullName = head,
				isCurrent = true,
				isRemote = head.startsWith(Constants.R_REMOTES),
			)
		}

	override suspend fun getBranches(): List<GitBranch> =
		withContext(Dispatchers.IO) {
			val currentBranch = repository.fullBranch
			git
				.branchList()
				.setListMode(ListMode.ALL)
				.call()
				.filter { ref -> !ref.name.endsWith("/HEAD") && !ref.isSymbolic }
				.map { ref ->
					val isRemote = ref.name.startsWith(Constants.R_REMOTES)
					val shortName = Repository.shortenRefName(ref.name)
					val remoteName =
						if (isRemote) {
							shortName.substringBefore('/')
						} else {
							null
						}
					GitBranch(
						name = shortName,
						fullName = ref.name,
						isCurrent = ref.name == currentBranch,
						isRemote = isRemote,
						remoteName = remoteName,
					)
				}
		}

	override suspend fun getHistory(limit: Int): List<GitCommit> =
		withContext(Dispatchers.IO) {
			try {
				val branchName = repository.branch ?: return@withContext emptyList()
				val trackingBranch = BranchConfig(repository.config, branchName).trackingBranch

				RevWalk(repository).use { walk ->
					val remoteCommit =
						trackingBranch?.let { repository.resolve(it) }?.let {
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

	override suspend fun getDiff(file: File): String =
		withContext(Dispatchers.IO) {
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

	override suspend fun stageFiles(files: List<File>) =
		withContext(Dispatchers.IO) {
			val addCommand = git.add()
			val rmCommand = git.rm().setCached(true)
			var hasAdds = false
			var hasRms = false

			val status = git.status().call()
			val missingFiles = status.missing

			files.forEach { file ->
				val relativePath = file.toRelativeString(rootDir).replace('\\', '/')
				val isMissing =
					missingFiles.contains(relativePath) ||
						missingFiles.any { it.startsWith("$relativePath/") }
				if (isMissing || !file.exists()) {
					rmCommand.addFilepattern(relativePath)
					hasRms = true
				} else {
					// If this existing file corresponds to a case-only rename of a missing file, evict old casing
					val caseRenamedOldPath =
						missingFiles.find { it.equals(relativePath, ignoreCase = true) && it != relativePath }
					if (caseRenamedOldPath != null) {
						rmCommand.addFilepattern(caseRenamedOldPath)
						hasRms = true
					}
					addCommand.addFilepattern(relativePath)
					hasAdds = true
				}
			}
			if (hasRms) rmCommand.call()
			if (hasAdds) addCommand.call()
			Unit
		}

	override suspend fun renamePath(
		oldFile: File,
		newFile: File,
	): Boolean =
		withContext(Dispatchers.IO) {
			val oldRel = oldFile.toRelativeString(rootDir).replace('\\', '/')
			val newRel = newFile.toRelativeString(rootDir).replace('\\', '/')
			if (oldRel == newRel || oldRel.startsWith("..") || newRel.startsWith("..")) {
				return@withContext false
			}

			val dirCache = repository.readDirCache()
			val oldPrefix = "$oldRel/"
			val newPrefix = "$newRel/"

			val entriesToRemove = mutableSetOf<String>()
			for (i in 0 until dirCache.entryCount) {
				val path = dirCache.getEntry(i).pathString
				if (path == oldRel ||
					path.startsWith(oldPrefix) ||
					path.equals(oldRel, ignoreCase = true) ||
					path.startsWith(oldPrefix, ignoreCase = true) ||
					path.equals(newRel, ignoreCase = true) ||
					path.startsWith(newPrefix, ignoreCase = true)
				) {
					entriesToRemove.add(path)
				}
			}

			if (entriesToRemove.isEmpty()) {
				return@withContext false
			}

			val rmCommand = git.rm().setCached(true)
			entriesToRemove.forEach { rmCommand.addFilepattern(it) }
			rmCommand.call()

			if (newFile.exists()) {
				val addCommand = git.add()
				addCommand.addFilepattern(newRel)
				addCommand.call()
			}

			true
		}

	override suspend fun isCommitWatermarkEnabled(): Boolean =
		withContext(Dispatchers.IO) {
			configMutex.withLock {
				repository.config.load()
				repository.config.getBoolean(CONFIG_SECTION_COTG, null, CONFIG_KEY_WATERMARK, true)
			}
		}

	override suspend fun setCommitWatermarkEnabled(enabled: Boolean) =
		withContext(Dispatchers.IO) {
			configMutex.withLock {
				repository.config.load()
				repository.config.setBoolean(
					CONFIG_SECTION_COTG,
					null,
					CONFIG_KEY_WATERMARK,
					enabled,
				)
				repository.config.save()
			}
		}

	override suspend fun commit(
		message: String,
		authorName: String?,
		authorEmail: String?,
	): GitCommit? =
		withContext(Dispatchers.IO) {
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
			hasBeenPushed = hasBeenPushed,
		)
	}

	override suspend fun push(
		remote: String,
		credentialsProvider: CredentialsProvider?,
		progressMonitor: ProgressMonitor?,
	): Iterable<PushResult> =
		withContext(Dispatchers.IO) {
			val pushCommand = git.push().setRemote(remote)

			if (credentialsProvider != null) {
				pushCommand.setCredentialsProvider(credentialsProvider)
			}

			if (progressMonitor != null) {
				pushCommand.setProgressMonitor(progressMonitor)
			}

			pushCommand.call()
		}

	override suspend fun getLocalCommitsCount(): Int =
		withContext(Dispatchers.IO) {
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
		progressMonitor: ProgressMonitor?,
	): PullResult =
		withContext(Dispatchers.IO) {
			val pullCommand = git.pull().setRemote(remote)

			if (credentialsProvider != null) {
				pullCommand.setCredentialsProvider(credentialsProvider)
			}

			if (progressMonitor != null) {
				pullCommand.setProgressMonitor(progressMonitor)
			}

			pullCommand.call()
		}

	override suspend fun merge(branchName: String): MergeResult =
		withContext(Dispatchers.IO) {
			val branchRef = repository.findRef(branchName) ?: throw IllegalArgumentException("Branch $branchName not found")
			git.merge().include(branchRef).call()
		}

	override suspend fun abortMerge(): Unit =
		withContext(Dispatchers.IO) {
			// Reset working tree and index to HEAD
			git
				.reset()
				.setMode(ResetType.HARD)
				.setRef(Constants.HEAD)
				.call()

			// Explicitly clear merge-related files to exit the MERGING state
			repository.apply {
				writeMergeHeads(null)
				writeMergeCommitMsg(null)
				writeCherryPickHead(null)
				writeRevertHead(null)
				writeSquashCommitMsg(null)
			}
		}

	override suspend fun checkout(
		branchName: String,
		createNew: Boolean,
		startPoint: String?,
	): String =
		withContext(Dispatchers.IO) {
			val command = git.checkout()

			if (createNew) {
				configureNewBranchCheckout(command, branchName, startPoint)
			} else {
				configureExistingBranchCheckout(command, branchName)
			}

			command.call()
			Repository.shortenRefName(repository.fullBranch)
		}

	private fun configureNewBranchCheckout(
		command: CheckoutCommand,
		branchName: String,
		startPoint: String?,
	) {
		command
			.setCreateBranch(true)
			.setName(branchName)

		if (!startPoint.isNullOrBlank()) {
			command.setStartPoint(startPoint)
		}
	}

	private fun configureExistingBranchCheckout(
		command: CheckoutCommand,
		branchName: String,
	) {
		val isExistingLocal = repository.findRef("${Constants.R_HEADS}$branchName") != null
		val remoteRef = if (isExistingLocal) null else resolveRemoteRef(branchName)

		if (remoteRef == null) {
			command.setName(branchName)
			return
		}

		when (val resolution = resolveLocalBranch(remoteRef)) {
			is LocalBranch -> {
				command.setName(resolution.name)
			}

			is NewBranch -> {
				configureNewTrackingBranch(
					command,
					resolution.name,
					remoteRef,
				)
			}
		}
	}

	private fun resolveRemoteRef(branchName: String): String? {
		if (branchName.startsWith(Constants.R_REMOTES)) {
			return branchName
		}

		return "${Constants.R_REMOTES}$branchName"
			.takeIf { repository.findRef(it) != null }
	}

	private fun resolveLocalBranch(remoteRef: String): BranchResolution {
		val shortRemoteName = Repository.shortenRefName(remoteRef)
		val localName = shortRemoteName.substringAfter('/')

		repository.findRef("${Constants.R_HEADS}$localName") ?: return NewBranch(localName)
		if (isTrackingRemote(localName, remoteRef, shortRemoteName)) {
			return LocalBranch(localName)
		}

		val scopedName = shortRemoteName.replace('/', '-')
		repository.findRef("${Constants.R_HEADS}$scopedName") ?: return NewBranch(scopedName)
		if (isTrackingRemote(scopedName, remoteRef, shortRemoteName)) {
			return LocalBranch(scopedName)
		}

		return NewBranch(
			findAvailableBranchName(
				scopedName,
				remoteRef,
				shortRemoteName,
			),
		)
	}

	private fun isTrackingRemote(
		name: String,
		remoteRef: String,
		shortRemoteName: String,
	): Boolean {
		val trackingBranch = BranchConfig(repository.config, name).trackingBranch

		return trackingBranch == null ||
			trackingBranch == remoteRef ||
			trackingBranch == shortRemoteName
	}

	private fun findAvailableBranchName(
		baseName: String,
		remoteRef: String,
		shortRemoteName: String,
	): String {
		var suffix = 1
		var candidate = "$baseName-$suffix"

		while (repository.findRef("${Constants.R_HEADS}$candidate") != null) {
			if (isTrackingRemote(candidate, remoteRef, shortRemoteName)) {
				return candidate
			}

			suffix++
			candidate = "$baseName-$suffix"
		}

		return candidate
	}

	private fun configureNewTrackingBranch(
		command: CheckoutCommand,
		name: String,
		remoteRef: String,
	) {
		command
			.setCreateBranch(true)
			.setName(name)
			.setStartPoint(remoteRef)
			.setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
	}

	private sealed interface BranchResolution

	private data class LocalBranch(
		val name: String,
	) : BranchResolution

	private data class NewBranch(
		val name: String,
	) : BranchResolution

	internal fun findRealCasingOnDisk(
		rootDir: File,
		relativePath: String,
	): String? {
		val segments = relativePath.split('/').filter { it.isNotEmpty() }
		if (segments.isEmpty()) return null

		var current = rootDir
		val realSegments = ArrayList<String>(segments.size)

		for (segment in segments) {
			val direct = File(current, segment)
			if (direct.exists()) {
				val children = current.list()
				val exactOnDisk = children?.find { it == segment }
				if (exactOnDisk != null) {
					realSegments.add(exactOnDisk)
					current = direct
					continue
				}
				val caseMatch = children?.find { it.equals(segment, ignoreCase = true) }
				if (caseMatch != null) {
					realSegments.add(caseMatch)
					current = File(current, caseMatch)
					continue
				}
			}

			val children = current.list() ?: return null
			val caseMatch = children.find { it.equals(segment, ignoreCase = true) } ?: return null
			realSegments.add(caseMatch)
			current = File(current, caseMatch)
		}

		return if (current.isFile) realSegments.joinToString("/") else null
	}

	override fun close() {
		repository.close()
		git.close()
	}
}
