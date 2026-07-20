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
		assertThat(edit.range.start.index).isEqualTo(0)
		assertThat(edit.range.end.index).isEqualTo(5)
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
		assertThat(edit.range.start.index).isEqualTo(10)
		assertThat(edit.range.end.index).isEqualTo(37)
	}

	@Test
	fun `blank lines inside the span are not indented`() {
		val edit = computeSurroundWithTryCatchEdit("a()\n\nb()", 0, 2)
		assertThat(edit!!.newText).isEqualTo(
			"try {\n\ta()\n\n\tb()\n} catch (e: Exception) {\n\te.printStackTrace()\n}"
		)
	}

	@Test
	fun `space-indented file produces a spaces-only body`() {
		val text = "fun f() {\n    val a = read()\n    process(a)\n}"
		val edit = computeSurroundWithTryCatchEdit(text, 1, 2)
		assertThat(edit).isNotNull()
		assertThat(edit!!.newText).isEqualTo(
			"    try {\n        val a = read()\n        process(a)\n" +
				"    } catch (e: Exception) {\n        e.printStackTrace()\n    }"
		)
		assertThat(edit.newText).doesNotContain("\t")
		assertThat(edit.range).isEqualTo(
			Range(Position(1, 0, 10), Position(2, 14, 43))
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

	@Test
	fun `trailing column-0 selection drops the unselected last line`() {
		assertThat(resolveSurroundLines(1, 2, 3, 0)).isEqualTo(1 to 2)
	}

	@Test
	fun `column-0 on a single-line selection is not trimmed`() {
		assertThat(resolveSurroundLines(2, 0, 2, 0)).isEqualTo(2 to 2)
	}

	@Test
	fun `non-zero end column keeps the last line`() {
		assertThat(resolveSurroundLines(1, 4, 3, 7)).isEqualTo(1 to 3)
	}

	@Test
	fun `partial mid-line columns still select whole lines`() {
		assertThat(resolveSurroundLines(0, 4, 0, 12)).isEqualTo(0 to 0)
		assertThat(resolveSurroundLines(0, 4, 2, 9)).isEqualTo(0 to 2)
	}

	@Test
	fun `CRLF file preserves carriage returns and replace indices`() {
		val edit = computeSurroundWithTryCatchEdit("a()\r\nb()", 0, 1)
		assertThat(edit).isNotNull()
		assertThat(edit!!.newText).isEqualTo(
			"try {\r\n\ta()\r\n\tb()\r\n} catch (e: Exception) {\r\n\te.printStackTrace()\r\n}"
		)
		assertThat(edit.range).isEqualTo(
			Range(Position(0, 0, 0), Position(1, 3, 8))
		)
	}

	@Test
	fun `stray whitespace-only line does not switch a tab file to spaces`() {
		val text = "fun f() {\n \n\tval a = read()\n\tprocess(a)\n}"
		val edit = computeSurroundWithTryCatchEdit(text, 2, 3)
		assertThat(edit).isNotNull()
		assertThat(edit!!.newText).isEqualTo(
			"\ttry {\n\t\tval a = read()\n\t\tprocess(a)\n\t} catch (e: Exception) {\n\t\te.printStackTrace()\n\t}"
		)
		assertThat(edit.newText).doesNotContain("    ")
	}
}
