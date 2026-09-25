package org.appdevforall.codeonthego.indexing.jvm

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.appdevforall.codeonthego.indexing.InMemoryIndex
import org.appdevforall.codeonthego.indexing.api.IndexQuery
import org.appdevforall.codeonthego.indexing.jvm.ModuleClasspathLookup.Child
import org.appdevforall.codeonthego.indexing.util.BackgroundIndexer
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** A module's class lookups answer what its classpath trie did, from the three JVM symbol indexes. */
@RunWith(JUnit4::class)
class ModuleClasspathLookupTest {
	private val library = index()
	private val generated = index()
	private val moduleOutput = index()

	private fun index(): JvmSymbolIndex {
		val backing = InMemoryIndex(JvmSymbolDescriptor)
		return object : JvmSymbolIndex(backing, BackgroundIndexer(backing)) {
			override fun visibleSourceIds(): Collection<String>? = null
		}
	}

	/**
	 * An index returning its matches in simple-name order, as SQLite does when it walks the name
	 * index, so which rows a limited query returns does not depend on hash order.
	 */
	private fun nameOrderedIndex(): JvmSymbolIndex {
		val backing = InMemoryIndex(JvmSymbolDescriptor)
		return object : JvmSymbolIndex(backing, BackgroundIndexer(backing)) {
			override fun visibleSourceIds(): Collection<String>? = null

			override fun query(query: IndexQuery): Sequence<JvmSymbol> {
				val limit = if (query.limit <= 0) Int.MAX_VALUE else query.limit
				return super.query(query.copy(limit = 0)).sortedBy { it.shortName }.take(limit)
			}
		}
	}

	private fun lookup(
		library: JvmSymbolIndex? = this.library,
		generated: JvmSymbolIndex? = this.generated,
		moduleOutput: JvmSymbolIndex? = this.moduleOutput,
		classpath: List<String> = listOf(LIB_JAR, R_JAR, OWN_JAR),
		moduleOutputs: List<String> = listOf(OWN_JAR),
	) = ModuleClasspathLookup(library, generated, moduleOutput) { ClasspathScope(classpath, moduleOutputs) }

	private fun topLevelClass(
		qualifiedName: String,
		sourceId: String = LIB_JAR,
		visibility: JvmVisibility = JvmVisibility.PUBLIC,
	): JvmSymbol {
		val internalName = qualifiedName.replace('.', '/')
		return JvmSymbol(
			key = internalName,
			sourceId = sourceId,
			name = internalName,
			shortName = qualifiedName.substringAfterLast('.'),
			packageName = qualifiedName.substringBeforeLast('.', missingDelimiterValue = ""),
			kind = JvmSymbolKind.CLASS,
			language = JvmSourceLanguage.JAVA,
			visibility = visibility,
			data = JvmClassInfo(internalName = internalName),
		)
	}

	private fun nestedClass(
		outer: String,
		simpleName: String,
	): JvmSymbol {
		val outerInternal = outer.replace('.', '/')
		return topLevelClass("$outer\$$simpleName").copy(
			shortName = simpleName,
			packageName = outer.substringBeforeLast('.'),
			data = JvmClassInfo(internalName = "$outerInternal\$$simpleName", containingClassName = outerInternal),
		)
	}

	@Test
	fun `a class only on a jar outside the module's classpath is not offered`() =
		runTest {
			library.insert(topLevelClass("com.other.Widget", sourceId = OTHER_JAR))

			val lookup = lookup()

			assertThat(lookup.qualifiedNamesOf("Widget")).isEmpty()
			assertThat(lookup.qualifiedNamesByPrefix("Wid", limit = 10)).isEmpty()
			assertThat(lookup.children("com")).isEmpty()
			assertThat(lookup.isPackage("com.other")).isFalse()
			assertThat(lookup.isClass("com.other.Widget")).isFalse()
		}

	@Test
	fun `each index is scoped to its own share of the module's classpath`() =
		runTest {
			library.insert(topLevelClass("com.lib.Widget", sourceId = LIB_JAR))
			generated.insert(topLevelClass("com.app.R", sourceId = R_JAR))
			moduleOutput.insert(topLevelClass("com.app.Screen", sourceId = OWN_JAR))
			moduleOutput.insert(topLevelClass("com.unrelated.Screen", sourceId = UNRELATED_MODULE_JAR))

			val lookup = lookup()

			assertThat(lookup.qualifiedNamesOf("Widget")).containsExactly("com.lib.Widget")
			assertThat(lookup.qualifiedNamesOf("R")).containsExactly("com.app.R")
			assertThat(lookup.qualifiedNamesOf("Screen")).containsExactly("com.app.Screen")
		}

	@Test
	fun `a class held by two indexes is offered once`() =
		runTest {
			library.insert(topLevelClass("com.example.Widget", sourceId = LIB_JAR))
			generated.insert(topLevelClass("com.example.Widget", sourceId = R_JAR))
			library.insert(topLevelClass("com.example.sub.Gadget", sourceId = LIB_JAR))
			generated.insert(topLevelClass("com.example.sub.Gadget", sourceId = R_JAR))

			val lookup = lookup()

			assertThat(lookup.qualifiedNamesOf("Widget")).containsExactly("com.example.Widget")
			assertThat(lookup.classesNamed("Widget")).hasSize(1)
			assertThat(lookup.qualifiedNamesByPrefix("Wid", limit = 10)).containsExactly("com.example.Widget")
			assertThat(lookup.children("com.example"))
				.containsExactly(
					Child("sub", "com.example.sub", isClass = false),
					Child("Widget", "com.example.Widget", isClass = true),
				)
		}

