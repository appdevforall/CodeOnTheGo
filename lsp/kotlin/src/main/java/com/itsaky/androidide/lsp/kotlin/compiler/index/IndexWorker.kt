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

			/*
			 * The counters run for as long as start() does, which spans every scan a build or sync
			 * restarts, so each phase reports what happened since it began.
			 */
			var scannedAtScanBegin = 0
			var scannedAtIndexBegin = 0
			var indexedAtIndexBegin = 0

			fun beginScan(): MemprofSpan {
				scannedAtScanBegin = scanCount
				return beginScanPhase()
			}

			fun beginIndex(): MemprofSpan {
				scannedAtIndexBegin = scannedAtScanBegin
				indexedAtIndexBegin = sourceIndexCount
				return beginIndexPhase()
			}

			/*
			 * The scan pass whose phases are open. A preempted file of that pass is re-queued behind
			 * the pass's IndexingComplete, so the index phase ends only once that has arrived and
			 * every retry has run.
			 */
			var currentPass: Int? = null
			var pendingRetries = 0
			var indexingCompleteReceived = false

			fun endIndexPhaseIfDrained() {
				if (!indexingCompleteReceived || pendingRetries > 0) return
				indexPhase?.apply {
					put("scanned", (scanCount - scannedAtIndexBegin).toLong())
					put("indexed", (sourceIndexCount - indexedAtIndexBegin).toLong())
					end()
				}
				indexPhase = null
				currentPass = null
			}

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
							if (project.isDisposed) break

							val inCurrentPass = cmd.pass != null && cmd.pass == currentPass
							if (cmd.isRetry && inCurrentPass) pendingRetries--

							when (indexFile(cmd)) {
								IndexOutcome.INDEXED -> {
									sourceIndexCount++
								}

								IndexOutcome.SKIPPED -> {
									Unit
								}

								IndexOutcome.PREEMPTED -> {
									// Preempted by higher-priority analysis; re-queue so the file still gets indexed.
									logger.debug("Indexing of {} preempted; re-queueing", cmd.vf.path)
									if (inCurrentPass) pendingRetries++
									scope.launch { submitCommand(cmd.copy(isRetry = true)) }
								}
							}

							if (inCurrentPass) endIndexPhaseIfDrained()
						}

						is IndexCommand.IndexModifiedFile -> {
							modifiedFileIndexer.schedule(
								ModFileIndexKey(
									cmd.ktFile.backingFilePath!!,
									cmd.ktFile,
								),
							)
						}

						is IndexCommand.IndexingComplete -> {
							logger.info(
								"Indexing complete: scanned={}, sourceIndexCount={}",
								scanCount,
								sourceIndexCount,
							)
							// A superseded pass's completion must not end the current pass's phase.
							if (cmd.pass == currentPass) {
								indexingCompleteReceived = true
								endIndexPhaseIfDrained()
							}
						}

						is IndexCommand.SourceScanningStarted -> {
							/*
							 * A scan can be cancelled before it completes (e.g. KtSymbolIndex.refreshSources()
							 * restarting it), leaving its phases stale and open; a fresh scan starting
							 * supersedes them rather than folding into them.
							 */
							scanPhase?.abandon()
							indexPhase?.abandon()
							indexPhase = null
							currentPass = cmd.pass
							pendingRetries = 0
							indexingCompleteReceived = false
							scanPhase = beginScan()
						}

						is IndexCommand.ScanSourceFile -> {
							if (project.isDisposed) break

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

						is IndexCommand.SourceScanningComplete -> {
							logger.info("Scanning complete. Found {} files to index.", scanCount)
							if (cmd.pass != currentPass) continue
							/*
							 * The index phase must begin before the scan phase ends, so the report's
							 * open-phase count never touches zero at this handoff, which would print
							 * the logcat report mid-open.
							 */
							if (indexPhase == null) {
								indexPhase = beginIndex()
							}
							scanPhase?.apply {
								put("files", (scanCount - scannedAtScanBegin).toLong())
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

	private enum class IndexOutcome { INDEXED, SKIPPED, PREEMPTED }

	private suspend fun indexFile(cmd: IndexCommand.IndexSourceFile): IndexOutcome {
		if (cmd.vf.fileSystem.protocol != "file") {
			logger.warn("Unknown source file protocol: {}", cmd.vf.path)
			return IndexOutcome.SKIPPED
		}

		val ktFile =
			project.read {
				PsiManager
					.getInstance(project)
					.findFile(cmd.vf) as? KtFile
			}
				// probably a non-kotlin file
				?: return IndexOutcome.SKIPPED

		return try {
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
			IndexOutcome.INDEXED
		} catch (e: AnalysisPreemptedException) {
			IndexOutcome.PREEMPTED
		}
	}

	private fun beginScanPhase() = Memprof.beginPhase("Scan Kotlin sources", "source_scan_complete")

	private fun beginIndexPhase() = Memprof.beginPhase("Index Kotlin sources", "source_index_complete")

	suspend fun submitCommand(cmd: IndexCommand) {
		when (cmd) {
			is IndexCommand.ScanSourceFile,
			is IndexCommand.SourceScanningStarted,
			is IndexCommand.SourceScanningComplete,
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
