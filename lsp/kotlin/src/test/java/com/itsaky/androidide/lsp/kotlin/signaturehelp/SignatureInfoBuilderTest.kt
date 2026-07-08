package com.itsaky.androidide.lsp.kotlin.signaturehelp

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.junit.Test

class SignatureInfoBuilderTest : KtLspTest() {
	@Test
	fun `builds a fancy label and parameter list for a named function`() {
		val file = createSourceFile("A.kt", "fun greet(name: String, times: Int): String = name")
		val info =
			analyze(file) {
				val fn = file.declarations.filterIsInstance<KtNamedFunction>().first()
				buildSignatureInformation(fn.symbol as KaFunctionSymbol)
			}
		assertThat(info.label).isEqualTo("greet(name: String, times: Int)")
		assertThat(info.parameters.map { it.label })
			.containsExactly("name: String", "times: Int")
			.inOrder()
		assertThat(info.documentation.value).isEmpty()
	}

	@Test
	fun `builds a label with no parameters`() {
		val file = createSourceFile("B.kt", "fun now() {}")
		val info =
			analyze(file) {
				val fn = file.declarations.filterIsInstance<KtNamedFunction>().first()
				buildSignatureInformation(fn.symbol as KaFunctionSymbol)
			}
		assertThat(info.label).isEqualTo("now()")
		assertThat(info.parameters).isEmpty()
	}

	@Test
	fun `prefixes vararg parameters`() {
		val file = createSourceFile("C.kt", "fun sum(vararg xs: Int): Int = 0")
		val info =
			analyze(file) {
				val fn = file.declarations.filterIsInstance<KtNamedFunction>().first()
				buildSignatureInformation(fn.symbol as KaFunctionSymbol)
			}
		assertThat(info.parameters.single().label).isEqualTo("vararg xs: Int")
	}
}
