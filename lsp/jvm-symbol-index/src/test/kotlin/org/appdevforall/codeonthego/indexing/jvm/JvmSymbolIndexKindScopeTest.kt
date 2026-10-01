package org.appdevforall.codeonthego.indexing.jvm

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.appdevforall.codeonthego.indexing.InMemoryIndex
import org.appdevforall.codeonthego.indexing.util.BackgroundIndexer
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * The package and simple-name lookups filter by kind in the query rather than over its result.
 *
 * A package holds far more members than top-level declarations, so a post-filter meant fetching
 * every symbol in the package to keep a handful -- and the caller's limit was spent on rows it
 * would then throw away.
 */
@RunWith(JUnit4::class)
class JvmSymbolIndexKindScopeTest {
	private fun index(): JvmSymbolIndex {
		val backing = InMemoryIndex(JvmSymbolDescriptor)
		return object : JvmSymbolIndex(backing, BackgroundIndexer(backing)) {
			override fun visibleSourceIds(): Collection<String>? = null
		}
	}

	private fun classifier(
		simpleName: String,
		pkg: String = "com.example",
	) = JvmSymbol(
		key = "$pkg.$simpleName",
		sourceId = "test.jar",
		name = "$pkg.$simpleName",
		shortName = simpleName,
		packageName = pkg,
		kind = JvmSymbolKind.CLASS,
		language = JvmSourceLanguage.JAVA,
		data = JvmClassInfo(internalName = "$pkg/$simpleName"),
	)

	private fun member(
		simpleName: String,
		owner: String,
		pkg: String = "com.example",
	) = JvmSymbol(
		key = "$pkg.$owner#$simpleName",
		sourceId = "test.jar",
		name = "$pkg.$owner#$simpleName",
		shortName = simpleName,
		packageName = pkg,
		kind = JvmSymbolKind.FUNCTION,
		language = JvmSourceLanguage.JAVA,
		data = JvmFunctionInfo(containingClassName = "$pkg/$owner"),
	)

	private fun topLevelFunction(
		simpleName: String,
		pkg: String = "com.example",
	) = JvmSymbol(
		key = "$pkg#$simpleName",
		sourceId = "test.jar",
		name = "$pkg#$simpleName",
		shortName = simpleName,
		packageName = pkg,
		kind = JvmSymbolKind.FUNCTION,
		language = JvmSourceLanguage.KOTLIN,
		data = JvmFunctionInfo(),
	)

	@Test
	fun `classifiers in a package exclude that package's members`() =
		runTest {
			val index = index()
			index.insert(classifier("Widget"))
			repeat(50) { index.insert(member("helper$it", owner = "Widget")) }

			val found = index.findClassifiersInPackage("com.example").map { it.shortName }.toList()

			assertThat(found).containsExactly("Widget")
		}

	@Test
	fun `a classifier is still returned when members would have filled the limit`() =
		runTest {
			val index = index()
			// Members are inserted first, so a post-filter would spend the limit on them.
			repeat(50) { index.insert(member("helper$it", owner = "Widget")) }
			index.insert(classifier("Widget"))

			val found = index.findClassifiersInPackage("com.example", limit = 5).map { it.shortName }.toList()

			assertThat(found).containsExactly("Widget")
		}

	@Test
	fun `top-level callables in a package exclude members of its classes`() =
		runTest {
			val index = index()
			index.insert(topLevelFunction("launch"))
			repeat(20) { index.insert(member("helper$it", owner = "Widget")) }

			val found = index.findTopLevelCallablesInPackage("com.example").map { it.shortName }.toList()

			assertThat(found).containsExactly("launch")
		}

	@Test
	fun `a simple-name lookup can be restricted to classifiers`() =
		runTest {
			val index = index()
			index.insert(classifier("Widget"))
			index.insert(member("Widget", owner = "Factory"))

			val all = index.findBySimpleName("Widget").toList()
			val classifiersOnly =
				index.findBySimpleName("Widget", kinds = JvmSymbolKind.CLASSIFIER_KINDS).toList()

			assertThat(all).hasSize(2)
			assertThat(classifiersOnly.map { it.kind }).containsExactly(JvmSymbolKind.CLASS)
		}

	@Test
	fun `a prefix lookup can be restricted by kind`() =
		runTest {
			val index = index()
			index.insert(classifier("Widget"))
			index.insert(member("WidgetFactory", owner = "Factory"))

			val found = index.findByPrefix("Widget", kinds = JvmSymbolKind.CLASSIFIER_KINDS).toList()

			assertThat(found.map { it.shortName }).containsExactly("Widget")
		}

	@Test
	fun `a file facade is not offered as a classifier`() =
		runTest {
			val index = index()
			index.insert(
				JvmSymbol(
					key = "com/example/WidgetKt",
					sourceId = "test.jar",
					name = "com/example/WidgetKt",
					shortName = "WidgetKt",
					packageName = "com.example",
					kind = JvmSymbolKind.FILE_FACADE,
					language = JvmSourceLanguage.KOTLIN,
					data = JvmClassInfo(internalName = "com/example/WidgetKt"),
				),
			)

			// Kotlin's import action asks for classifiers, and a facade is not one.
			assertThat(
				index.findBySimpleName("WidgetKt", kinds = JvmSymbolKind.CLASSIFIER_KINDS).toList(),
			).isEmpty()
			// It is still findable as a class, which is what Java needs.
			assertThat(
				index.findBySimpleName("WidgetKt", kinds = JvmSymbolKind.JVM_CLASS_KINDS).toList(),
			).hasSize(1)
		}
}
