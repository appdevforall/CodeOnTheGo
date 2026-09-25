package com.itsaky.androidide.lsp.kotlin.completion

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.appdevforall.codeonthego.indexing.InMemoryIndex
import org.appdevforall.codeonthego.indexing.jvm.JvmClassInfo
import org.appdevforall.codeonthego.indexing.jvm.JvmFunctionInfo
import org.appdevforall.codeonthego.indexing.jvm.JvmSourceLanguage
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbol
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolDescriptor
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolIndex
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolKind
import org.appdevforall.codeonthego.indexing.util.BackgroundIndexer
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * [collectUnimportedSymbolMatches] is the ordering and deduplication [collectUnimportedSymbols]
 * runs on top of the raw index queries.
 */
@RunWith(JUnit4::class)
class CollectUnimportedSymbolMatchesTest {
	/** A [JvmSymbolIndex] over a fresh, unfiltered in-memory backing store. */
	private fun newIndex(): JvmSymbolIndex {
		val backing = InMemoryIndex(JvmSymbolDescriptor)
		return object : JvmSymbolIndex(backing, BackgroundIndexer(backing)) {
			// The default filters to explicitly activated sources; these tests care about query
			// ordering and dedup, not source activation, so admit everything inserted.
			override fun visibleSourceIds(): Collection<String>? = null
		}
	}

	private fun classSymbol(
		sourceId: String,
		shortName: String,
		packageName: String = "com.example",
	): JvmSymbol {
		val internalName = "${packageName.replace('.', '/')}/$shortName"
		return JvmSymbol(
			key = internalName,
			sourceId = sourceId,
			name = internalName,
			shortName = shortName,
			packageName = packageName,
			kind = JvmSymbolKind.CLASS,
			language = JvmSourceLanguage.JAVA,
			data = JvmClassInfo(internalName = internalName),
		)
	}

	private fun funSymbol(
		sourceId: String,
		key: String,
		shortName: String,
		packageName: String = "com.example",
	): JvmSymbol {
		val internalName = "${packageName.replace('.', '/')}/Foo#$shortName"
		return JvmSymbol(
			key = key,
			sourceId = sourceId,
			name = internalName,
			shortName = shortName,
			packageName = packageName,
			kind = JvmSymbolKind.FUNCTION,
			language = JvmSourceLanguage.JAVA,
			data = JvmFunctionInfo(),
		)
	}

	private fun JvmSymbolIndex.insertBlocking(symbol: JvmSymbol) = runBlocking { insert(symbol) }

	@Test
	fun `an exact library match survives a cap already filled by an earlier index's prefix matches`() {
		val sourceIndex = newIndex()
		val libraryIndex = newIndex()

		// A prefix match that is not an exact match, sitting in the index queried first.
		sourceIndex.insertBlocking(classSymbol(sourceId = "proj", shortName = "FooBar"))
		// The exact match the user is typing, sitting in the index queried last.
		libraryIndex.insertBlocking(classSymbol(sourceId = "lib.jar", shortName = "Foo"))

		val accepted = mutableListOf<JvmSymbol>()
		collectUnimportedSymbolMatches(
			indexes = listOf(sourceIndex, libraryIndex),
			partial = "Foo",
			kinds = setOf(JvmSymbolKind.CLASS),
			limit = 1,
			fetchBudget = 10,
			accept = {
				accepted += it
				true
			},
		)

		// With a cap of one, a prefix-only scan (source, then library) would spend the cap on
		// FooBar and never reach the library index at all.
		assertThat(accepted.map { it.shortName }).containsExactly("Foo")
	}

