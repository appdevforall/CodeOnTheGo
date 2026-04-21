package com.itsaky.androidide.lsp.kotlin.compiler.index

import com.github.benmanes.caffeine.cache.Caffeine
import com.itsaky.androidide.lsp.kotlin.compiler.modules.KtModule
import com.itsaky.androidide.lsp.kotlin.compiler.read
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolIndex
import org.appdevforall.codeonthego.indexing.jvm.KtFileMetadataIndex
import org.appdevforall.codeonthego.indexing.service.IndexKey
import org.checkerframework.checker.index.qual.NonNegative
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.psi.KtFile
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

val KT_SOURCE_FILE_INDEX_KEY = IndexKey<JvmSymbolIndex>("kt-source-file-index")
val KT_SOURCE_FILE_META_INDEX_KEY = IndexKey<KtFileMetadataIndex>("kt-source-file-meta-index")

/**
 * An index of symbols from Kotlin source files and JARs.
 *
 * NOTE: This index does not own the provided [fileIndex], [sourceIndex] and [libraryIndex].
 * Callers are responsible for closing the provided indexes.
 */
internal class KtSymbolIndex(
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
		const val DEFAULT_CACHE_SIZE = 100L
	}

	private val workerQueue = WorkerQueue<IndexCommand>()
	private val indexWorker = IndexWorker(
		project = project,
		queue = workerQueue,
		fileIndex = fileIndex,
		sourceIndex = sourceIndex,
	)

	private val scanningWorker = ScanningWorker(
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

	fun syncIndexInBackground() {
		// TODO: Figure out how to handle already-running scanning/indexing jobs.

		indexingJob = scope.launch {
			indexWorker.start()
		}

		scanningJob = scope.launch(Dispatchers.IO) {
			scanningWorker.start()
		}
	}

	fun queueOnFileChangedAsync(ktFile: KtFile) {
		scope.launch {
			queueOnFileChanged(ktFile)
		}
	}

	suspend fun queueOnFileChanged(ktFile: KtFile) {
		indexWorker.submitCommand(IndexCommand.IndexModifiedFile(ktFile))
	}

	fun openKtFile(path: Path, ktFile: KtFile) {
		openedFiles[path] = ktFile
	}

	fun closeKtFile(path: Path) {
		openedFiles.remove(path)
	}

	fun getOpenedKtFile(path: Path) = openedFiles[path]

	fun getKtFile(vf: VirtualFile): KtFile {
		val path = vf.toNioPath()

		openedFiles[path]?.also { return it }
		ktFileCache.getIfPresent(path)?.also { return it }

		val ktFile = loadKtFile(vf)

		ktFileCache.put(path, ktFile)
		return ktFile
	}

	private fun loadKtFile(vf: VirtualFile): KtFile = project.read {
		PsiManager.getInstance(project)
			.findFile(vf) as KtFile
	}

	suspend fun close() {
		scanningWorker.stop()
		indexWorker.submitCommand(IndexCommand.Stop)

		scanningJob?.join()
		indexingJob?.join()
	}
}

internal fun KtSymbolIndex.packageExistsInSource(packageFqn: String) =
	fileIndex.packageExists(packageFqn)

internal fun KtSymbolIndex.filesForPackage(packageFqn: String) =
	fileIndex.getFilesForPackage(packageFqn)

internal fun KtSymbolIndex.subpackageNames(packageFqn: String) =
	fileIndex.getSubpackageNames(packageFqn)