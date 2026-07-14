package com.itsaky.androidide.lsp.kotlin.utils

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.models.Position
import com.itsaky.androidide.models.Range
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class SurroundWithTryCatchTest {

	@Test
	fun `single unindented line is wrapped`() {
		val edit = computeSurroundWithTryCatchEdit("foo()", 0, 0)
		assertThat(edit).isNotNull()
		assertThat(edit!!.newText).isEqualTo(
			"try {\n\tfoo()\n} catch (e: Exception) {\n\te.printStackTrace()\n}"
		)
		assertThat(edit.range).isEqualTo(
			Range(Position(0, 0, 0), Position(0, 5, 5))
		)
	}

	@Test
	fun `indented multi-line block preserves and deepens indentation`() {
		val text = "fun f() {\n\tval a = read()\n\tprocess(a)\n}"
		val edit = computeSurroundWithTryCatchEdit(text, 1, 2)
		assertThat(edit).isNotNull()
		assertThat(edit!!.newText).isEqualTo(
			"\ttry {\n\t\tval a = read()\n\t\tprocess(a)\n\t} catch (e: Exception) {\n\t\te.printStackTrace()\n\t}"
		)
		assertThat(edit.range).isEqualTo(
			Range(Position(1, 0, 10), Position(2, 11, 37))
		)
	}

	@Test
	fun `blank lines inside the span are not indented`() {
		val edit = computeSurroundWithTryCatchEdit("a()\n\nb()", 0, 2)
		assertThat(edit!!.newText).isEqualTo(
			"try {\n\ta()\n\n\tb()\n} catch (e: Exception) {\n\te.printStackTrace()\n}"
		)
	}

	@Test
	fun `whitespace-only span returns null`() {
		assertThat(computeSurroundWithTryCatchEdit("\n  \n", 0, 1)).isNull()
	}

	@Test
	fun `out-of-range span returns null`() {
		assertThat(computeSurroundWithTryCatchEdit("foo()", 0, 5)).isNull()
		assertThat(computeSurroundWithTryCatchEdit("foo()", -1, 0)).isNull()
		assertThat(computeSurroundWithTryCatchEdit("foo()", 2, 1)).isNull()
	}
}
