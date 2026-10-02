package com.itsaky.androidide.lsp.kotlin.compiler.index

import com.itsaky.androidide.lsp.kotlin.compiler.CompilationEnvironment
import com.itsaky.androidide.lsp.kotlin.compiler.modules.AnalysisPreemptedException
import com.itsaky.androidide.lsp.kotlin.compiler.modules.backingFilePath
import com.itsaky.androidide.lsp.kotlin.compiler.read
import com.itsaky.androidide.memprof.Memprof
import com.itsaky.androidide.memprof.MemprofSpan
import com.itsaky.androidide.progress.ICancelChecker
import com.itsaky.androidide.utils.KeyedDebouncingAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolIndex
import org.appdevforall.codeonthego.indexing.jvm.KtFileMetadata
import org.appdevforall.codeonthego.indexing.jvm.KtFileMetadataIndex
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.psi.KtFile
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.pathString

internal class IndexWorker(
	private val project: Project,
	private val queue: WorkerQueue<IndexCommand>,
	private val fileIndex: KtFileMetadataIndex,
	private val sourceIndex: JvmSymbolIndex,
	private val scope: CoroutineScope,
) {
	companion object {
		private val logger = LoggerFactory.getLogger(IndexWorker::class.java)
	}

	private class ModFileIndexKey(
		val path: Path,
		val ktFile: KtFile,
	) {
		override fun equals(other: Any?): Boolean = path == (other as? ModFileIndexKey)?.path

		override fun hashCode(): Int = path.hashCode()

		operator fun component1() = path

		operator fun component2() = ktFile
	}

	suspend fun start() =
		coroutineScope {
			var scanCount = 0
			var sourceIndexCount = 0

			val modifiedFileIndexer =
				KeyedDebouncingAction<ModFileIndexKey>(
					scope = scope,
					debounceDuration = CompilationEnvironment.DEFAULT_FILE_MOD_EVENT_DEBOUNCE_DURATION,
				) { (path, ktFile), cancelChecker ->
					logger.debug("Indexing modified file: {}", path)
					try {
						indexSourceFile(project, ktFile, fileIndex, sourceIndex, cancelChecker)
						sourceIndexCount++
					} catch (e: AnalysisPreemptedException) {
						// Preempted by higher-priority analysis; re-queue so the edit still gets indexed.
						logger.debug("Indexing of modified file {} preempted; re-queueing", path)
						scope.launch { submitCommand(IndexCommand.IndexModifiedFile(ktFile)) }
					}
				}

			var scanPhase: MemprofSpan? = null
			var indexPhase: MemprofSpan? = null
			try {
				while (isActive) {
					// Defensive guard: if the project was disposed out from under us (e.g. a disposal
					// path that didn't first drain this worker), stop instead of calling PsiManager on a
					// disposed project, which throws "Project is already disposed" (APPDEVFORALL-17R).
					if (project.isDisposed) break

					when (val cmd = queue.take()) {
						is IndexCommand.RemoveFromIndex -> {
							applyRemovals(
								first = cmd,
								fileIndex = fileIndex,
								sourceIndex = sourceIndex,
								pollNext = { queue.pollIndexQueue() },
								pushBack = { queue.pushBackIndexQueue(it) },
							)
						}

						is IndexCommand.IndexSourceFile -> {
							if (cmd.vf.fileSystem.protocol != "file") {
								logger.warn("Unknown source file protocol: {}", cmd.vf.path)
								continue
							}

							if (project.isDisposed) break

							val ktFile =
								project.read {
									PsiManager
										.getInstance(project)
										.findFile(cmd.vf) as? KtFile
								}

							if (ktFile == null) {
								// probably a non-kotlin file
								continue
							}

							try {
								// cmd.vf.path is not a plain field read (it builds a system-independent
								// path string), so it must not be computed when nothing records it.
								val detail = if (Memprof.sink != null) cmd.vf.path else null
								Memprof.section("Index Kotlin file", detail) {
									indexSourceFile(
										project = project,
										ktFile = ktFile,
										fileIndex = fileIndex,
										symbolsIndex = sourceIndex,
										// A real (cancellable) checker so the scheduler can preempt this pass
										// in favour of completion/diagnostics.
										cancelChecker = ICancelChecker.Default(),
									)
								}

								sourceIndexCount++
							} catch (e: AnalysisPreemptedException) {
								// Preempted by higher-priority analysis; re-queue so the file still gets indexed.
								logger.debug("Indexing of {} preempted; re-queueing", cmd.vf.path)
								scope.launch { submitCommand(cmd) }
							}
						}

						is IndexCommand.IndexModifiedFile -> {
							modifiedFileIndexer.schedule(
								ModFileIndexKey(
									cmd.ktFile.backingFilePath!!,
									cmd.ktFile,
								),
							)
						}

						IndexCommand.IndexingComplete -> {
							logger.info(
								"Indexing complete: scanned={}, sourceIndexCount={}",
								scanCount,
								sourceIndexCount,
							)
							/*
							 * No open index phase (e.g. IndexingComplete with nothing indexed) means
							 * there is no real phase to mark; ?.apply leaves the marker unemitted
							 * rather than begin-and-immediately-end a zero-length one.
							 */
							indexPhase?.apply {
								put("scanned", scanCount.toLong())
								put("indexed", sourceIndexCount.toLong())
								end()
							}
							indexPhase = null
						}

						IndexCommand.SourceScanningStarted -> {
							/*
							 * A scan can be cancelled before it reaches SourceScanningComplete (e.g.
							 * KtSymbolIndex.refreshSources() restarting it), leaving scanPhase stale
							 * and open; a fresh scan starting must not fold into it.
							 */
							scanPhase?.abandon()
							scanPhase = beginScanPhase()
						}

						is IndexCommand.ScanSourceFile -> {
							if (project.isDisposed) break
							if (scanPhase == null) {
								scanPhase = beginScanPhase()
							}

							val ktFile =
								project.read {
									PsiManager.getInstance(project).findFile(cmd.vf) as? KtFile
								}
									?: continue

							val newFile = ktFile.toMetadata(project, isIndexed = false)
							val existingFile = fileIndex.get(newFile.filePath)
							if (KtFileMetadata.shouldBeSkipped(existingFile, newFile)) {
								continue
							}

							fileIndex.upsert(newFile)
							scanCount++
						}

						IndexCommand.SourceScanningComplete -> {
							logger.info("Scanning complete. Found {} files to index.", scanCount)
							/*
							 * The index phase must begin before the scan phase ends, so the report's
							 * open-phase count never touches zero at this handoff (which is what
							 * printed the logcat report 3-4 times per open).
							 */
							if (indexPhase == null) {
								indexPhase = beginIndexPhase()
							}
							(scanPhase ?: beginScanPhase()).apply {
								put("files", scanCount.toLong())
								end()
							}
							scanPhase = null
						}

						IndexCommand.Stop -> {
							break
						}
					}
				}
			} finally {
				// The worker can stop before the queue reports either phase as complete.
				scanPhase?.abandon()
				indexPhase?.abandon()
			}
		}

	private fun beginScanPhase() = Memprof.beginPhase("Scan Kotlin sources", "source_scan_complete")

	private fun beginIndexPhase() = Memprof.beginPhase("Index Kotlin sources", "source_index_complete")

	suspend fun submitCommand(cmd: IndexCommand) {
		when (cmd) {
			is IndexCommand.ScanSourceFile,
			IndexCommand.SourceScanningStarted,
			IndexCommand.SourceScanningComplete,
			-> {
				queue.putScanQueue(cmd)
			}

			is IndexCommand.IndexModifiedFile -> {
				queue.putEditQueue(cmd)
			}

			else -> {
				queue.putIndexQueue(cmd)
			}
		}
	}
}

