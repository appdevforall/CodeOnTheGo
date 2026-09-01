package com.itsaky.androidide.lsp.kotlin.actions

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.eventbus.events.editor.DocumentCloseEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentOpenEvent
import com.itsaky.androidide.lsp.kotlin.compiler.index.UnpinnedKtFileAccess
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import com.itsaky.androidide.projects.FileManager
import kotlinx.coroutines.runBlocking
import org.appdevforall.codeonthego.indexing.jvm.JvmClassInfo
import org.appdevforall.codeonthego.indexing.jvm.JvmSourceLanguage
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbol
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolDescriptor
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolKind
import org.junit.After
import org.junit.Test
import java.nio.file.Path

/**
 * The importable-classifier query must not run inside the file's pin scope.
 *
 * Holding the pin across the query freezes live-PSI refresh for that path: concurrent acquirers join
 * the frozen instance and the refresh is only owed on release. The query does not read the file, so
 * the pin buys nothing over that window.
 */
internal class AddImportActionPinScopeTest : KtLspTest() {
	override val enableParserEventSystem = true

	private val content = "package p\n\nfun f(x: Foo) {}\n"

	private val openedPaths = mutableListOf<Path>()

	@After
	fun closeDocs() {
		openedPaths.forEach { FileManager.onDocumentClose(DocumentCloseEvent(it)) }
		openedPaths.clear()
	}

	private fun classifier(
		pkg: String,
		shortName: String,
	): JvmSymbol {
		val internalName = "${pkg.replace('.', '/')}/$shortName"
		return JvmSymbol(
			key = "$internalName#${JvmSymbolKind.CLASS.name}",
			sourceId = "test",
			name = internalName,
			shortName = shortName,
			packageName = pkg,
			kind = JvmSymbolKind.CLASS,
			language = JvmSourceLanguage.KOTLIN,
			data = JvmClassInfo(),
		)
	}

	private fun openDocument(): Path {
		createSourceFile("Main.kt", content)
		val path = env.sourceRoots.first().resolve("Main.kt")
		FileManager.onDocumentOpen(DocumentOpenEvent(path, content, 1))
		openedPaths.add(path)
		return path
	}

	@OptIn(UnpinnedKtFileAccess::class)
	@Test
	fun `the classifier query runs before the file is pinned`() {
		runBlocking { env.ktSymbolIndex.sourceIndex.insert(classifier("lib", "Foo")) }
		val path = openDocument()

		/*
		 * Nothing has resolved the live document yet, so the current-file cache is empty. That is what
		 * makes it a usable probe below: it becomes non-empty only once something pins or refreshes.
		 */
		assertThat(env.ktSymbolIndex.peekLiveKtFile(path)).isNull()

		var pinnedAtQueryTime: Boolean? = null
		env.onSymbolIndexQuery = { query ->
			if (query.exactMatch[JvmSymbolDescriptor.KEY_NAME] == "Foo" && pinnedAtQueryTime == null) {
				pinnedAtQueryTime = env.ktSymbolIndex.peekLiveKtFile(path) != null
			}
		}

		val candidates = AddImportAction().computeImportCandidates(env, path, "Foo")
		env.onSymbolIndexQuery = null

		assertThat(candidates.keys).containsExactly("lib.Foo")
		assertThat(pinnedAtQueryTime).isFalse()
	}
}
