package com.itsaky.androidide.lsp.kotlin.format

import com.facebook.ktfmt.format.ParseError
import com.itsaky.androidide.lsp.models.CodeFormatException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KotlinCodeFormatterTest {
	@Test
	fun `formats the whole file as one edit`() {
		val source = "fun  main( ) {println( \"hi\" )}\n"

		val result = KotlinCodeFormatter.format(source)

		val edit = result.indexedTextEdits.single()
		assertTrue(result.isIndexed)
		assertEquals(0, edit.start)
		assertEquals(source.length, edit.end)
		assertEquals("fun main() {\n    println(\"hi\")\n}\n", edit.newText)
	}

	@Test
	fun `reports a syntax error with its position`() {
		val error =
			assertThrows(CodeFormatException::class.java) {
				KotlinCodeFormatter.format("fun main() {\n    println(\"hi\"\n}\n")
			}

		assertTrue(error.cause is ParseError)
		assertTrue(error.message, error.message!!.matches(Regex("""\d+:\d+: .+""")))
	}

	@Test
	fun `counts the shebang line in a syntax error position`() {
		val error =
			assertThrows(CodeFormatException::class.java) {
				KotlinCodeFormatter.format("#!/usr/bin/env kotlin\n\nfun broken( {\n    println(\"hi\")\n}\n")
			}

		assertEquals("3:12: Expecting ')'", error.message)
	}
}
