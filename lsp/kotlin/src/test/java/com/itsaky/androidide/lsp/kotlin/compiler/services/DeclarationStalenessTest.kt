package com.itsaky.androidide.lsp.kotlin.compiler.services

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.eventbus.events.editor.ChangeType
import com.itsaky.androidide.eventbus.events.editor.DocumentChangeEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentCloseEvent
import com.itsaky.androidide.eventbus.events.editor.DocumentOpenEvent
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.projects.FileManager
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtFile
import org.junit.After
import org.junit.Test
import java.nio.file.Path

/**
 * The declaration provider must never answer from a stale package file list.
 *
 * Each test edits one file the way the editor does, then analyses another file in the same package.
 */
internal class DeclarationStalenessTest : KtLspTest() {
	override val enableParserEventSystem = true

	private val openedPaths = mutableListOf<Path>()

	@After
	fun closeDocs() {
		openedPaths.forEach { FileManager.onDocumentClose(DocumentCloseEvent(it)) }
		openedPaths.clear()
	}

	@Test
	fun addedFunctionResolvesInAnotherFile() = assertAddedDeclarationResolves("fun foo() = 1", "fun b() = foo()")

	@Test
	fun removedFunctionIsUnresolvedInAnotherFile() = assertRemovedDeclarationIsUnresolved("fun foo() = 1", "fun b() = foo()")

	@Test
	fun addedPropertyResolvesInAnotherFile() = assertAddedDeclarationResolves("val foo = 1", "fun b() = foo")

	@Test
	fun removedPropertyIsUnresolvedInAnotherFile() = assertRemovedDeclarationIsUnresolved("val foo = 1", "fun b() = foo")

	@Test
	fun addedClassResolvesInAnotherFile() = assertAddedDeclarationResolves("class Foo", "fun b() = Foo()")

	@Test
	fun removedClassIsUnresolvedInAnotherFile() = assertRemovedDeclarationIsUnresolved("class Foo", "fun b() = Foo()")

	@Test
	fun addedTypealiasResolvesInAnotherFile() = assertAddedDeclarationResolves("typealias Foo = String", "fun b(x: Foo) = x")

	@Test
	fun removedTypealiasIsUnresolvedInAnotherFile() = assertRemovedDeclarationIsUnresolved("typealias Foo = String", "fun b(x: Foo) = x")

	@Test
	fun addedNestedClassResolvesInAnotherFile() =
		assertAddedDeclarationResolves("class Outer { class Inner }", "fun b() = Outer.Inner()", without = "class Outer")

	@Test
	fun removedNestedClassIsUnresolvedInAnotherFile() =
		assertRemovedDeclarationIsUnresolved("class Outer { class Inner }", "fun b() = Outer.Inner()", without = "class Outer")

	@Test
	fun newFileInPackageIsFound() {
		val b = createSourceFile("B.kt", "package p\nfun b() = bar()")
		env.ktSymbolIndex.sourceFilesForPackage(FqName("p"))

		createSourceFile("C.kt", "package p\nfun bar() = 1")

		assertThat(diagnosticsOf(b)).doesNotContain(UNRESOLVED_REFERENCE)
	}

	@Test
	fun packageChangeMovesTheFile() {
		createSourceFile("C.kt", "package p\nfun bar() = 1")
		val path = sourcePath("C.kt")
		env.ktSymbolIndex.sourceFilesForPackage(FqName("p"))
		env.ktSymbolIndex.sourceFilesForPackage(FqName("q"))

		env.ktSymbolIndex.evictDiskCopy(path)
		val moved = createSourceFile("C.kt", "package q\nfun bar() = 1")
		assertThat(moved.packageFqName.asString()).isEqualTo("q")

		assertThat(pathsIn("p")).doesNotContain(path)
		assertThat(pathsIn("q")).contains(path)
	}

	/** A's disk copy lacks [declaration]; only the live document has it. */
	private fun assertAddedDeclarationResolves(
		declaration: String,
		usage: String,
		without: String = "",
	) {
		val withoutText = "package p\n$without"
		val a = openDocument("A.kt", withoutText)
		val b = createSourceFile("B.kt", "package p\n$usage")

		edit(a, withoutText, "package p\n$declaration", 2)

		assertThat(diagnosticsOf(b)).doesNotContain(UNRESOLVED_REFERENCE)
	}

	/** A's disk copy has [declaration]; only the live document has dropped it. */
	private fun assertRemovedDeclarationIsUnresolved(
		declaration: String,
		usage: String,
		without: String = "",
	) {
		val withText = "package p\n$declaration"
		val a = openDocument("A.kt", withText)
		val b = createSourceFile("B.kt", "package p\n$usage")

		edit(a, withText, "package p\n$without", 2)

		assertThat(diagnosticsOf(b)).contains(UNRESOLVED_REFERENCE)
	}

	private fun openDocument(
		relativePath: String,
		text: String,
	): Path {
		createSourceFile(relativePath, text)
		val path = sourcePath(relativePath)
		FileManager.onDocumentOpen(DocumentOpenEvent(path, text, 1))
		openedPaths.add(path)
		return path
	}

	/** Changes the open document at [path], then waits for the live refresh, as the editor's next request would. */
	private fun edit(
		path: Path,
		oldText: String,
		newText: String,
		version: Int,
	) {
		FileManager.onDocumentContentChange(
			DocumentChangeEvent(path, oldText, newText, version, ChangeType.NEW_TEXT, 0, Range.NONE),
		)
		runBlocking { env.ktSymbolIndex.refreshCurrentKtFile(path) }
	}

	private fun diagnosticsOf(file: KtFile): List<String> =
		analyzeMaybeDanglingForTest(file) {
			file
				.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)
				.map { it.factoryName }
		}

	private fun pathsIn(packageFqName: String): List<Path> =
		env.ktSymbolIndex.sourceFilesForPackage(FqName(packageFqName)).map { it.toNioPath() }

	private fun sourcePath(relativePath: String): Path = env.sourceRoots.first().resolve(relativePath)

	private companion object {
		const val UNRESOLVED_REFERENCE = "UNRESOLVED_REFERENCE"
	}
}