/**
 * Apply [first] plus any consecutive, immediately-available [IndexCommand.RemoveFromIndex]
 * commands as a single batched removal.
 *
 * The symbol removals and the per-file metadata removals are each collapsed into one
 * batched call — [JvmSymbolIndex.removeBySources] and [KtFileMetadataIndex.removeAll], a
 * single SQLite transaction apiece — instead of issuing one `DELETE` per file (one
 * transaction each), which is the N+1 this fix targets (Sentry APPDEVFORALL-SE).
 *
 * [pollNext] returns the next already-queued index command without blocking, or `null`
 * when none is ready. A polled command that is *not* a removal is handed to [pushBack] so
 * it is processed (in order) on the next loop iteration rather than dropped.
 *
 * @param first      The removal command that triggered this batch.
 * @param fileIndex  Per-file metadata index; removed via the batched [KtFileMetadataIndex.removeAll].
 * @param sourceIndex Symbol index; removed via the batched [JvmSymbolIndex.removeBySources].
 * @param pollNext   Non-blocking poll of the next queued index command.
 * @param pushBack   Returns a non-removal command to the front of the queue.
 */
internal suspend fun applyRemovals(
	first: IndexCommand.RemoveFromIndex,
	fileIndex: KtFileMetadataIndex,
	sourceIndex: JvmSymbolIndex,
	pollNext: () -> IndexCommand?,
	pushBack: (IndexCommand) -> Unit,
) {
	val paths = ArrayList<String>()
	paths.add(first.path.pathString)

	while (true) {
		val next = pollNext() ?: break
		if (next is IndexCommand.RemoveFromIndex) {
			paths.add(next.path.pathString)
		} else {
			// Not batchable — return it so the main loop handles it next, in order.
			pushBack(next)
			break
		}
	}

	// Collapse all per-file metadata removals into a single transaction.
	fileIndex.removeAll(paths)

	// Collapse all symbol removals into a single transaction.
	sourceIndex.removeBySources(paths)
}
