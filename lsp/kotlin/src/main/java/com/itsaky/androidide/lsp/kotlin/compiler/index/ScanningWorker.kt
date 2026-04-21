package com.itsaky.androidide.lsp.kotlin.compiler.index

import com.itsaky.androidide.lsp.kotlin.compiler.modules.KtModule
import com.itsaky.androidide.lsp.kotlin.compiler.modules.asFlatSequence
import com.itsaky.androidide.lsp.kotlin.compiler.modules.isSourceModule
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolIndex
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

internal class ScanningWorker(
	private val sourceIndex: JvmSymbolIndex,
	private val indexWorker: IndexWorker,
	private val modules: List<KtModule>,
) {

	companion object {
		private val logger = LoggerFactory.getLogger(ScanningWorker::class.java)
	}

	private val isRunning = AtomicBoolean(false)

	suspend fun start() {
		isRunning.set(true)
		try {
			scan()
		} finally {
			isRunning.set(false)
		}
	}

	private suspend fun scan() {
		val sourceFiles = modules.asFlatSequence()
			.filter { it.isSourceModule }
			.flatMap { it.computeFiles(extended = true) }
			.takeWhile { isRunning.get() }
			.toList()

		sourceIndex.setActiveSources(sourceFiles.asSequence().map { it.path }.toSet())

		for (sourceFile in sourceFiles) {
			if (!isRunning.get()) return
			indexWorker.submitCommand(IndexCommand.ScanSourceFile(sourceFile))
		}

		indexWorker.submitCommand(IndexCommand.SourceScanningComplete)

		sourceFiles.asSequence()
			.takeWhile { isRunning.get() }
			.forEach { sourceFile ->
				indexWorker.submitCommand(IndexCommand.IndexSourceFile(sourceFile))
			}

		indexWorker.submitCommand(IndexCommand.IndexingComplete)
	}

	fun stop() {
		isRunning.set(false)
	}
}