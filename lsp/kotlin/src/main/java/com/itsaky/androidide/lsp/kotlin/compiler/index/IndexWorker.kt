package com.itsaky.androidide.lsp.kotlin.compiler.index

import com.itsaky.androidide.lsp.kotlin.compiler.CompilationEnvironment
import com.itsaky.androidide.lsp.kotlin.compiler.modules.backingFilePath
import com.itsaky.androidide.lsp.kotlin.compiler.read
import com.itsaky.androidide.lsp.kotlin.utils.toNioPathOrNull
import com.itsaky.androidide.progress.ICancelChecker
import com.itsaky.androidide.utils.KeyedDebouncingAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
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
		override fun equals(other: Any?): Boolean {
			return path == (other as? ModFileIndexKey)?.path
		}

		override fun hashCode(): Int {
			return path.hashCode()
		}

		operator fun component1() = path
		operator fun component2() = ktFile
	}

	suspend fun start() = coroutineScope {
		var scanCount = 0
		var sourceIndexCount = 0

		val modifiedFileIndexer = KeyedDebouncingAction<ModFileIndexKey>(
			scope = scope,
			debounceDuration = CompilationEnvironment.DEFAULT_FILE_MOD_EVENT_DEBOUNCE_DURATION
		) { (path, ktFile), cancelChecker ->
			logger.debug("Indexing modified file: {}", path)
			indexSourceFile(project, ktFile, fileIndex, sourceIndex, cancelChecker)
			sourceIndexCount++
		}

		while (isActive) {
			when (val cmd = queue.take()) {
				is IndexCommand.RemoveFromIndex -> {
					val filePath = cmd.path.pathString
					fileIndex.remove(filePath)
					sourceIndex.removeBySource(filePath)
				}

				is IndexCommand.IndexSourceFile -> {
					if (cmd.vf.fileSystem.protocol != "file") {
						logger.warn("Unknown source file protocol: {}", cmd.vf.path)
						continue
					}

					val ktFile = project.read {
						PsiManager.getInstance(project)
							.findFile(cmd.vf) as? KtFile
					}

					if (ktFile == null) {
						// probably a non-kotlin file
						continue
					}

					indexSourceFile(
						project = project,
						ktFile = ktFile,
						fileIndex = fileIndex,
						symbolsIndex = sourceIndex,
						cancelChecker = ICancelChecker.NOOP
					)

					sourceIndexCount++
				}

				is IndexCommand.IndexModifiedFile -> {
					modifiedFileIndexer.schedule(
						ModFileIndexKey(
							cmd.ktFile.backingFilePath!!,
							cmd.ktFile
						)
					)
				}

				IndexCommand.IndexingComplete -> {
					logger.info(
						"Indexing complete: scanned={}, sourceIndexCount={}",
						scanCount,
						sourceIndexCount,
					)
				}

				is IndexCommand.ScanSourceFile -> {
					val ktFile = project.read {
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
				}

				IndexCommand.Stop -> break
			}
		}
	}

	suspend fun submitCommand(cmd: IndexCommand) {
		when (cmd) {
			is IndexCommand.ScanSourceFile, IndexCommand.SourceScanningComplete -> {
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