	@Test
	fun `a prefix lookup puts exact simple-name matches first under a small limit`() =
		runTest {
			repeat(5) { library.insert(topLevelClass("com.example.Widget$it")) }
			generated.insert(topLevelClass("com.example.Widget", sourceId = R_JAR))

			val found = lookup().qualifiedNamesByPrefix("Widget", limit = 2)

			assertThat(found).hasSize(2)
			assertThat(found.first()).isEqualTo("com.example.Widget")
		}

	@Test
	fun `a prefix lookup fills its limit with case-insensitive prefix matches`() =
		runTest {
			library.insert(topLevelClass("com.example.WidgetA"))
			generated.insert(topLevelClass("com.example.widgetB", sourceId = R_JAR))
			moduleOutput.insert(topLevelClass("com.example.WIDGETC", sourceId = OWN_JAR))

			val found = lookup().qualifiedNamesByPrefix("widget", limit = 2)

			assertThat(found).hasSize(2)
		}

	@Test
	fun `a prefix lookup fills its limit when one index holds a class from two jars`() =
		runTest {
			val library = nameOrderedIndex()
			val jars = listOf("/libs/kotlin-stdlib.jar", "/libs/kotlin-stdlib-jdk8.jar")
			jars.forEach { library.insert(topLevelClass("com.example.Widget", sourceId = it)) }
			library.insert(topLevelClass("com.example.WidgetB", sourceId = jars.first()))

			val found = lookup(library = library, classpath = jars).qualifiedNamesByPrefix("Wid", limit = 2)

			assertThat(found).containsExactly("com.example.Widget", "com.example.WidgetB")
		}

	@Test
	fun `children mixes a package's direct subpackages and top-level classes`() =
		runTest {
			library.insert(topLevelClass("com.example.Widget"))
			library.insert(topLevelClass("com.example.ui.Button"))
			library.insert(topLevelClass("com.example.ui.deep.Deep"))
			library.insert(nestedClass("com.example.Widget", "Inner"))

			val children = lookup().children("com.example")

			assertThat(children)
				.containsExactly(
					Child("ui", "com.example.ui", isClass = false),
					Child("Widget", "com.example.Widget", isClass = true),
				)
		}

	@Test
	fun `children of the default package are the root packages and default-package classes`() =
		runTest {
			library.insert(topLevelClass("com.example.Widget"))
			library.insert(topLevelClass("Loose"))

			val lookup = lookup()

			assertThat(lookup.children(""))
				.containsExactly(
					Child("com", "com", isClass = false),
					Child("Loose", "Loose", isClass = true),
				)
			assertThat(lookup.isClass("Loose")).isTrue()
		}

	@Test
	fun `a package or class check answers for top-level classes only`() =
		runTest {
			library.insert(topLevelClass("com.example.Widget"))
			library.insert(nestedClass("com.example.Widget", "Inner"))
			library.insert(topLevelClass("com.other.Gadget"))

			val lookup = lookup()

			assertThat(lookup.isPackage("com.example")).isTrue()
			assertThat(lookup.isPackage("com")).isTrue()
			assertThat(lookup.isPackage("com.example.Widget")).isFalse()
			assertThat(lookup.isClass("com.example.Widget")).isTrue()
			assertThat(lookup.isClass("com.example.Widget.Inner")).isFalse()
			assertThat(lookup.isClass("com.example")).isFalse()
			assertThat(lookup.isClass("com.example.Gadget")).isFalse()
		}

	@Test
	fun `classes named carry the package and visibility a visibility filter needs`() =
		runTest {
			library.insert(topLevelClass("com.example.Widget", visibility = JvmVisibility.PACKAGE_PRIVATE))

			val found = lookup().classesNamed("Widget").single()

			assertThat(found.fqName).isEqualTo("com.example.Widget")
			assertThat(found.packageName).isEqualTo("com.example")
			assertThat(found.visibility).isEqualTo(JvmVisibility.PACKAGE_PRIVATE)
		}

	@Test
	fun `an unregistered index contributes nothing`() =
		runTest {
			library.insert(topLevelClass("com.example.Widget"))

			val lookup = lookup(generated = null, moduleOutput = null)

			assertThat(lookup.qualifiedNamesOf("Widget")).containsExactly("com.example.Widget")
			assertThat(lookup.qualifiedNamesByPrefix("W", limit = 5)).containsExactly("com.example.Widget")
			assertThat(lookup.children("com")).containsExactly(Child("example", "com.example", isClass = false))
			assertThat(lookup.isPackage("com.example")).isTrue()
			assertThat(lookup.isClass("com.example.Widget")).isTrue()
			assertThat(lookup(library = null, generated = null, moduleOutput = null).qualifiedNamesOf("Widget")).isEmpty()
		}

	@Test
	fun `constructing a lookup does not resolve the module's classpath`() {
		var resolved = false

		ModuleClasspathLookup(library, generated, moduleOutput) {
			resolved = true
			ClasspathScope(emptyList(), emptyList())
		}

		assertThat(resolved).isFalse()
	}

	private companion object {
		const val LIB_JAR = "/libs/lib.jar"
		const val R_JAR = "/app/build/R.jar"
		const val OWN_JAR = "/app/build/classes.jar"
		const val OTHER_JAR = "/libs/other.jar"
		const val UNRELATED_MODULE_JAR = "/unrelated/build/classes.jar"
	}
}
