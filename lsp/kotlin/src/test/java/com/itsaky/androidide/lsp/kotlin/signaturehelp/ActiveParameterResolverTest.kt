package com.itsaky.androidide.lsp.kotlin.signaturehelp

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.kotlin.compiler.read
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import org.jetbrains.kotlin.psi.KtCallElement
import org.junit.Test

class ActiveParameterResolverTest : KtLspTest() {

  private fun callAt(name: String, text: String, cursorMarker: String): Pair<KtCallElement, Int> {
    val file = createSourceFile(name, text)
    val offset = text.indexOf(cursorMarker).let { check(it >= 0); it }
    // `findEnclosingCall` only recognizes a call once the offset is past its callee (e.g. inside the
    // argument list); when the marker is the call itself (as in the empty-argument-list case below),
    // `offset` alone lands exactly on the callee's name. Look the call up a couple of characters
    // further in so callers can still use the marker's own `offset` as the cursor position they test.
    val call = env.project.read { findEnclosingCall(file, offset + 2) }!!
    return call to offset
  }

  @Test
  fun `first positional argument is index 0`() {
    val (call, offset) = callAt("A.kt",
      "fun f(a: Int, b: Int) {}\nfun g() { f(10, 20) }", "10")
    val idx = env.project.read { analyzeMaybeDanglingForTest(call) { rc -> computeActiveParameter(call, rc, offset) } }
    assertThat(idx).isEqualTo(0)
  }

  @Test
  fun `second positional argument is index 1`() {
    val (call, offset) = callAt("B.kt",
      "fun f(a: Int, b: Int) {}\nfun g() { f(10, 20) }", "20")
    val idx = env.project.read { analyzeMaybeDanglingForTest(call) { rc -> computeActiveParameter(call, rc, offset) } }
    assertThat(idx).isEqualTo(1)
  }

  @Test
  fun `named argument maps to its declared parameter index`() {
    // cursor on `a = 5`, but `a` is the first declared parameter -> index 0 even though written second
    val (call, offset) = callAt("C.kt",
      "fun f(a: Int, b: Int) {}\nfun g() { f(b = 1, a = 5) }", "a = 5")
    val idx = env.project.read { analyzeMaybeDanglingForTest(call) { rc -> computeActiveParameter(call, rc, offset) } }
    assertThat(idx).isEqualTo(0)
  }

  @Test
  fun `empty argument list is index 0`() {
    val (call, offset) = callAt("D.kt",
      "fun f(a: Int) {}\nfun g() { f() }", "f()")
    val cursor = offset + 2 // inside the parens
    val idx = env.project.read { analyzeMaybeDanglingForTest(call) { rc -> computeActiveParameter(call, rc, cursor) } }
    assertThat(idx).isEqualTo(0)
  }

  @Test
  fun `second vararg argument maps to the vararg parameter index`() {
    // cursor on the second `xs` argument (positionally index 2), declared vararg parameter is index 1
    val (call, offset) = callAt("E.kt",
      "fun f(a: Int, vararg xs: Int) {}\nfun g() { f(1, 22, 33) }", "33")
    val idx = env.project.read { analyzeMaybeDanglingForTest(call) { rc -> computeActiveParameter(call, rc, offset) } }
    assertThat(idx).isEqualTo(1)
  }
}
