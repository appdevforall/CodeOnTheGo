package com.itsaky.androidide.editor.ui

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.models.MarkupContent
import com.itsaky.androidide.lsp.models.ParameterInformation
import com.itsaky.androidide.lsp.models.SignatureInformation
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SignatureHelpWindowTest {

  private fun sig(label: String, paramCount: Int): SignatureInformation {
    val params = (0 until paramCount).map { ParameterInformation("p$it: Int", MarkupContent()) }
    return SignatureInformation(label, MarkupContent(), params)
  }

  @Test
  fun `signatureName returns text before the opening paren`() {
    assertThat(SignatureHelpWindow.signatureName("foo(p0: Int, p1: Int)")).isEqualTo("foo")
  }

  @Test
  fun `signatureName returns whole label when there is no paren`() {
    assertThat(SignatureHelpWindow.signatureName("foo")).isEqualTo("foo")
  }

  @Test
  fun `applicableSignatures drops overloads without enough parameters`() {
    val input = listOf(sig("a(p0: Int)", 1), sig("b(p0: Int, p1: Int)", 2))
    val result = SignatureHelpWindow.applicableSignatures(input, 1)
    assertThat(result).hasSize(1)
    assertThat(result[0].label).isEqualTo("b(p0: Int, p1: Int)")
  }

  @Test
  fun `applicableSignatures does not mutate an immutable input list`() {
    val input = java.util.Collections.unmodifiableList(listOf(sig("a(p0: Int)", 1)))
    // Must not throw UnsupportedOperationException:
    val result = SignatureHelpWindow.applicableSignatures(input, 5)
    assertThat(result).isEmpty()
    assertThat(input).hasSize(1)
  }
}
