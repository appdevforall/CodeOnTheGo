package org.appdevforall.codeonthego.indexing.jvm

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.appdevforall.codeonthego.indexing.InMemoryIndex
import org.appdevforall.codeonthego.indexing.util.BackgroundIndexer
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * The top-level class lookups and the package tree hold what the classpath trie held: every
 * top-level class file of any visibility, and the packages those class files are in.
 */
@RunWith(JUnit4::class)
class JvmSymbolIndexTopLevelClassTest {
	private fun unfilteredIndex(): JvmSymbolIndex {
		val backing = InMemoryIndex(JvmSymbolDescriptor)
		return object : JvmSymbolIndex(backing, BackgroundIndexer(backing)) {
			override fun visibleSourceIds(): Collection<String>? = null
		}
	}

	private fun filteredIndex(vararg activeSources: String): JvmSymbolIndex {
		val backing = InMemoryIndex(JvmSymbolDescriptor)
		return JvmSymbolIndex(backing, BackgroundIndexer(backing)).apply { setActiveSources(activeSources.toSet()) }
	}

	private fun symbol(
		shortName: String,
		kind: JvmSymbolKind,
		pkg: String = PKG,
		sourceId: String = JAR_A,
		visibility: JvmVisibility = JvmVisibility.PUBLIC,
		data: JvmSymbolInfo = JvmClassInfo(internalName = "${pkg.replace('.', '/')}/$shortName"),
	) = JvmSymbol(
		key = "${pkg.replace('.', '/')}/$shortName",
		sourceId = sourceId,
		name = "${pkg.replace('.', '/')}/$shortName",
		shortName = shortName,
		packageName = pkg,
		kind = kind,
		language = JvmSourceLanguage.KOTLIN,
		visibility = visibility,
		data = data,
	)

	private fun topLevelClass(
		shortName: String,
		pkg: String = PKG,
		sourceId: String = JAR_A,
		kind: JvmSymbolKind = JvmSymbolKind.CLASS,
		visibility: JvmVisibility = JvmVisibility.PUBLIC,
	) = symbol(shortName, kind, pkg, sourceId, visibility)

	private fun nestedClass(
		shortName: String,
		outer: String,
		pkg: String = PKG,
		sourceId: String = JAR_A,
	) = symbol(
		shortName = "$outer\$$shortName",
		kind = JvmSymbolKind.CLASS,
		pkg = pkg,
		sourceId = sourceId,
		data = JvmClassInfo(containingClassName = "${pkg.replace('.', '/')}/$outer"),
	).copy(shortName = shortName)

	private fun member(
		shortName: String,
		owner: String,
		pkg: String = PKG,
	) = symbol(
		shortName = "$owner#$shortName",
		kind = JvmSymbolKind.FUNCTION,
		pkg = pkg,
		data = JvmFunctionInfo(containingClassName = "${pkg.replace('.', '/')}/$owner"),
	).copy(shortName = shortName)

	private fun topLevelFunction(
		shortName: String,
		pkg: String = PKG,
	) = symbol("#$shortName", JvmSymbolKind.FUNCTION, pkg, data = JvmFunctionInfo()).copy(shortName = shortName)

	private fun typeAlias(
		shortName: String,
		pkg: String = PKG,
	) = symbol(shortName, JvmSymbolKind.TYPE_ALIAS, pkg, data = JvmTypeAliasInfo())

	private suspend fun JvmSymbolIndex.insertEveryShapeNamed(name: String) {
		insert(topLevelClass(name))
		insert(nestedClass(name, outer = "Outer"))
		insert(member(name, owner = "Outer"))
		insert(topLevelFunction(name))
		insert(typeAlias(name, pkg = "$PKG.aliases"))
	}

	@Test
	fun `an exact-name lookup returns only the top-level class`() =
		runTest {
			val index = unfilteredIndex()
			index.insertEveryShapeNamed("Widget")

			val found = index.findTopLevelClassesNamed("Widget", sourceIds = null).toList()

			assertThat(found.map { it.key }).containsExactly("com/example/Widget")
		}

	@Test
	fun `a prefix lookup returns only top-level classes`() =
		runTest {
			val index = unfilteredIndex()
			index.insertEveryShapeNamed("Widget")

			val found = index.findTopLevelClassesByPrefix("Widg", sourceIds = null, limit = 10).toList()

			assertThat(found.map { it.key }).containsExactly("com/example/Widget")
		}

	@Test
	fun `a prefix lookup ignores case`() =
		runTest {
			val index = unfilteredIndex()
			index.insert(topLevelClass("Widget"))

			val found = index.findTopLevelClassesByPrefix("wIDG", sourceIds = null, limit = 10).toList()

			assertThat(found.map { it.shortName }).containsExactly("Widget")
		}

	@Test
	fun `a prefix lookup spends its limit only on top-level classes`() =
		runTest {
			val index = unfilteredIndex()
			repeat(20) { index.insert(nestedClass("Widget$it", outer = "Outer")) }
			index.insert(topLevelClass("WidgetZ"))

			val found = index.findTopLevelClassesByPrefix("Widget", sourceIds = null, limit = 5).toList()

			assertThat(found.map { it.shortName }).containsExactly("WidgetZ")
		}

