package com.itsaky.androidide.lsp.kotlin.completion

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import org.jetbrains.kotlin.analysis.api.symbols.receiverType
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.junit.Test

/** Requirements for offering extensions declared as members of the implicit receivers after `receiver.`. */
class MemberExtensionCandidatesTest : KtLspTest() {
	private val declarations =
		"""
		package test

		open class BaseBuilder {
			fun String.inherited(): Int = 1
		}

		class Builder : BaseBuilder() {
			fun String.shout(): Int = 2
		}

		class Scope<T> {
			fun T.wrap(): Int = 3
		}

		class JavaBacked : java.util.ArrayList<String>() {
			fun String.own(): Int = 4
			fun plain(): Int = 5
		}
		""".trimIndent()

	@Test
	fun `member extension names come from the Kotlin classes of the implicit receivers' hierarchies`() {
		createSourceFile("test/Declarations.kt", declarations)
		val file: KtFile = createSourceFile("test/Use.kt", "package test\n\nfun JavaBacked.inJava() {\n    \"x\".length\n}\n")
		val selector =
			PsiTreeUtil.findChildrenOfType(file, KtQualifiedExpression::class.java).single().selectorExpression!!

		val names = analyzeMaybeDanglingForTest(file) { memberExtensionNames(file.scopeContext(selector)) }

		assertThat(names.map { it.asString() }).containsExactly("own")
	}

	private class Lookup(
		val candidates: List<String>,
		val enumeratedCandidates: List<String>,
	)

	/** Candidates after the receiver of `[qualified]` inside [function], with an index that knows nothing. */
	private fun lookUp(
		function: String,
		qualified: String,
	): Lookup {
		createSourceFile("test/Declarations.kt", declarations)
		val file: KtFile = createSourceFile("test/Use.kt", "package test\n\n$function {\n    $qualified\n}\n")
		val qualifiedExpression =
			PsiTreeUtil.findChildrenOfType(file, KtQualifiedExpression::class.java).single { it.text == qualified }
		return analyzeMaybeDanglingForTest(file) {
			val receiverType = qualifiedExpression.receiverExpression.expressionType!!
			val scopeContext = file.scopeContext(qualifiedExpression.selectorExpression!!)
			val found = extensionCallableCandidates(receiverType, scopeContext, { emptySet() }) { true }
			val enumerated =
				scopeContext
					.compositeScope()
					.callables { true }
					.filter { it.isExtension && it.receiverType?.let { r -> receiverType.isSubtypeOf(r) } == true }
			Lookup(
				candidates = found.mapNotNull { it.callableId?.callableName?.asString() }.toList(),
				enumeratedCandidates =
					enumerated
						.mapNotNull { it.callableId?.callableName?.asString() }
						.filter { it in setOf("inherited", "shout", "wrap") }
						.toList(),
			)
		}
	}

	@Test
	fun `a member extension declared in a Kotlin implicit receiver class is offered`() {
		assertThat(lookUp("fun Builder.build()", "\"x\".length").candidates).contains("shout")
	}

	@Test
	fun `a member extension inherited from a Kotlin superclass of the implicit receiver is offered`() {
		assertThat(lookUp("fun Builder.build()", "\"x\".length").candidates).contains("inherited")
	}

	/**
	 * Pins today's behaviour: the receiver check compares against the unsubstituted `T`, so a generic
	 * implicit receiver's member extension is not offered. A fix for that flips this test.
	 */
	@Test
	fun `a member extension of a generic implicit receiver is not offered`() {
		assertThat(lookUp("fun Scope<String>.inScope()", "\"x\".length").candidates).doesNotContain("wrap")
	}

	@Test
	fun `member extensions offered are the ones enumerating every scope finds`() {
		for ((function, qualified) in listOf("fun Builder.build()" to "\"x\".length", "fun Scope<String>.inScope()" to "\"x\".length")) {
			val lookup = lookUp(function, qualified)

			assertThat(lookup.candidates.filter { it in setOf("inherited", "shout", "wrap") })
				.containsExactlyElementsIn(lookup.enumeratedCandidates)
		}
	}
}
