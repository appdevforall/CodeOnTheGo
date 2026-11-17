package com.itsaky.androidide.lsp.kotlin.completion

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import org.jetbrains.kotlin.analysis.api.components.KaScopeContext
import org.jetbrains.kotlin.analysis.api.components.KaScopeKind
import org.jetbrains.kotlin.analysis.api.symbols.receiverType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.junit.Test

/** Requirements for finding the extensions member completion offers after `receiver.`. */
class ExtensionCallableCandidatesTest : KtLspTest() {
	private val declarations =
		"""
		package test

		open class Base
		class Derived : Base()
		class Unrelated
		class Outer {
			class Inner
		}

		fun Base.onBase(): Int = 1
		fun Unrelated.onUnrelated(): Int = 2
		fun <T> T.onAnything(): Int = 3
		fun Outer.Inner.onInner(): Int = 4
		""".trimIndent()

	/**
	 * What the JVM symbol index stores: receiver internal name to extension names. `kotlin/Any` holds the
	 * stdlib's `Any?.hashCode()` and `Any?.toString()`, which the library index has from metadata.
	 */
	private val indexedExtensions =
		mapOf(
			"kotlin/Any" to setOf("hashCode", "toString"),
			"test/Base" to setOf("onBase"),
			"test/Unrelated" to setOf("onUnrelated"),
			"T0" to setOf("onAnything"),
			"test/Outer\$Inner" to setOf("onInner"),
		)

	private val completeIndex: (Set<String>) -> Set<Name> = { receiverNames ->
		receiverNames.flatMap { indexedExtensions[it].orEmpty() }.map(Name::identifier).toSet()
	}

	private class Lookup(
		val candidates: List<String>,
		val receiverNamesAsked: Set<String>,
		val enumeratedCandidates: List<String>,
		val unindexedScopeCandidates: List<String>,
	)

	/** Extension candidates after the receiver of `[qualified]` in a function whose body is [body]. */
	private fun lookUp(
		qualified: String,
		body: String,
		index: (Set<String>) -> Set<Name> = completeIndex,
	): Lookup {
		createSourceFile("test/Declarations.kt", declarations)
		val file: KtFile = createSourceFile("test/Use.kt", "package test\n\nfun use() {\n$body\n}\n")
		val qualifiedExpression =
			PsiTreeUtil.findChildrenOfType(file, KtQualifiedExpression::class.java).single { it.text == qualified }
		val asked = mutableSetOf<String>()
		return analyzeMaybeDanglingForTest(file) {
			val receiverType: KaType = qualifiedExpression.receiverExpression.expressionType!!
			val scopeContext: KaScopeContext = file.scopeContext(qualifiedExpression.selectorExpression!!)
			val found =
				extensionCallableCandidates(receiverType, scopeContext, { names ->
					asked += names
					index(names)
				}) { true }

			fun applicableExtensionNames(scopeKinds: (KaScopeKind) -> Boolean): List<String> =
				scopeContext
					.compositeScope(scopeKinds)
					.callables { true }
					.filter { it.isExtension && it.receiverType?.let { r -> receiverType.isSubtypeOf(r) } == true }
					.mapNotNull { it.callableId?.callableName?.asString() }
					.toList()
			Lookup(
				candidates = found.mapNotNull { it.callableId?.callableName?.asString() }.toList(),
				receiverNamesAsked = asked,
				enumeratedCandidates = applicableExtensionNames { true },
				unindexedScopeCandidates =
					applicableExtensionNames {
						it is KaScopeKind.LocalScope ||
							it is KaScopeKind.TypeScope ||
							it is KaScopeKind.TypeParameterScope ||
							it is KaScopeKind.StaticMemberScope ||
							it is KaScopeKind.ScriptMemberScope
					},
			)
		}
	}

	@Test
	fun `an extension on a supertype of the receiver is offered`() {
		assertThat(lookUp("Derived().hashCode()", "    Derived().hashCode()").candidates).contains("onBase")
	}

	@Test
	fun `an extension on an unrelated type is not offered`() {
		assertThat(lookUp("Derived().hashCode()", "    Derived().hashCode()").candidates).doesNotContain("onUnrelated")
	}

	/** Local, type, type-parameter, static member and script scopes hold declarations the index never sees, so they are enumerated. */
	@Test
	fun `extensions in scopes the index never sees are found without it`() {
		val lookup =
			lookUp("Derived().hashCode()", "    fun Derived.local(): Int = 5\n    Derived().hashCode()", index = { emptySet() })

		assertThat(lookup.candidates).containsExactlyElementsIn(lookup.unindexedScopeCandidates)
	}

	@Test
	fun `a top-level extension the index does not name is not looked up`() {
		val lookup = lookUp("Derived().hashCode()", "    Derived().hashCode()", index = { emptySet() })

		assertThat(lookup.candidates).doesNotContain("onBase")
	}

	@Test
	fun `the index is asked about the receiver type and each of its supertypes`() {
		val asked = lookUp("Derived().hashCode()", "    Derived().hashCode()").receiverNamesAsked

		assertThat(asked).containsAtLeast("test/Derived", "test/Base", "kotlin/Any")
	}

	@Test
	fun `an extension on a nested class is found under the source index's spelling of its name`() {
		val lookup = lookUp("Outer.Inner().hashCode()", "    Outer.Inner().hashCode()")

		assertThat(lookup.candidates).contains("onInner")
	}

	@Test
	fun `with a complete index the candidates are the ones enumerating every scope finds`() {
		val lookup = lookUp("Derived().hashCode()", "    fun Derived.local(): Int = 5\n    Derived().hashCode()")

		assertThat(lookup.candidates).containsExactlyElementsIn(lookup.enumeratedCandidates)
	}

	@Test
	fun `index receiver names spell a nested class both ways`() {
		val nested = ClassId(FqName("test"), FqName("Outer.Inner"), isLocal = false)
		val topLevel = ClassId(FqName("test"), FqName("Base"), isLocal = false)

		assertThat(extensionIndexReceiverNames(nested)).containsExactly("test/Outer\$Inner", "test/Outer.Inner")
		assertThat(extensionIndexReceiverNames(topLevel)).containsExactly("test/Base")
	}
}
