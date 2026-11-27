package com.itsaky.androidide.lsp.kotlin.completion

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.junit.Test

/** Requirements for what member completion offers after `receiver.`, by kind of receiver. */
class MemberCompletionSymbolsTest : KtLspTest() {
	private val declarations =
		"""
		package test

		enum class Color { RED, GREEN }

		class WithCompanion {
			companion object {
				fun make(): WithCompanion = WithCompanion()
			}
		}

		object Registry {
			fun lookup(): Int = 0
		}
		""".trimIndent()

	private class MemberNames(
		val members: List<String>,
		val hasExtensionReceiver: Boolean,
	)

	/** The member names offered after the receiver of the qualified expression whose text is [qualified]. */
	private fun membersAfter(
		qualified: String,
		body: String,
	): MemberNames {
		createSourceFile("test/Declarations.kt", declarations)
		val file: KtFile =
			createSourceFile("test/Use.kt", "package test\n\nimport java.util.Collections\n\nfun use() {\n    $body\n}\n")
		val receiver =
			PsiTreeUtil
				.findChildrenOfType(file, KtQualifiedExpression::class.java)
				.single { it.text == qualified }
				.receiverExpression
		return analyzeMaybeDanglingForTest(file) {
			val symbols = memberCompletionSymbols(receiver, isSafeCall = false) { true }!!
			MemberNames(
				members = symbols.members.mapNotNull { (it as? KaNamedSymbol)?.name?.asString() },
				hasExtensionReceiver = symbols.extensionReceiverType != null,
			)
		}
	}

	@Test
	fun `members after a Java class name are its static members, not Unit's`() {
		val names = membersAfter("Collections.emptyList<String>()", "Collections.emptyList<String>()")

		assertThat(names.members).contains("emptyList")
		assertThat(names.members).doesNotContain("hashCode")
		assertThat(names.hasExtensionReceiver).isFalse()
	}

	@Test
	fun `members after an enum class name include its entries`() {
		val names = membersAfter("Color.RED", "Color.RED")

		assertThat(names.members).containsAtLeast("RED", "GREEN")
	}

	@Test
	fun `members after a class name with a companion include the companion's members`() {
		val names = membersAfter("WithCompanion.make()", "WithCompanion.make()")

		assertThat(names.members).contains("make")
		assertThat(names.hasExtensionReceiver).isTrue()
	}

	@Test
	fun `members after a value are its type's members`() {
		val names = membersAfter("\"abc\".length", "\"abc\".length")

		assertThat(names.members).contains("length")
		assertThat(names.hasExtensionReceiver).isTrue()
	}

	@Test
	fun `members after an object name are the object's members`() {
		val names = membersAfter("Registry.lookup()", "Registry.lookup()")

		assertThat(names.members).contains("lookup")
		assertThat(names.hasExtensionReceiver).isTrue()
	}
}
