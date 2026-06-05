package com.itsaky.androidide.lsp.kotlin.fixtures

import com.google.common.truth.Truth.assertThat
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.junit.Test

class KtLspTestEnvironmentTest : KtLspTest() {

    @Test
    fun `source file is created and visible to the project`() {
        val file = createSourceFile("Hello.kt", "fun hello() = \"hi\"")
        assertThat(file.name).isEqualTo("Hello.kt")
        assertThat(file.declarations).hasSize(1)
    }

    @Test
    fun `analysis resolves named function symbol`() {
        val file = createSourceFile("Greet.kt", """
            fun greet(name: String): String = "Hello, ${'$'}name"
        """.trimIndent())

        analyze(file) {
            val fn = file.declarations.filterIsInstance<KtNamedFunction>().first()
            val sym = fn.symbol as? KaNamedFunctionSymbol
            assertThat(sym).isNotNull()
            assertThat(sym!!.name.asString()).isEqualTo("greet")
        }
    }

    @Test
    fun `analysis resolves cross-file class reference`() {
        createSourceFile("com/example/Foo.kt", """
            package com.example
            class Foo(val value: Int)
        """.trimIndent())

        val file = createSourceFile("com/example/Bar.kt", """
            package com.example
            fun makeFoo(): Foo = Foo(42)
        """.trimIndent())

        analyze(file) {
            val fn = file.declarations.filterIsInstance<KtNamedFunction>().first()
            val sym = fn.symbol as? KaNamedFunctionSymbol
            assertThat(sym).isNotNull()
            assertThat(sym!!.returnType.toString()).contains("Foo")
        }
    }
}
