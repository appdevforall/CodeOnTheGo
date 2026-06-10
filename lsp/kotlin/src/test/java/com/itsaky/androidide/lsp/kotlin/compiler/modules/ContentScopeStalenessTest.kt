package com.itsaky.androidide.lsp.kotlin.compiler.modules

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.junit.Test

class ContentScopeStalenessTest : KtLspTest() {

	@Test
	fun `file resolvable by path but missing from a stale scope snapshot can still be analyzed`() {
		val a = createSourceFile(
			"demo/A.kt", """
            package demo
            fun a(): Int = 1
        """.trimIndent()
		)

		// Materialize the module's content scope and resolution scope NOW, while
		// A.kt is the only file on disk. Previously, this used to freeze
		// a module's filesScope that contains only A.kt.
		analyze(a) {
			val fn = a.declarations.filterIsInstance<KtNamedFunction>().first()
			assertThat(fn.symbol).isNotNull()
		}

		// B.kt is written AFTER the snapshot, WITHOUT invalidating the module scope.
		// This simulates a file appearing (or being refreshed to a new VirtualFile
		// instance) after the scope was last computed.
		val b = env.createSourceFile(
			"demo/B.kt", """
            package demo
            fun b(): Int = 2
        """.trimIndent(),
		)

		// B.kt is resolvable to the same source module by path, so analysis opens a
		// session for that module, but the stale snapshot scope does not contain B.kt.
		// This used to throw a KaBaseIllegalPsiException.
		analyze(b) {
			val fn = b.declarations.filterIsInstance<KtNamedFunction>().first()
			val sym = fn.symbol as? KaNamedFunctionSymbol
			assertThat(sym).isNotNull()
			assertThat(sym!!.name.asString()).isEqualTo("b")
		}
	}
}