	@Test
	fun `the same class indexed from two jars is offered once`() {
		val libraryIndex = newIndex()
		libraryIndex.insertBlocking(classSymbol(sourceId = "libA.jar", shortName = "Foo"))
		libraryIndex.insertBlocking(classSymbol(sourceId = "libB.jar", shortName = "Foo"))

		val accepted = mutableListOf<JvmSymbol>()
		collectUnimportedSymbolMatches(
			indexes = listOf(libraryIndex),
			partial = "Foo",
			kinds = setOf(JvmSymbolKind.CLASS),
			limit = 10,
			fetchBudget = 10,
			accept = {
				accepted += it
				true
			},
		)

		assertThat(accepted).hasSize(1)
		assertThat(accepted.single().fqName).isEqualTo("com.example.Foo")
	}

	@Test
	fun `a single exact match is not also offered via the prefix stage`() {
		val sourceIndex = newIndex()
		sourceIndex.insertBlocking(classSymbol(sourceId = "proj", shortName = "Foo"))

		val accepted = mutableListOf<JvmSymbol>()
		collectUnimportedSymbolMatches(
			indexes = listOf(sourceIndex),
			partial = "Foo",
			kinds = setOf(JvmSymbolKind.CLASS),
			limit = 10,
			fetchBudget = 10,
			accept = {
				accepted += it
				true
			},
		)

		assertThat(accepted).hasSize(1)
	}

	@Test
	fun `a duplicate rejected on its first occurrence does not block a later acceptable one`() {
		val sourceIndex = newIndex()
		val libraryIndex = newIndex()

		/*
		 * Same class, indexed from two JARs. The querying module can only reach one of them --
		 * modelled here as `accept` rejecting rows from "unreachable.jar" -- and that source happens
		 * to be queried first.
		 */
		sourceIndex.insertBlocking(classSymbol(sourceId = "unreachable.jar", shortName = "Foo"))
		libraryIndex.insertBlocking(classSymbol(sourceId = "reachable.jar", shortName = "Foo"))

		val accepted = mutableListOf<JvmSymbol>()
		collectUnimportedSymbolMatches(
			indexes = listOf(sourceIndex, libraryIndex),
			partial = "Foo",
			kinds = setOf(JvmSymbolKind.CLASS),
			limit = 10,
			fetchBudget = 10,
			accept = { symbol ->
				if (symbol.sourceId == "unreachable.jar") {
					false
				} else {
					accepted += symbol
					true
				}
			},
		)

		// Marking the dedup key "seen" on the rejected row would silently swallow the reachable
		// module's otherwise-valid completion.
		assertThat(accepted.map { it.sourceId }).containsExactly("reachable.jar")
	}

	@Test
	fun `a row accept rejects in the exact stage is not asked about again in the prefix stage`() {
		val sourceIndex = newIndex()
		sourceIndex.insertBlocking(classSymbol(sourceId = "proj", shortName = "Foo"))

		var acceptCalls = 0
		collectUnimportedSymbolMatches(
			indexes = listOf(sourceIndex),
			partial = "Foo",
			kinds = setOf(JvmSymbolKind.CLASS),
			limit = 10,
			fetchBudget = 10,
			accept = {
				acceptCalls++
				false
			},
		)

		/*
		 * The same row (same source and key) is a match in both the exact and prefix stages; once
		 * `accept` has turned it down, asking again wastes the visibility check and item-building
		 * work it would otherwise repeat.
		 */
		assertThat(acceptCalls).isEqualTo(1)
	}

	@Test
	fun `two overloads sharing a qualified name are both offered`() {
		val sourceIndex = newIndex()
		sourceIndex.insertBlocking(funSymbol(sourceId = "proj", key = "com/example/Foo#foo()", shortName = "foo"))
		sourceIndex.insertBlocking(funSymbol(sourceId = "proj", key = "com/example/Foo#foo(int)", shortName = "foo"))

		val accepted = mutableListOf<JvmSymbol>()
		collectUnimportedSymbolMatches(
			indexes = listOf(sourceIndex),
			partial = "foo",
			kinds = setOf(JvmSymbolKind.FUNCTION),
			limit = 10,
			fetchBudget = 10,
			accept = {
				accepted += it
				true
			},
		)

		assertThat(accepted).hasSize(2)
	}
}