	@Test
	fun `an in-package lookup returns only the package's top-level classes`() =
		runTest {
			val index = unfilteredIndex()
			index.insertEveryShapeNamed("Widget")
			index.insert(typeAlias("Alias"))
			index.insert(topLevelClass("Elsewhere", pkg = "com.other"))

			val found = index.findTopLevelClassesInPackage(PKG, sourceIds = null).toList()

			assertThat(found.map { it.key }).containsExactly("com/example/Widget")
		}

	@Test
	fun `a file facade and a package-private class are top-level classes`() =
		runTest {
			val index = unfilteredIndex()
			index.insert(topLevelClass("WidgetKt", kind = JvmSymbolKind.FILE_FACADE))
			index.insert(topLevelClass("Hidden", visibility = JvmVisibility.PACKAGE_PRIVATE))

			assertThat(index.findTopLevelClassesNamed("WidgetKt", sourceIds = null).toList()).hasSize(1)
			assertThat(index.findTopLevelClassesNamed("Hidden", sourceIds = null).toList()).hasSize(1)
			assertThat(index.findTopLevelClassesByPrefix("", sourceIds = null, limit = 10).map { it.shortName }.toList())
				.containsExactly("WidgetKt", "Hidden")
			assertThat(index.findTopLevelClassesInPackage(PKG, sourceIds = null).map { it.shortName }.toList())
				.containsExactly("WidgetKt", "Hidden")
		}

	@Test
	fun `every class query is scoped to the requested sources`() =
		runTest {
			val index = unfilteredIndex()
			index.insert(topLevelClass("Widget", sourceId = JAR_A))
			index.insert(topLevelClass("Widget", sourceId = JAR_B))
			val scope = listOf(JAR_B)

			assertThat(index.findTopLevelClassesNamed("Widget", scope).map { it.sourceId }.toList()).containsExactly(JAR_B)
			assertThat(index.findTopLevelClassesByPrefix("Wid", scope, limit = 10).map { it.sourceId }.toList())
				.containsExactly(JAR_B)
			assertThat(index.findTopLevelClassesInPackage(PKG, scope).map { it.sourceId }.toList()).containsExactly(JAR_B)
		}

	@Test
	fun `every class query drops a requested source that is not active`() =
		runTest {
			val index = filteredIndex(JAR_A)
			index.insert(topLevelClass("Widget", sourceId = JAR_A))
			index.insert(topLevelClass("Widget", sourceId = JAR_B))
			val scope = listOf(JAR_A, JAR_B)

			assertThat(index.findTopLevelClassesNamed("Widget", scope).map { it.sourceId }.toList()).containsExactly(JAR_A)
			assertThat(index.findTopLevelClassesByPrefix("Wid", scope, limit = 10).map { it.sourceId }.toList())
				.containsExactly(JAR_A)
			assertThat(index.findTopLevelClassesInPackage(PKG, scope).map { it.sourceId }.toList()).containsExactly(JAR_A)
		}

	@Test
	fun `a package exists when it holds a top-level class`() =
		runTest {
			val index = unfilteredIndex()
			index.insert(topLevelClass("Widget", pkg = "com.example.ui"))

			assertThat(index.containsPackage("com.example.ui", sourceIds = null)).isTrue()
			assertThat(index.subpackages("com.example", sourceIds = null)).containsExactly("com.example.ui")
			assertThat(index.subpackages("", sourceIds = null)).containsExactly("com")
		}

	@Test
	fun `a package holding only a file facade exists`() =
		runTest {
			val index = unfilteredIndex()
			index.insert(topLevelClass("UtilsKt", pkg = "com.example.util", kind = JvmSymbolKind.FILE_FACADE))

			assertThat(index.containsPackage("com.example.util", sourceIds = null)).isTrue()
		}

	@Test
	fun `a package holding no top-level class file does not exist`() =
		runTest {
			val index = unfilteredIndex()
			index.insert(nestedClass("Inner", outer = "Outer", pkg = "com.nested"))
			index.insert(member("helper", owner = "Outer", pkg = "com.members"))
			index.insert(topLevelFunction("launch", pkg = "com.functions"))
			index.insert(typeAlias("Alias", pkg = "com.aliases"))

			assertThat(index.subpackages("", sourceIds = null)).isEmpty()
			assertThat(index.containsPackage("com.nested", sourceIds = null)).isFalse()
		}

	@Test
	fun `package lookups are scoped to the requested active sources`() =
		runTest {
			val index = filteredIndex(JAR_A, JAR_B)
			index.insert(topLevelClass("A", pkg = "com.fromA", sourceId = JAR_A))
			index.insert(topLevelClass("B", pkg = "com.fromB", sourceId = JAR_B))
			index.insert(topLevelClass("C", pkg = "com.fromC", sourceId = JAR_C))

			assertThat(index.subpackages("com", sourceIds = listOf(JAR_B, JAR_C))).containsExactly("com.fromB")
			assertThat(index.containsPackage("com.fromA", sourceIds = listOf(JAR_B))).isFalse()
			assertThat(index.containsPackage("com.fromC", sourceIds = listOf(JAR_C))).isFalse()
		}

	private companion object {
		const val PKG = "com.example"
		const val JAR_A = "a.jar"
		const val JAR_B = "b.jar"
		const val JAR_C = "c.jar"
	}
}
