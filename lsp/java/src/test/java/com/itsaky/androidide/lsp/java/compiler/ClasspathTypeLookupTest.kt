package com.itsaky.androidide.lsp.java.compiler

import com.google.common.truth.Truth.assertThat
import org.appdevforall.codeonthego.indexing.jvm.JvmClassInfo
import org.appdevforall.codeonthego.indexing.jvm.JvmSourceLanguage
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbol
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolKind
import org.appdevforall.codeonthego.indexing.jvm.JvmVisibility
import org.appdevforall.codeonthego.indexing.jvm.ModuleClasspathLookup
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class ClasspathTypeLookupTest {
	/** A stand-in for the index-backed classpath lookup: exact matches first, then case-insensitive prefix. */
	private class FakeClasspath(
		private val names: List<String>,
		private val visibilityOf: (String) -> JvmVisibility = { JvmVisibility.PUBLIC },
	) : ClasspathClassNames {
		override fun qualifiedNamesOf(simpleName: String) = names.filter { it.substringAfterLast('.') == simpleName }

		override fun qualifiedNamesByPrefix(
			prefix: String,
			limit: Int,
		): List<String> {
			val prefixed = names.filter { it.substringAfterLast('.').startsWith(prefix, ignoreCase = true) }
			return (qualifiedNamesOf(prefix) + prefixed).distinct().take(limit)
		}

		override fun classesNamed(simpleName: String): List<JvmSymbol> =
			qualifiedNamesOf(simpleName).map { fqName ->
				JvmSymbol(
					key = fqName,
					sourceId = "fake",
					name = fqName.replace('.', '/'),
					shortName = fqName.substringAfterLast('.'),
					packageName = fqName.substringBeforeLast('.', missingDelimiterValue = ""),
					kind = JvmSymbolKind.CLASS,
					language = JvmSourceLanguage.JAVA,
					visibility = visibilityOf(fqName),
					data = JvmClassInfo(),
				)
			}

		override fun isClass(qualifiedName: String) = qualifiedName in names

		override fun children(packageName: String) = emptyList<ModuleClasspathLookup.Child>()
	}

	private fun lookup(
		sources: List<String> = emptyList(),
		classpath: ClasspathClassNames? = null,
		boot: List<String> = emptyList(),
	) = ClasspathTypeLookup({ sources }, { classpath }, { boot })

	@Test
	fun `qualified names union source, classpath and boot classes`() {
		val types =
			lookup(
				sources = listOf("com.app.Widget"),
				classpath = FakeClasspath(listOf("com.lib.Widget")),
				boot = listOf("android.widget.Widget"),
			)

		assertThat(types.findQualifiedNames("Widget", false))
			.containsExactly("android.widget.Widget", "com.app.Widget", "com.lib.Widget")
	}

	@Test
	fun `a class present in several sources is returned once`() {
		val types =
			lookup(
				sources = listOf("com.shared.Thing"),
				classpath = FakeClasspath(listOf("com.shared.Thing")),
				boot = listOf("com.shared.Thing"),
			)

		assertThat(types.findQualifiedNames("Thing", false)).containsExactly("com.shared.Thing")
		assertThat(types.findTypeNamesMatching("Th", 10)).containsExactly("com.shared.Thing")
	}

	@Test
	fun `qualified names match the whole simple name only`() {
		val types = lookup(boot = listOf("java.util.ArrayList", "java.util.List"))

		assertThat(types.findQualifiedNames("List", false)).containsExactly("java.util.List")
	}

	@Test
	fun `only one qualified name is returned when asked for one`() {
		val types =
			lookup(
				sources = listOf("com.app.Widget"),
				classpath = FakeClasspath(listOf("com.lib.Widget")),
				boot = listOf("android.widget.Widget"),
			)

		assertThat(types.findQualifiedNames("Widget", true)).hasSize(1)
	}

	@Test
	fun `type names match a prefix ignoring case`() {
		val types =
			lookup(
				sources = listOf("com.app.ArrayAdapterX"),
				classpath = FakeClasspath(listOf("com.lib.ArrayMap")),
				boot = listOf("java.util.ArrayList", "java.util.List"),
			)

		assertThat(types.findTypeNamesMatching("array", 10))
			.containsExactly("com.app.ArrayAdapterX", "com.lib.ArrayMap", "java.util.ArrayList")
	}

	@Test
	fun `exact simple-name matches come first and survive the limit`() {
		val prefixed = (1..20).map { "com.lib.ViewThing$it" }
		val types =
			lookup(
				sources = listOf("com.app.ViewModelX"),
				classpath = FakeClasspath(prefixed),
				boot = listOf("android.view.View"),
			)

		val names = types.findTypeNamesMatching("View", 3)

		assertThat(names).hasSize(3)
		assertThat(names.first()).isEqualTo("android.view.View")
	}

	@Test
	fun `type names never exceed the limit`() {
		val types =
			lookup(
				sources = (1..5).map { "com.app.Foo$it" },
				classpath = FakeClasspath((1..5).map { "com.lib.Foo$it" }),
				boot = (1..5).map { "java.foo.Foo$it" },
			)

		assertThat(types.findTypeNamesMatching("Foo", 7)).hasSize(7)
	}

	@Test
	fun `a class the classpath repeats from the sources does not cost the limit a slot`() {
		val shared = (1..3).map { "com.shared.Bar$it" }
		val types =
			lookup(
				sources = shared,
				classpath = FakeClasspath(shared + (4..6).map { "com.lib.Bar$it" }),
			)

		assertThat(types.findTypeNamesMatching("Bar", 6)).hasSize(6)
	}

	@Test
	fun `a fuzzy near-miss is not a type name match`() {
		val types = lookup(boot = listOf("java.util.ArrayList"))

		assertThat(types.findTypeNamesMatching("ArgList", 50)).isEmpty()
	}

	@Test
	fun `no classpath lookup contributes nothing`() {
		val types = lookup(sources = listOf("com.app.Widget"), classpath = null)

		assertThat(types.findQualifiedNames("Widget", false)).containsExactly("com.app.Widget")
		assertThat(types.findTypeNamesMatching("Wid", 10)).containsExactly("com.app.Widget")
	}

	@Test
	fun `the classpath lookup is created for every request`() {
		var created = 0
		val types =
			ClasspathTypeLookup({ emptyList() }, {
				created++
				FakeClasspath(emptyList())
			}, { emptyList() })

		types.findQualifiedNames("A", false)
		types.findTypeNamesMatching("A", 5)

		assertThat(created).isEqualTo(2)
	}

	@Test
	fun `construction does not create the classpath lookup`() {
		var created = 0
		ClasspathTypeLookup({ emptyList() }, {
			created++
			null
		}, { emptyList() })

		assertThat(created).isEqualTo(0)
	}

	@Test
	fun `a package-private classpath class from another package is not importable`() {
		val types =
			lookup(
				classpath =
					FakeClasspath(
						listOf("com.lib.Hidden"),
						visibilityOf = { JvmVisibility.PACKAGE_PRIVATE },
					),
			)

		assertThat(types.findImportableQualifiedNames("Hidden", importingPackage = "com.app")).isEmpty()
	}

	@Test
	fun `a package-private classpath class in the importing package is importable`() {
		val types =
			lookup(
				classpath =
					FakeClasspath(
						listOf("com.lib.Hidden"),
						visibilityOf = { JvmVisibility.PACKAGE_PRIVATE },
					),
			)

		assertThat(types.findImportableQualifiedNames("Hidden", importingPackage = "com.lib"))
			.containsExactly("com.lib.Hidden")
	}

	@Test
	fun `a file-private classpath class from another package is not importable`() {
		val types =
			lookup(
				classpath =
					FakeClasspath(
						listOf("com.lib.Hidden"),
						visibilityOf = { JvmVisibility.PRIVATE },
					),
			)

		assertThat(types.findImportableQualifiedNames("Hidden", importingPackage = "com.app")).isEmpty()
	}

	@Test
	fun `a file-private classpath class in the importing package is importable`() {
		val types =
			lookup(
				classpath =
					FakeClasspath(
						listOf("com.lib.Hidden"),
						visibilityOf = { JvmVisibility.PRIVATE },
					),
			)

		assertThat(types.findImportableQualifiedNames("Hidden", importingPackage = "com.lib"))
			.containsExactly("com.lib.Hidden")
	}

	@Test
	fun `source and boot classes are importable regardless of visibility`() {
		val types =
			lookup(
				sources = listOf("com.app.PackagePrivateSource"),
				boot = listOf("android.PackagePrivateBoot"),
			)

		assertThat(types.findImportableQualifiedNames("PackagePrivateSource", importingPackage = "com.other"))
			.containsExactly("com.app.PackagePrivateSource")
		assertThat(types.findImportableQualifiedNames("PackagePrivateBoot", importingPackage = "com.other"))
			.containsExactly("android.PackagePrivateBoot")
	}

	@Test
	fun `a public classpath class is importable from any package`() {
		val types = lookup(classpath = FakeClasspath(listOf("com.lib.Widget")))

		assertThat(types.findImportableQualifiedNames("Widget", importingPackage = "com.app"))
			.containsExactly("com.lib.Widget")
	}
}
