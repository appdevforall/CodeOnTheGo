package com.itsaky.androidide.lsp.kotlin.compiler.index

import androidx.annotation.VisibleForTesting
import com.github.benmanes.caffeine.cache.Caffeine
import com.itsaky.androidide.lsp.kotlin.compiler.CompilationKind
import com.itsaky.androidide.lsp.kotlin.compiler.modules.AnalysisPriority
import com.itsaky.androidide.lsp.kotlin.compiler.modules.AnalysisScheduler
import com.itsaky.androidide.lsp.kotlin.compiler.modules.KtModule
import com.itsaky.androidide.lsp.kotlin.compiler.modules.ScheduledCancelChecker
import com.itsaky.androidide.lsp.kotlin.compiler.modules.UnpinnedAnalysis
import com.itsaky.androidide.lsp.kotlin.compiler.modules.analyzeMaybeDangling
import com.itsaky.androidide.lsp.kotlin.compiler.modules.backingFilePath
import com.itsaky.androidide.lsp.kotlin.compiler.read
import com.itsaky.androidide.lsp.kotlin.compiler.services.ProjectStructureProvider
import com.itsaky.androidide.lsp.kotlin.compiler.write
import com.itsaky.androidide.lsp.kotlin.compiler.writeUnlessDisposed
import com.itsaky.androidide.lsp.kotlin.utils.toNioPathOrNull
import com.itsaky.androidide.lsp.kotlin.utils.toVirtualFileOrNull
import com.itsaky.androidide.projects.FileManager
import com.itsaky.androidide.utils.DocumentUtils
import io.sentry.Sentry
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolIndex
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolKind
import org.appdevforall.codeonthego.indexing.jvm.KtFileMetadataIndex
import org.checkerframework.checker.index.qual.NonNegative
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.platform.modification.KaElementModificationType
import org.jetbrains.kotlin.analysis.api.platform.modification.KaSourceModificationService
import org.jetbrains.kotlin.analysis.low.level.api.fir.LLFirInternals
import org.jetbrains.kotlin.analysis.low.level.api.fir.file.structure.LLFirDeclarationModificationService
import org.jetbrains.kotlin.analysis.low.level.api.fir.util.originalKtFile
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiManagerEx
import org.jetbrains.kotlin.com.intellij.psi.impl.file.impl.FileManagerImpl
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.StampedLock
import kotlin.io.path.pathString

/**
 * An index of symbols from Kotlin source files and JARs.
 *
 * NOTE: This index does not own the provided [fileIndex], [sourceIndex] and [libraryIndex].
 * Callers are responsible for closing the provided indexes.
 */
