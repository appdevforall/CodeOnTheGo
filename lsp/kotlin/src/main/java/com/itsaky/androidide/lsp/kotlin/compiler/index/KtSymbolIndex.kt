package com.itsaky.androidide.lsp.kotlin.compiler.index

import com.github.benmanes.caffeine.cache.Caffeine
import com.itsaky.androidide.lsp.kotlin.compiler.CompilationKind
import com.itsaky.androidide.lsp.kotlin.compiler.modules.KtModule
import com.itsaky.androidide.lsp.kotlin.compiler.modules.backingFilePath
import com.itsaky.androidide.lsp.kotlin.compiler.read
import com.itsaky.androidide.lsp.kotlin.compiler.services.ProjectStructureProvider
import com.itsaky.androidide.lsp.kotlin.compiler.write
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
import kotlinx.coroutines.launch
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolIndex
import org.appdevforall.codeonthego.indexing.jvm.KtFileMetadataIndex
import org.appdevforall.codeonthego.indexing.service.IndexKey
import org.checkerframework.checker.index.qual.NonNegative
import org.jetbrains.kotlin.analysis.api.platform.modification.KaElementModificationType
import org.jetbrains.kotlin.analysis.api.platform.modification.KaSourceModificationService
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.pathString

val KT_SOURCE_FILE_INDEX_KEY = IndexKey<JvmSymbolIndex>("kt-source-file-index")
val KT_SOURCE_FILE_META_INDEX_KEY = IndexKey<KtFileMetadataIndex>("kt-source-file-meta-index")

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
	private val scope: CoroutineScope = CoroutineScope(
		Dispatchers.Default + SupervisorJob() + CoroutineName(
			"KtSymbolIndex"
		)
	)
) {
	companion object {
		private val logger = LoggerFactory.getLogger(KtSymbolIndex::class.java)
		const val DEFAULT_CACHE_SIZE = 100L
		private const val CLOSE_DRAIN_TIMEOUT_SECONDS = 5L
	}

	private val workerQueue = WorkerQueue<IndexCommand>()
	private val indexWorker = IndexWorker(
		project = project,
		queue = workerQueue,
		fileIndex = fileIndex,
		sourceIndex = sourceIndex,
		scope = scope,
	)

	private val scanningWorker = ScanningWorker(
		kind = kind,
		sourceIndex = sourceIndex,
		indexWorker = indexWorker,
		modules = modules,
	)

	private var scanningJob: Job? = null
	private var indexingJob: Job? = null

	private val ktFileCache = Caffeine
		.newBuilder()
		.maximumSize(cacheSize)
		.build<Path, KtFile>()

	private val openedFiles = ConcurrentHashMap<Path, KtFile>()

	val openedKtFiles: Sequence<Map.Entry<Path, KtFile>>
		get() = openedFiles.asSequence()

	/** Set by AbstractCompilationEnvironment.initialize once the env's KtPsiFactory exists. */
	lateinit var parser: KtPsiFactory

	private data class VersionedKtFile(val version: Int, val ktFile: KtFile)

	private val refreshExecutor: ExecutorService =
		Executors.newFixedThreadPool(2) { r -> Thread(r, "KtCurrentFileRefresh").apply { isDaemon = true } }

	/** path -> in-flight/last-launched refresh. Guarded by currentFiles.asMap().compute per key. */
	private val currentFiles = ConcurrentHashMap<Path, CompletableFuture<VersionedKtFile>>()
	/** path -> version most recently launched. Read/written only inside the compute() critical section. */
	private val currentVersions = ConcurrentHashMap<Path, Int>()

	fun syncIndexInBackground() {
		indexingJob?.cancel()
		startIndexing()

		scanningJob?.cancel()
		startScanning()
	}

	private fun startIndexing() {
		val job = scope.launch {
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
		scanningJob = scope.launch {
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

	private fun getVirtualFileOrWarn(path: Path): VirtualFile? {
		return path.toVirtualFileOrNull() ?: run {
			logger.warn("unable to find virtual file for path {}", path)
			null
		}
	}

	suspend fun submitForIndexing(path: Path) {
		val vf = getVirtualFileOrWarn(path) ?: return
		indexWorker.apply {
			submitCommand(IndexCommand.ScanSourceFile(vf))
			submitCommand(IndexCommand.IndexSourceFile(vf))
		}
	}

	suspend fun removeFromIndex(path: Path) {
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

	fun openKtFile(path: Path, ktFile: KtFile): KtFile? {
		return openedFiles.put(path, ktFile)
	}

	fun closeKtFile(path: Path) {
		openedFiles.remove(path)
	}

	fun getOpenedKtFile(path: Path) = openedFiles[path]

	/**
	 * Returns the canonical [KtFile] for [path] at the current document version, parsing (once) on a
	 * version miss. For non-open paths (no active document) falls back to the disk [getKtFile].
	 * Single-flight: concurrent callers at the same version share one parse.
	 */
	fun getCurrentKtFile(path: Path): CompletableFuture<KtFile?> {
		if (!DocumentUtils.isKotlinFile(path)) return CompletableFuture.completedFuture(null)

		val doc = FileManager.getActiveDocument(path)
			?: return CompletableFuture.completedFuture(getKtFile(path))  // not open -> disk path

		val version = doc.version
		val future = currentFiles.compute(path) { p, existing ->
			if (existing != null && !existing.isCompletedExceptionally && currentVersions[p] == version) {
				existing
			} else {
				currentVersions[p] = version
				val prior = existing
				CompletableFuture.supplyAsync({
					// Serialize with any prior refresh for this path so old->new succession is linear.
					val old = try { prior?.get()?.ktFile } catch (_: Throwable) { null }
					refreshToCurrent(p, version, old)
				}, refreshExecutor)
			}
		}!!
		return future.thenApply { it.ktFile }
	}

	/** Parses the live document for [path], registers it as the in-memory file, returns the stamped file. */
	private fun refreshToCurrent(path: Path, version: Int, old: KtFile?): VersionedKtFile {
		// [version] is the version observed when this refresh was launched (inside compute()); the
		// content below is read live, possibly later (this runs on refreshExecutor, after any prior
		// refresh's prior.get()). ActiveDocument's version/content are separate mutable fields with no
		// atomic snapshot, so a concurrent edit landing in that window can make [version] under-state
		// the content actually parsed. This is bounded and safe: callers never see stale content (what
		// we serve is at-least-as-fresh as requested); the only effect is a spurious cache miss and one
		// redundant re-parse on the next request for the newer version.
		val content = FileManager.getDocumentContents(path)
		val newKtFile = project.read { parser.createFile(path.pathString, content) }
		newKtFile.backingFilePath = path
		// Use the view provider's virtual file rather than KtFile.virtualFile: the latter is null for
		// non-physical PSI files (parser event system disabled, e.g. the unit-test environment), while
		// the view provider always exposes the backing light virtual file. For physical files (prod)
		// the two are the same object.
		ProjectStructureProvider.getInstance(project)
			.registerInMemoryFile(path.pathString, newKtFile.viewProvider.virtualFile)
		// Mirrors CompilationEnvironment.onFileContentChanged: invalidate the FIR session's view of
		// the (old) element under the write lock so it can't race a concurrent `analyze` (which only
		// holds the read lock), then re-index the new instance.
		project.write {
			// handleElementModification publishes an out-of-block modification event, which the
			// platform's ThreadingAssertions require to run inside a write action (independent of our
			// own read/write lock above, which only serializes with `analyze`).
			ApplicationManager.getApplication().runWriteAction {
				KaSourceModificationService.getInstance(project)
					.handleElementModification(old ?: newKtFile, KaElementModificationType.Unknown)
			}
			queueOnFileChangedAsync(newKtFile)
		}
		return VersionedKtFile(version, newKtFile)
	}

	/** Drops the cached current file for [path] (e.g. on close). */
	fun invalidateCurrent(path: Path) {
		currentFiles.remove(path)
		currentVersions.remove(path)
		ProjectStructureProvider.getInstance(project).unregisterInMemoryFile(path.pathString)
	}

	/**
	 * Non-blocking: the current cached instance for [path] if a refresh has already completed,
	 * else `null`. Safe to call while holding `project.read` (unlike [getCurrentKtFile], which may
	 * trigger a blocking refresh that needs `project.write`).
	 */
	fun getCurrentKtFileIfPresent(path: Path): KtFile? =
		currentFiles[path]?.getNow(null)?.ktFile

	fun getKtFile(vf: VirtualFile): KtFile? =
		getKtFile(vf.toNioPath(), vf)

	fun getKtFile(path: Path, virtualFile: VirtualFile? = null): KtFile? {
		if (!DocumentUtils.isKotlinFile(path)) return null

		if (FileManager.isActive(path)) {
			// Active document: peek the current-file cache without blocking. Calling
			// getCurrentKtFile(path).get() here would deadlock: getKtFile is invoked by
			// Analysis-API services (DeclarationsProvider, AnnotationsResolver,
			// DirectInheritorsProvider) while FIR resolution holds project.read, and a blocking
			// refresh needs project.write on the executor thread (reader-holds-lock waits on
			// writer -> deadlock). A peek miss falls back to the disk instance below; the file's
			// own refresh is already scheduled on edit and will be served on the next request.
			getCurrentKtFileIfPresent(path)?.let { return it }
		}

		openedFiles[path]?.also {
			return it
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

	private fun loadKtFile(vf: VirtualFile): KtFile = project.read {
		PsiManager.getInstance(project)
			.findFile(vf) as KtFile
	}

	suspend fun close() {
		indexWorker.submitCommand(IndexCommand.Stop)

		scanningJob?.cancelAndJoin()
		indexingJob?.join()

		// Cancel AND JOIN the index's own scope. Beyond the main worker loop drained above, the
		// debounced modifiedFileIndexer and queueOnFileChangedAsync coroutines also run
		// project.read { PsiManager … }. Joining guarantees none survive into the caller's
		// Disposer.dispose(...), which would otherwise crash with "Project is already disposed"
		// (APPDEVFORALL-17R). This index owns `scope`.
		scope.coroutineContext[Job]?.cancelAndJoin()

		// Drain the current-file refresh pool: refreshToCurrent runs project.read/write on it, so no
		// in-flight refresh may survive into the caller's project disposal (same rationale as the
		// scope join above). Bounded so a slow parse can't block shutdown indefinitely.
		refreshExecutor.shutdownNow()
		refreshExecutor.awaitTermination(CLOSE_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
	}
}

internal fun KtSymbolIndex.packageExistsInSource(packageFqn: String) =
	fileIndex.packageExists(packageFqn)

internal fun KtSymbolIndex.filesForPackage(packageFqn: String) =
	fileIndex.getFilesForPackage(packageFqn)

internal fun KtSymbolIndex.subpackageNames(packageFqn: String) =
	fileIndex.getSubpackageNames(packageFqn)

internal fun KtSymbolIndex.findSymbolBySimpleName(name: String, limit: Int) =
	(sourceIndex.findBySimpleName(name, 0) + libraryIndex.findBySimpleName(name, 0))
		.take(limit)
