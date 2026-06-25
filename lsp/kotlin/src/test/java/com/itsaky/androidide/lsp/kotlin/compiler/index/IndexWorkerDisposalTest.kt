package com.itsaky.androidide.lsp.kotlin.compiler.index

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.appdevforall.codeonthego.indexing.InMemoryIndex
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolDescriptor
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolIndex
import org.appdevforall.codeonthego.indexing.jvm.KtFileMetadataDescriptor
import org.appdevforall.codeonthego.indexing.jvm.KtFileMetadataIndex
import org.appdevforall.codeonthego.indexing.util.BackgroundIndexer
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager
import org.junit.Test

/**
 * Regression tests for APPDEVFORALL-17R / ADFA-4384:
 * `AssertionError: Project is already disposed` thrown by [IndexWorker] when the IntelliJ
 * [org.jetbrains.kotlin.com.intellij.openapi.project.Project] is disposed while the background
 * index worker is still running and about to call `PsiManager.findFile(project)`.
 */
class IndexWorkerDisposalTest : KtLspTest() {

	private fun jvmIndex(): JvmSymbolIndex {
		val backing = InMemoryIndex(JvmSymbolDescriptor)
		return object : JvmSymbolIndex(backing, BackgroundIndexer(backing)) {
			override fun isActive(sourceId: String) = true
		}
	}

	private fun fileIndex(): KtFileMetadataIndex =
		KtFileMetadataIndex(InMemoryIndex(KtFileMetadataDescriptor))

	@Test
	fun `worker stops without crashing when project is already disposed`(): Unit = runBlocking {
		// Capture a real VirtualFile while the project is still alive.
		val vf = createSourceFile("Sample.kt", "fun foo() = 1").virtualFile

		val worker = IndexWorker(
			project = env.project,
			queue = WorkerQueue(),
			fileIndex = fileIndex(),
			sourceIndex = jvmIndex(),
			scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
		)

		// Dispose the project out from under the worker, exactly as happens on LSP shutdown
		// (env.close() disposes the project via its parent Disposable, the production path).
		// IntelliJ requires model teardown to run inside a write action.
		ApplicationManager.getApplication().runWriteAction { env.close() }
		assertThat(env.project.isDisposed).isTrue()

		// Pre-fix, processing either command calls PsiManager.findFile on the disposed project
		// and throws AssertionError("Project is already disposed"). Post-fix, the disposal guard
		// breaks the loop, so start() returns cleanly instead of throwing.
		worker.submitCommand(IndexCommand.ScanSourceFile(vf))
		worker.submitCommand(IndexCommand.IndexSourceFile(vf))
		worker.submitCommand(IndexCommand.Stop)

		withTimeout(5_000) { worker.start() }
	}
}