internal class KtSymbolIndex(
	val kind: CompilationKind,
	val project: Project,
	modules: List<KtModule>,
	val fileIndex: KtFileMetadataIndex,
	val sourceIndex: JvmSymbolIndex,
	val libraryIndex: JvmSymbolIndex,
	cacheSize: @NonNegative Long = DEFAULT_CACHE_SIZE,
	private val scope: CoroutineScope =
		CoroutineScope(
			Dispatchers.Default + SupervisorJob() +
				CoroutineName(
					"KtSymbolIndex",
				),
		),
) {
	companion object {
		private val logger = LoggerFactory.getLogger(KtSymbolIndex::class.java)
		const val DEFAULT_CACHE_SIZE = 100L
		private const val CLOSE_DRAIN_TIMEOUT_SECONDS = 5L

		/** Pin version stamp for a path with no open document, which no real version can equal. */
		private const val NO_DOCUMENT_VERSION = -1
	}

	private val workerQueue = WorkerQueue<IndexCommand>()

	@VisibleForTesting
	internal val indexWorker =
		IndexWorker(
			project = project,
			queue = workerQueue,
			fileIndex = fileIndex,
			sourceIndex = sourceIndex,
			scope = scope,
		)

	private val scanningWorker =
		ScanningWorker(
			kind = kind,
			sourceIndex = sourceIndex,
			indexWorker = indexWorker,
			modules = modules,
		)

	private var scanningJob: Job? = null
	private var indexingJob: Job? = null

	private val ktFileCache =
		Caffeine
			.newBuilder()
			.maximumSize(cacheSize)
			.build<Path, KtFile>()

	private val packageFiles =
		PackageFileCache(
			maxCachedFiles = PackageFileCache.DEFAULT_MAX_CACHED_FILES,
			pathOf = { it.toNioPathOrNull()?.pathString.orEmpty() },
			query = ::findSourceFiles,
		)

	private val packageFilesRegistration: AutoCloseable = fileIndex.addWriteListener(packageFiles)

	/** Set by AbstractCompilationEnvironment.initialize once the env's KtPsiFactory exists. */
	lateinit var parser: KtPsiFactory

	private data class VersionedKtFile(
		val version: Int,
		val ktFile: KtFile,
	)

	private val refreshExecutor: ExecutorService =
		Executors.newFixedThreadPool(2) { r -> Thread(r, "KtCurrentFileRefresh").apply { isDaemon = true } }

	/** path -> in-flight/last-launched refresh; mutated only inside the per-key `compute` below. */
	private val currentFiles = ConcurrentHashMap<Path, CompletableFuture<VersionedKtFile>>()

	/** path -> last-launched version; read/written only inside that same `compute` section. */
	private val currentVersions = ConcurrentHashMap<Path, Int>()

	/** path -> the one [KtFile] an open document keeps across versions, edited in place. */
	private val documents = ConcurrentHashMap<Path, IncrementalKtDocument>()

	/** The in-place document held for [path], if it is open and has been parsed. */
	@VisibleForTesting
	internal fun incrementalDocument(path: Path): IncrementalKtDocument? = documents[path]

	/**
	 * path -> the instance pinned for the duration of one or more open [LiveKtFile] scopes.
	 *
	 * A pinned path is frozen: [getCurrentKtFile] hands back the pinned instance rather than minting a
	 * new one for a newer document version, and [getKtFile] resolves to it too, so an analysis and the
	 * declaration provider cannot disagree about which instance is the file. The refresh a version bump
	 * would have triggered is recorded and launched when the last scope closes.
	 *
	 * That deferral is best-effort, not a guarantee: [getCurrentKtFile] reads the pin outside the map's
	 * atomic section, so a bump observed exactly as the last scope releases can be recorded on an entry
	 * that has already been removed, and lost. It self-heals - [currentVersions] still holds the older
	 * version, so the next request for the path refreshes.
	 */
	private val pins = ConcurrentHashMap<Path, Pin>()

	/**
	 * @property stamp The read stamp held on [editLock] for the pin's lifetime, so no in-place edit
	 * changes [file] under it; `0L` with no lock for a disk file.
	 */
	private class Pin(
		val file: KtFile,
		val version: Int,
		val editLock: StampedLock?,
		val stamp: Long,
	) {
		var count: Int = 0

		/** Written outside the map's `compute` section, by whichever thread observes the version bump. */
		@Volatile
		var refreshOwed: Boolean = false
	}

	fun syncIndexInBackground() {
		indexingJob?.cancel()
		startIndexing()

		scanningJob?.cancel()
		startScanning()
	}

	private fun startIndexing() {
		val job =
			scope.launch {
				try {
					indexWorker.start()
				} finally {
					if (indexingJob === coroutineContext[Job]) {
						indexingJob = null
					}
				}
			}

		indexingJob = job
	}

	private fun startScanning() {
		scanningJob =
			scope.launch {
				try {
					scanningWorker.scan()
				} finally {
					if (scanningJob === coroutineContext[Job]) {
						scanningJob = null
					}
				}
			}
	}

	fun refreshSources() {
		Sentry.addBreadcrumb("KtSymbolIndex.refreshSources()")
		indexingJob ?: startIndexing()

		scanningJob?.cancel()
		startScanning()
	}

	private fun getVirtualFileOrWarn(path: Path): VirtualFile? =
		path.toVirtualFileOrNull() ?: run {
			logger.warn("unable to find virtual file for path {}", path)
			null
		}

	suspend fun submitForIndexing(path: Path) {
		val vf = getVirtualFileOrWarn(path) ?: return
		indexWorker.apply {
			submitCommand(IndexCommand.ScanSourceFile(vf))
			submitCommand(IndexCommand.IndexSourceFile(vf))
		}
	}

	suspend fun removeFromIndex(path: Path) {
		ktFileCache.invalidate(path)
		indexWorker.submitCommand(IndexCommand.RemoveFromIndex(path))
	}

	fun queueOnFileChangedAsync(ktFile: KtFile) {
		scope.launch {
			queueOnFileChanged(ktFile)
		}
	}

	suspend fun queueOnFileChanged(ktFile: KtFile) {
		indexWorker.submitCommand(IndexCommand.IndexModifiedFile(ktFile))
	}

	/**
	 * Returns the canonical [KtFile] for [path] at the current document version, parsing (once) on a
	 * version miss. For non-open paths (no active document) falls back to the disk [getKtFile].
	 * Single-flight: concurrent callers at the same version share one parse.
	 */
	private fun getCurrentKtFile(path: Path): CompletableFuture<KtFile?> =
		getCurrentVersionedKtFile(path)?.thenApply { it.ktFile } ?: CompletableFuture.completedFuture(null)

	/**
	 * [getCurrentKtFile] with the document version the instance was parsed from, or `null` if [path]
	 * has no Kotlin PSI at all.
	 *
	 * Pin acquisition needs the version *of the resolved instance*, not the one the document happens
	 * to be at once the parse finishes - re-reading [FileManager] after a blocking resolve stamps a
	 * pin with a version its PSI does not have, which makes [LiveKtFile.isStale] claim a superseded
	 * instance is current.
	 */
	@OptIn(ResolutionSideKtFileAccess::class)
	private fun getCurrentVersionedKtFile(path: Path): CompletableFuture<VersionedKtFile>? {
		if (!DocumentUtils.isKotlinFile(path)) return null

		pins[path]?.let { pin ->
			val current = FileManager.getActiveDocument(path)?.version
			if (current != null && current != pin.version) {
				pin.refreshOwed = true
			}
			return CompletableFuture.completedFuture(VersionedKtFile(pin.version, pin.file))
		}

		val doc =
			FileManager.getActiveDocument(path)
				?: return getKtFile(path)?.let {
					// not open -> disk path
					CompletableFuture.completedFuture(VersionedKtFile(NO_DOCUMENT_VERSION, it))
				}

		val version = doc.version
		val future =
			currentFiles.compute(path) { p, existing ->
				// The failure check runs last so a refresh that fails while the others are evaluated is relaunched.
				val reusable =
					existing != null &&
						currentVersions[p] == version &&
						!existing.isDeferredBelow(version) &&
						!existing.isCompletedExceptionally
				if (reusable) {
					existing
				} else {
					currentVersions[p] = version
					val prior = existing
					CompletableFuture.supplyAsync({
						// Serialize with any prior refresh for this path so old->new succession is linear.
						val old =
							try {
								prior?.get()?.ktFile
							} catch (_: Throwable) {
								null
							}
						refreshToCurrent(p, version, old)
					}, refreshExecutor)
				}
			}!!
		return future
	}

	/**
	 * A refresh that found its document locked by a pin settles at the older applied version; it must
	 * not count as the refresh for the version it was launched for. A failed refresh has no value, so
	 * it does not count as deferred; the caller's own failure check relaunches it.
	 */
	private fun CompletableFuture<VersionedKtFile>.isDeferredBelow(version: Int): Boolean =
		valueNowOrNull()?.let { it.version != version } ?: false

	/**
	 * Brings [path]'s [IncrementalKtDocument] to the live document and transitions the module's FIR
	 * session (invalidate + reindex) so later analysis sees the content.
	 *
	 * The text is applied to the document's one [KtFile] in place. A path with no document yet, or an
	 * edit that cannot be applied in place, takes a full parse into a fresh [KtFile] instead. A pinned
	 * document is not edited: the edit is owed to the pin's release and the applied version returned.
	 *
	 * The result is stamped with [version] (captured when the refresh was launched) even though the
	 * content is read later, here. FileManager writes version-before-content unsynchronized, so the
	 * stamp may lag the content but never lead it: callers never get older-than-requested content, and
	 * a lagging stamp only costs one redundant refresh on the next request.
	 */
	private fun refreshToCurrent(
		path: Path,
		version: Int,
		old: KtFile?,
	): VersionedKtFile {
		// A document whose file is not the one this refresh succeeds was left behind by a close racing a
		// refresh; editing it in place would skip the in-memory registration the close undid.
		val doc =
			documents[path]?.takeIf { old != null && it.ktFile === old }
				?: return parseIntoNewDocument(path, version, old)
		val stamp = doc.editLock.tryWriteLock()
		if (stamp == 0L) {
			// Never wait for the pin: it can be held across a whole analysis. Its release refreshes again.
			pins[path]?.refreshOwed = true
			return VersionedKtFile(doc.appliedVersion, doc.ktFile)
		}
		try {
			val content = FileManager.getDocumentContents(path)
			if (doc.advanceIfUnchanged(content, version)) {
				return VersionedKtFile(version, doc.ktFile)
			}
			val outcome = applyInPlace(doc, content, version)
			if (outcome is ReparseOutcome.Failed) {
				logger.warn("in-place reparse of {} failed, parsing in full: {}", path, outcome.reason, outcome.cause)
				return parseIntoNewDocument(path, version, doc.ktFile)
			}
			return VersionedKtFile(version, doc.ktFile)
		} finally {
			doc.editLock.unlockWrite(stamp)
		}
	}

	private fun applyInPlace(
		doc: IncrementalKtDocument,
		content: CharSequence,
		version: Int,
	): ReparseOutcome =
		AnalysisScheduler.preemptingAnalysis {
			project.write {
				val outcome =
					ApplicationManager.getApplication().runWriteAction<ReparseOutcome> {
						doc.apply(content, version).also { reportModification(doc.ktFile, it) }
					}
				if (outcome.needsReindex()) {
					queueOnFileChangedAsync(doc.ktFile)
				}
				outcome
			}
		}

	/** Reports [outcome]'s change to FIR; caller runs inside runWriteAction. */
	@OptIn(LLFirInternals::class)
	private fun reportModification(
		ktFile: KtFile,
		outcome: ReparseOutcome,
	) {
		val modificationService = KaSourceModificationService.getInstance(project)
		when (outcome) {
			is ReparseOutcome.InBlock -> {
				// `reparsed` is the old, still-attached node; the detached new root would be classified
				// out-of-block against the wrong module.
				modificationService.handleElementModification(outcome.reparsed, KaElementModificationType.Unknown)
				// In-block changes are only queued; the platform's flush on writeActionFinished never fires
				// because MockApplication.runWriteAction notifies no listeners.
				LLFirDeclarationModificationService.getInstance(project).flushModifications()
			}

			ReparseOutcome.WholeFile -> {
				modificationService.handleElementModification(ktFile, KaElementModificationType.Unknown)
			}

			ReparseOutcome.Unchanged, is ReparseOutcome.Failed -> {}
		}
	}

	/** SourceFileIndexer records local classes and objects, so only an in-block edit adding or removing one needs a reindex. */
	private fun ReparseOutcome.needsReindex(): Boolean =
		when (this) {
			is ReparseOutcome.InBlock -> {
				replacedHadClassOrObject || PsiTreeUtil.findChildOfType(reparsed, KtClassOrObject::class.java) != null
			}

			ReparseOutcome.WholeFile -> {
				true
			}

			ReparseOutcome.Unchanged, is ReparseOutcome.Failed -> {
				false
			}
		}

	/**
	 * Parses [path]'s live document into a fresh [KtFile], registers it as the in-memory file, makes it
	 * the path's [IncrementalKtDocument], and reports [old] (or the new file) modified.
	 */
	private fun parseIntoNewDocument(
		path: Path,
		version: Int,
		old: KtFile?,
	): VersionedKtFile {
		val content = FileManager.getDocumentContents(path)
		val newKtFile = project.read { parser.createFile(path.pathString, content) }
		newKtFile.backingFilePath = path
		// KtFile.virtualFile is null for non-physical PSI (unit-test env); the view provider's is
		// always present, and identical to it in production.
		ProjectStructureProvider
			.getInstance(project)
			.registerInMemoryFile(path.pathString, newKtFile.viewProvider.virtualFile)
		// project.write serializes the mutation against a concurrent `analyze` (read lock); runWriteAction
		// supplies the platform write access handleElementModification asserts, which our RW lock does not.
		// The barrier preempts running analyses: completion waits on this refresh before it reaches the
		// scheduler, so without it the keystroke waits out a whole indexing analysis.
		AnalysisScheduler.preemptingAnalysis {
			project.write {
				ApplicationManager.getApplication().runWriteAction {
					KaSourceModificationService
						.getInstance(project)
						.handleElementModification(old ?: newKtFile, KaElementModificationType.Unknown)
				}
				queueOnFileChangedAsync(newKtFile)
			}
		}
		// A close during the parse already ran invalidateCurrent; a document stored now would outlive it.
		if (FileManager.isActive(path)) {
			documents[path] = IncrementalKtDocument(newKtFile, version)
		}
		return VersionedKtFile(version, newKtFile)
	}

	/** Whether the disk copy of [path] is held in the parsed-file cache. */
	@VisibleForTesting
	internal fun isDiskCopyCached(path: Path): Boolean = ktFileCache.getIfPresent(path) != null

	fun evictDiskCopy(path: Path) {
		ktFileCache.invalidate(path)
		if (project.isDisposed) return
		val vf = path.toVirtualFileOrNull() ?: return
		project.writeUnlessDisposed {
			ApplicationManager.getApplication().runWriteAction {
				(PsiManagerEx.getInstanceEx(project).fileManager as FileManagerImpl).setViewProvider(vf, null)
			}
		}
	}

	/** Drops the cached current file for [path] (e.g. on close). */
	fun invalidateCurrent(path: Path) {
		documents.remove(path)
		currentFiles.remove(path)
		currentVersions.remove(path)
		ProjectStructureProvider.getInstance(project).unregisterInMemoryFile(path.pathString)
	}

	/**
	 * Non-blocking: the current cached instance for [path] if a refresh has already completed,
	 * else `null`. Safe to call while holding `project.read` (unlike [getCurrentKtFile], which may
	 * trigger a blocking refresh that needs `project.write`).
	 */
	private fun getCurrentKtFileIfPresent(path: Path): KtFile? = currentFiles[path]?.getNow(null)?.ktFile

	/**
	 * Runs [block] with the file at [path] pinned, or returns `null` if the path has no Kotlin PSI.
	 *
	 * Blocking: resolves the current instance before pinning, and that refresh needs `project.write`.
	 * Never call this while holding `project.read` - it deadlocks. Acquire the scope first, then use
	 * [LiveKtFile.read] / [LiveKtFile.analyzing] inside it, which take the read lock for you.
	 *
	 * The pin is process-wide, not per-caller: while any scope on [path] is open, *every* request for
	 * that path joins it and sees the same instance and the same text, including requests from unrelated
	 * features. So a scope's duration is a staleness window for everyone else - a caller that joins a
	 * long-running scope can get text older than the buffer the user is looking at. Any site whose
	 * output is an edit, or that indexes into the text with coordinates from its own request, must
	 * therefore check [LiveKtFile.isStale] and degrade rather than compute against frozen text.
	 *
	 * Known gap: the instance is resolved *before* the pin is installed, so a request arriving in that
	 * window sees no pin and can launch a refresh. An in-place refresh cannot land inside this scope:
	 * the pin holds the document's edit read stamp, so the edit is deferred to the release; so is the
	 * full-parse fallback of a failed in-place reparse, which runs under the write stamp. Only a full
	 * parse with no document to stamp (the path's first parse) can complete inside the scope, firing
	 * `registerInMemoryFile` and a FIR modification event underneath it. Instance identity still holds - every door answers with the pinned instance for the whole
	 * scope - and the pin is stamped with the locked document's `appliedVersion`, so the bump is not
	 * lost. Closing the window entirely would mean publishing a pin before its file exists, making
	 * joiners wait on an unresolved entry inside the one path every caller depends on; that deadlock
	 * risk is worse than the window.
	 */
	fun <R> withLiveKtFile(
		path: Path,
		block: (LiveKtFile) -> R,
	): R? {
		val pin = acquirePin(path) { getCurrentVersionedKtFile(path)?.get() } ?: return null
		try {
			return block(PinnedKtFile(path, pin))
		} finally {
			releasePin(path)
		}
	}

	/**
	 * Suspending [withLiveKtFile], for callers that must not block a dispatcher thread on a refresh.
	 *
	 * It still blocks briefly once the refresh is done: installing the pin waits in the document's edit
	 * read lock while an in-place edit is being applied.
	 */
	suspend fun <R> withLiveKtFileAsync(
		path: Path,
		block: (LiveKtFile) -> R,
	): R? {
		val pin = acquirePinAsync(path) ?: return null
		try {
			return block(PinnedKtFile(path, pin))
		} finally {
			releasePin(path)
		}
	}

	/**
	 * Pulls [path] through the current-file cache so a refresh (and its reindex) happens, without
	 * handing the instance to the caller.
	 *
	 * This is the door for callers that want the refresh side effect only, so wanting a refresh never
	 * becomes a reason to hold a live instance.
	 */
	suspend fun refreshCurrentKtFile(path: Path) {
		getCurrentKtFile(path).await()
	}

	/**
	 * The current instance for [path] with no pin, or `null` if none is cached.
	 *
	 * Non-blocking and PSI-only. See [UnpinnedKtFileAccess] for why this is opt-in. The instance is
	 * edited in place by later refreshes, so read it only inside `project.read`.
	 */
	@UnpinnedKtFileAccess
	fun peekLiveKtFile(path: Path): KtFile? = getCurrentKtFileIfPresent(path)

	private inline fun acquirePin(
		path: Path,
		resolve: () -> VersionedKtFile?,
	): Pin? {
		joinExistingPin(path)?.let { return it }
		// Resolved outside the map mutation: it can block on a refresh, and holding a ConcurrentHashMap
		// bin lock across that would stall every other path.
		val resolved = resolve() ?: return null
		return installPin(path, resolved)
	}

	private suspend fun acquirePinAsync(path: Path): Pin? {
		joinExistingPin(path)?.let { return it }
		val resolved = getCurrentVersionedKtFile(path)?.await() ?: return null
		return installPin(path, resolved)
	}

	private fun joinExistingPin(path: Path): Pin? = pins.compute(path) { _, existing -> existing?.also { it.count++ } }

	private fun installPin(
		path: Path,
		resolved: VersionedKtFile,
	): Pin {
		val locked = if (resolved.version == NO_DOCUMENT_VERSION) null else lockCurrentDocument(path)
		// The document's file is pinned rather than the resolved one: an in-place edit or a fallback may
		// have moved on since the resolve, and the stamp only guards the document's own file.
		val candidate =
			locked?.let { (doc, stamp) -> Pin(doc.ktFile, doc.appliedVersion, doc.editLock, stamp) }
				?: Pin(resolved.ktFile, resolved.version, null, 0L)
		var joined = false
		val pin =
			pins.compute(path) { _, existing ->
				// A concurrent acquirer may have won the race; join its pin and let this file go.
				existing?.also {
					it.count++
					joined = true
				} ?: candidate.also { it.count = 1 }
			}!!
		if (joined) {
			candidate.unlockStamp()
		}

		/*
		 * The document can move on while the resolve is still parsing, so the pinned instance may already
		 * be behind by the time it is installed. That bump has no pinned instance left to refresh into,
		 * hence record it as owed here rather than let it fall between the resolve and the pin. Safe to
		 * write outside the section above: this thread holds a count, so no release can be reading it.
		 */
		val current = FileManager.getActiveDocument(path)?.version
		if (current != null && current != pin.version) {
			pin.refreshOwed = true
		}
		return pin
	}

	/**
	 * Read-locks [path]'s current document, or returns `null` if it has none (a disk file).
	 *
	 * The stamp is taken before the pin is published, so it may wait out an in-flight edit; that edit
	 * can replace the document with a full parse, hence the re-check after the lock.
	 */
	private fun lockCurrentDocument(path: Path): Pair<IncrementalKtDocument, Long>? {
		while (true) {
			val doc = documents[path] ?: return null
			val stamp = doc.editLock.readLock()
			if (documents[path] === doc) return doc to stamp
			doc.editLock.unlockRead(stamp)
		}
	}

	private fun Pin.unlockStamp() {
		editLock?.unlockRead(stamp)
	}

	private fun releasePin(path: Path) {
		var released: Pin? = null
		pins.compute(path) { _, pin ->
			if (pin == null) return@compute null
			if (--pin.count > 0) return@compute pin
			released = pin
			null
		}
		val refreshOwed = released?.refreshOwed ?: false
		// Unlocked before the owed refresh below is launched, so that refresh can take the write stamp.
		released?.unlockStamp()

		/*
		 * Applied on the way out rather than during the pin: the version bump that arrived while the path
		 * was frozen still has to reach the FIR session. Skipped once the document is gone, since
		 * invalidateCurrent already unregistered it.
		 */
		if (refreshOwed && FileManager.isActive(path)) {
			scope.launch { refreshCurrentKtFile(path) }
		}
	}

	private inner class PinnedKtFile(
		override val path: Path,
		private val pin: Pin,
	) : LiveKtFile {
		override val isStale: Boolean
			get() {
				val current = FileManager.getActiveDocument(path)?.version ?: return false
				return current != pin.version
			}

		override fun <R> read(block: (KtFile) -> R): R = project.read { guarded(block(pin.file)) }

		@OptIn(UnpinnedAnalysis::class)
		override fun <R> analyzing(
			priority: AnalysisPriority,
			cancelChecker: ScheduledCancelChecker,
			useSite: KtElement?,
			block: KaSession.(KtFile) -> R,
		): R =
			project.read {
				guarded(
					analyzeMaybeDangling(useSite ?: pin.file, priority, cancelChecker) { block(pin.file) },
				)
			}

		@OptIn(UnpinnedAnalysis::class)
		override fun <R> analyzingVariant(
			name: String,
			text: String,
			priority: AnalysisPriority,
			cancelChecker: ScheduledCancelChecker,
			block: KaSession.(KtFile) -> R,
		): R {
			val variant =
				project.read {
					parser.createFile(fileName = name, text = text).apply {
						originalFile = pin.file
						originalKtFile = pin.file
					}
				}
			// No guard here: the block only ever sees the variant, so it cannot return the pinned file.
			return project.read {
				analyzeMaybeDangling(variant, priority, cancelChecker) { block(variant) }
			}
		}

		/** Catches `read { it }`: returning the pinned file outlives the pin that made it safe to use. */
		private fun <R> guarded(result: R): R {
			check(result !== pin.file) {
				"The pinned KtFile for $path must not escape its LiveKtFile scope."
			}
			return result
		}
	}

	/** Returns the source files the metadata index lists for [packageFqName]. */
	internal fun sourceFilesForPackage(packageFqName: FqName): List<VirtualFile> = packageFiles.filesFor(packageFqName.asString())

	private fun findSourceFiles(packageFqName: String): List<VirtualFile> =
		fileIndex
			.getFilePathsForPackage(packageFqName)
			.mapNotNull { Paths.get(it).toVirtualFileOrNull() }
			.toList()

	/** [getKtFile] for [vf], keyed by the path it maps to. */
	@ResolutionSideKtFileAccess
	internal fun getKtFile(vf: VirtualFile): KtFile? = getKtFile(vf.toNioPath(), vf)

	/**
	 * The resolution-side door: what the Analysis API service providers answer a path lookup with.
	 *
	 * A pinned path resolves to the pinned instance, so an open analysis and the declaration provider
	 * cannot disagree about which instance is the file. Otherwise the live cache is peeked, then the
	 * on-disk instance is loaded. See [ResolutionSideKtFileAccess] for why this is opt-in.
	 */
	@ResolutionSideKtFileAccess
	internal fun getKtFile(
		path: Path,
		virtualFile: VirtualFile? = null,
	): KtFile? {
		if (!DocumentUtils.isKotlinFile(path)) return null

		// A pinned path resolves to the pinned instance for every door, which is the whole point of the
		// pin: this is the branch the Analysis API declaration providers take while an analysis is open.
		pins[path]?.let { return it.file }

		if (FileManager.isActive(path)) {
			/*
			 * Peek, never block: getKtFile runs under project.read inside Analysis-API services, so a
			 * blocking refresh (which needs project.write) would deadlock. A miss falls through to the disk
			 * instance; the edit already scheduled a refresh for next time.
			 */
			getCurrentKtFileIfPresent(path)?.let { return it }
		}

		ktFileCache.getIfPresent(path)?.also {
			return it
		}

		var file = virtualFile
		if (file == null) {
			file = path.toVirtualFileOrNull()
		}

		if (file == null) {
			return null
		}

		val ktFile = loadKtFile(file)
		ktFileCache.put(path, ktFile)
		return ktFile
	}

	private fun loadKtFile(vf: VirtualFile): KtFile =
		project.read {
			PsiManager
				.getInstance(project)
				.findFile(vf) as KtFile
		}

	suspend fun close() {
		packageFilesRegistration.close()

		/*
		 * Cancel the worker rather than queueing a stop: a stop waits behind every queued file, and
		 * a large pass outlasts the caller's bounded wait, so disposal would proceed under a live worker.
		 */
		scanningJob?.cancelAndJoin()
		indexingJob?.cancelAndJoin()

		/*
		 * Cancel AND JOIN the index's own scope. Beyond the main worker loop drained above, the
		 * debounced modifiedFileIndexer and queueOnFileChangedAsync coroutines also run
		 * project.read { PsiManager ... }. Joining guarantees none survive into the caller's
		 * Disposer.dispose(...), which would otherwise crash with "Project is already disposed" once
		 * one of them touches the project after it is gone. This index owns `scope`.
		 */
		scope.coroutineContext[Job]?.cancelAndJoin()

		// Drain the refresh pool before disposal: refreshToCurrent runs project.read/write on it (same
		// rationale as the scope join above). Bounded so a slow parse can't stall shutdown.
		refreshExecutor.shutdownNow()
		refreshExecutor.awaitTermination(CLOSE_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
	}
}

internal fun KtSymbolIndex.packageExistsInSource(packageFqn: String) = fileIndex.packageExists(packageFqn)

internal fun KtSymbolIndex.subpackageNames(packageFqn: String) = fileIndex.getSubpackageNames(packageFqn)

/**
 * Returns source- and library-index symbols whose simple name equals [name].
 *
 * [limit] `<= 0` means unbounded, honoring the same convention as
 * [org.appdevforall.codeonthego.indexing.api.ReadableIndex.query] ("If IndexQuery.limit is 0, all
 * matches are emitted"). A plain `take(limit)` would turn the common `limit = 0` call into
 * `take(0)`, silently yielding no results.
 *
 * The limit is passed to each index rather than applied only to the concatenation. Applying it
 * afterwards made every call fetch both indexes in full, so a caller asking for ten paid for all of
 * them. [kinds] narrows the fetch for the same reason: an exact-name lookup otherwise returns every
 * method and field that happens to share the name.
 */
internal fun KtSymbolIndex.findSymbolBySimpleName(
	name: String,
	limit: Int,
	kinds: Set<JvmSymbolKind>? = null,
) = (
	sourceIndex.findBySimpleName(name, limit, kinds) +
		libraryIndex.findBySimpleName(name, limit, kinds)
).let { if (limit <= 0) it else it.take(limit) }

/** This future's value if it has completed normally, else `null`. */
@VisibleForTesting
internal fun <T> CompletableFuture<T>.valueNowOrNull(): T? = runCatching { getNow(null) }.getOrNull()
