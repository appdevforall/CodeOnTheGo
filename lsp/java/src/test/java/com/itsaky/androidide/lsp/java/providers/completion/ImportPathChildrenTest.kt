package com.itsaky.androidide.lsp.java.providers.completion

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.java.compiler.ClasspathPackages
import com.itsaky.androidide.lsp.java.providers.completion.ImportCompletionProvider.RequireMemberCompletionException
import com.itsaky.androidide.utils.ClassTrie
import org.appdevforall.codeonthego.indexing.jvm.ModuleClasspathLookup.Child
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class ImportPathChildrenTest {
	private fun trie(vararg classes: String) = ClassTrie().apply { classes.forEach(::append) }

	private fun children(
		sources: ClassTrie = trie(),
		classpath: ClasspathPackages? = null,
		boot: List<ClassTrie> = emptyList(),
	) = ImportPathChildren(sources, classpath, boot)

	private fun Sequence<Child>.names() = map { it.name }.toList()

	@Test
	fun `a classpath class before the last segment of a package walk requires member completion`() {
		val children = children(classpath = FakeClasspathPackages(listOf("com.lib.Outer")))

		assertThrows(RequireMemberCompletionException::class.java) {
			children.ofPackage("com.lib.Outer.Inner")
		}
	}

	@Test
	fun `a classpath class as the last segment of a package walk does not require member completion`() {
		val children = children(classpath = FakeClasspathPackages(listOf("com.lib.Outer")))

		assertThat(children.ofPackage("com.lib.Outer").names()).isEmpty()
	}

	@Test
	fun `a classpath class named by the whole path requires member completion`() {
		val children = children(classpath = FakeClasspathPackages(listOf("com.lib.Outer")))

		assertThrows(RequireMemberCompletionException::class.java) {
			children.of("com.lib.Outer").toList()
		}
	}

	@Test
	fun `a source class named by the whole path requires member completion`() {
		val children = children(sources = trie("com.app.Main"))

		assertThrows(RequireMemberCompletionException::class.java) {
			children.of("com.app.Main").toList()
		}
	}

	@Test
	fun `packages and classes of the classpath are told apart`() {
		val children = children(classpath = FakeClasspathPackages(listOf("com.lib.Widget", "com.lib.impl.Engine")))

		assertThat(children.ofPackage("com.lib").toList()).containsExactly(
			Child("impl", "com.lib.impl", isClass = false),
			Child("Widget", "com.lib.Widget", isClass = true),
		)
	}

	@Test
	fun `children come from the sources, then the classpath, then the boot classpath`() {
		val children =
			children(
				sources = trie("com.app.Main"),
				classpath = FakeClasspathPackages(listOf("com.lib.Widget")),
				boot = listOf(trie("com.boot.Thing")),
			)

		assertThat(children.of("com").names()).containsExactly("app", "lib", "boot").inOrder()
	}

	@Test
	fun `a name the sources offered is not offered again by the classpath or the boot classpath`() {
		val children =
			children(
				sources = trie("com.shared.A"),
				classpath = FakeClasspathPackages(listOf("com.shared.B")),
				boot = listOf(trie("com.shared.C")),
			)

		assertThat(children.of("com").toList()).containsExactly(Child("shared", "com.shared", isClass = false))
	}

	@Test
	fun `a name the package walk offered is not offered again by the lookup`() {
		val children = children(classpath = FakeClasspathPackages(listOf("com.lib.Widget")))

		assertThat(children.ofPackage("com.lib").names()).containsExactly("Widget")
		assertThat(children.of("com.lib").names()).isEmpty()
	}

	@Test
	fun `a package only the classpath holds is offered`() {
		val children = children(sources = trie("com.app.Main"), classpath = FakeClasspathPackages(listOf("com.indexed.Widget")))

		assertThat(children.ofPackage("com").names()).contains("indexed")
	}

	@Test
	fun `boot classpath packages are offered`() {
		val children = children(boot = listOf(trie("java.util.List"), trie("android.view.View")))

		assertThat(children.of("").names()).containsExactly("java", "android")
	}

	@Test
	fun `without a classpath the sources and the boot classpath still answer`() {
		val children = children(sources = trie("com.app.Main"), classpath = null, boot = listOf(trie("com.boot.Thing")))

		assertThat(children.ofPackage("com").names()).containsExactly("app", "boot")
	}
}
