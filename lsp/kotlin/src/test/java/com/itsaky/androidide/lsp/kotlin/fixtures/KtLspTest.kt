package com.itsaky.androidide.lsp.kotlin.fixtures

import com.itsaky.androidide.lsp.kotlin.compiler.index.toMetadata
import com.itsaky.androidide.lsp.kotlin.compiler.modules.analyzeMaybeDangling
import com.itsaky.androidide.lsp.kotlin.compiler.read
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtFile
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Base class for plain JVM unit tests that need a real Kotlin Analysis API environment.
 *
 * Each test method gets a fresh [KtLspTestEnvironment] (see [KtLspTestRule]) backed by a
 * temporary source directory.
 */
@RunWith(RobolectricTestRunner::class)
abstract class KtLspTest {
	@get:Rule
	@PublishedApi
	internal val lspTestRule = KtLspTestRule()

	internal val env: KtLspTestEnvironment
		get() = lspTestRule.env

	protected fun createSourceFile(
		relativePath: String,
		content: String,
	): KtFile {
		val file = env.createSourceFile(relativePath, content)
		// See the comment in `analyzeMaybeDanglingForTest` below: freshly-created files are invisible to
		// unqualified name resolution until they're registered with the symbol index's file metadata,
		// which in production happens via the background indexer. Do that synchronously here so every
		// file returned by this helper is resolvable.
		runBlocking { env.ktSymbolIndex.fileIndex.upsert(file.toMetadata(env.project, isIndexed = false)) }
		return file
	}

	protected fun <R> analyze(
		file: KtFile,
		action: KaSession.() -> R,
	): R = env.analyze(file, action)

	/** Resolves [call] to a function call and runs [action] inside the analyze block. */
	protected fun <R> analyzeMaybeDanglingForTest(
		call: KtCallElement,
		action: KaSession.(KaFunctionCall<*>?) -> R,
	): R {
		// `createSourceFile` writes the file and refreshes the VFS/module search scope, but unqualified
		// name resolution (e.g. resolving a call's callee) goes through `KtSymbolIndex.fileIndex`
		// (`DeclarationProvider.ktFilesForPackage`), which only learns about a file once it has been
		// scanned. In the real IDE that happens via the background indexer
		// (`KtSymbolIndex.syncIndexInBackground`), which the lightweight test environment never
		// starts, so any call in a freshly created test file otherwise resolves as
		// `UNRESOLVED_REFERENCE` even though the file's own PSI/declarations are visible. Register the
		// file's package metadata directly, synchronously, mirroring what `IndexCommand.ScanSourceFile`
		// does in production.
		val ktFile = call.containingKtFile
		runBlocking { env.ktSymbolIndex.fileIndex.upsert(ktFile.toMetadata(env.project, isIndexed = false)) }
		return env.project.read {
			analyzeMaybeDangling(ktFile) {
				val resolved = call.resolveToCall()?.successfulFunctionCallOrNull()
				action(resolved)
			}
		}
	}
}
