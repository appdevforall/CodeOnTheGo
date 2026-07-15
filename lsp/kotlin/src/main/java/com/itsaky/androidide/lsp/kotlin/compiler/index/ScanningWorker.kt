package com.itsaky.androidide.lsp.kotlin.compiler.index

import com.itsaky.androidide.lsp.kotlin.compiler.CompilationKind
import com.itsaky.androidide.lsp.kotlin.compiler.modules.KtModule
import com.itsaky.androidide.lsp.kotlin.compiler.modules.asFlatSequence
import com.itsaky.androidide.lsp.kotlin.compiler.modules.isSourceModule
import com.itsaky.androidide.lsp.kotlin.utils.toNioPathOrNull
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolIndex
import org.slf4j.LoggerFactory

internal class ScanningWorker(
    private val kind: CompilationKind,
    private val sourceIndex: JvmSymbolIndex,
    private val indexWorker: IndexWorker,
    private val modules: List<KtModule>,
) {

    companion object {
        private val logger = LoggerFactory.getLogger(ScanningWorker::class.java)
    }

    suspend fun scan() = coroutineScope {
        val sourceFiles = modules.asFlatSequence()
            .filter { it.isSourceModule }
            .flatMap { it.computeFiles(extended = true) }
            .filter {
                it.toNioPathOrNull()?.let { path -> kind.acceptsFile(path) } ?: run {
                    logger.warn("rejecting {} from kt source index", it)
                    false
                }
            }
            .takeWhile { isActive }
            .toList()

        sourceIndex.setActiveSources(
            sourceFiles
                .asSequence()
                .map { it.path }
                .takeWhile { isActive }
                .toSet()
        )

        for (sourceFile in sourceFiles) {
            if (!isActive) return@coroutineScope
            indexWorker.submitCommand(IndexCommand.ScanSourceFile(sourceFile))
        }

        indexWorker.submitCommand(IndexCommand.SourceScanningComplete)

        sourceFiles.asSequence()
            .takeWhile { isActive }
            .forEach { sourceFile ->
                indexWorker.submitCommand(IndexCommand.IndexSourceFile(sourceFile))
            }

        indexWorker.submitCommand(IndexCommand.IndexingComplete)
    }
}