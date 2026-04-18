package com.itsaky.androidide.lsp.kotlin.compiler.index

import com.itsaky.androidide.lsp.kotlin.compiler.read
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolIndex
import org.appdevforall.codeonthego.indexing.jvm.KtFileMetadata
import org.appdevforall.codeonthego.indexing.jvm.KtFileMetadataIndex
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.psi.KtFile
import org.slf4j.LoggerFactory

internal class IndexWorker(
	private val project: Project,
	private val queue: WorkerQueue<IndexCommand>,
	private val fileIndex: KtFileMetadataIndex,
	private val sourceIndex: JvmSymbolIndex,
) {
	companion object {
		private val logger = LoggerFactory.getLogger(IndexWorker::class.java)
	}

	suspend fun start() {
		var scanCount = 0
		var sourceIndexCount = 0

		while (true) {
			when (val command = queue.take()) {
				is IndexCommand.IndexSourceFile -> {
					if (command.vf.fileSystem.protocol != "file") {
						logger.warn("Unknown source file protocol: {}", command.vf.path)
						continue
					}

					val ktFile = project.read {
						PsiManager.getInstance(project)
							.findFile(command.vf) as? KtFile
					}

					if (ktFile == null) {
						// probably a non-kotlin file
						continue
					}

					indexSourceFile(project, ktFile, fileIndex, sourceIndex)
					sourceIndexCount++
				}

				is IndexCommand.IndexModifiedFile -> {
					logger.debug("Indexing modified ktFile: {}", command.ktFile)
					indexSourceFile(project, command.ktFile, fileIndex, sourceIndex)
					sourceIndexCount++
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
						PsiManager.getInstance(project).findFile(command.vf) as? KtFile
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
