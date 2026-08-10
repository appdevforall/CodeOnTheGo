package com.itsaky.androidide.lsp.kotlin.utils.refactor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The emitted text, with every candidate built by hand -- no PSI, no analysis. Assertions are on the
 * resulting file text, the only kind that catches an indentation or off-by-one error.
 */
class ExtractMethodEditTest {
	private val file =
		"package p\n" +
			"class C {\n" +
			"\tfun demo(a: Int, b: Int): Int {\n" +
			"\t\tval sum = a + b\n" +
			"\t\treturn sum\n" +
			"\t}\n" +
			"}\n"

	private val enclosingStart = file.indexOf("fun demo")
	private val enclosingEnd = file.indexOf("\t}\n}") + 2

	private fun candidate(
		span: TextSpan,
		body: ExtractedBody,
		callSite: CallSiteForm,
		parameters: List<MethodParameter> = emptyList(),
		returnTypeText: String? = null,
		modifiers: List<String> = listOf("private"),
		annotations: List<String> = emptyList(),
		receiverTypeText: String? = null,
	) = ExtractMethodCandidate(
		label = "region",
		span = span,
		suggestedName = "extracted",
		takenNames = emptySet(),
		annotations = annotations,
		modifiers = modifiers,
		receiverTypeText = receiverTypeText,
		parameters = parameters,
		returnTypeText = returnTypeText,
		body = body,
		callSite = callSite,
		insertOffset = enclosingEnd,
		insertIndent = "\t",
	)

	/** Applies the rewrites in the order they are returned, exactly as the language client does. */
	private fun apply(
		text: String,
		rewrites: List<RewriteSpan>,
	): String =
		rewrites.fold(text) { current, rewrite ->
			current.substring(0, rewrite.span.start) + rewrite.newText + current.substring(rewrite.span.end)
		}

	@Test
	fun `the function insertion comes before the call site`() {
		val span = TextSpan(file.indexOf("a + b"), file.indexOf("a + b") + "a + b".length)
		val rewrites =
			buildExtractMethodRewrites(
				file,
				candidate(
					span,
					ExtractedBody.ExpressionBody(needsReturn = true),
					CallSiteForm.Call,
					parameters = listOf(MethodParameter("a", "Int"), MethodParameter("b", "Int")),
					returnTypeText = "Int",
				),
				"total",
			)

		assertNotNull(rewrites)
		assertEquals(2, rewrites!!.size)
		assertTrue(
			"the insertion must be at a higher offset than the call site",
			rewrites[0].span.start > rewrites[1].span.start,
		)
	}

	@Test
	fun `an expression region becomes a call and a returning function`() {
		val span = TextSpan(file.indexOf("a + b"), file.indexOf("a + b") + "a + b".length)
		val rewrites =
			buildExtractMethodRewrites(
				file,
				candidate(
					span,
					ExtractedBody.ExpressionBody(needsReturn = true),
					CallSiteForm.Call,
					parameters = listOf(MethodParameter("a", "Int"), MethodParameter("b", "Int")),
					returnTypeText = "Int",
				),
				"total",
			)!!

		assertEquals(
			"package p\n" +
				"class C {\n" +
				"\tfun demo(a: Int, b: Int): Int {\n" +
				"\t\tval sum = total(a, b)\n" +
				"\t\treturn sum\n" +
				"\t}\n" +
				"\n" +
				"\tprivate fun total(a: Int, b: Int): Int {\n" +
				"\t\treturn a + b\n" +
				"\t}\n" +
				"}\n",
			apply(file, rewrites),
		)
	}

	@Test
	fun `a statement range with one output assigns at the call site`() {
		val span = TextSpan(file.indexOf("val sum"), file.indexOf("val sum") + "val sum = a + b".length)
		val rewrites =
			buildExtractMethodRewrites(
				file,
				candidate(
					span,
					ExtractedBody.StatementBody(trailingReturn = "return sum"),
					CallSiteForm.AssignOutput("sum"),
					parameters = listOf(MethodParameter("a", "Int"), MethodParameter("b", "Int")),
					returnTypeText = "Int",
				),
				"total",
			)!!

		assertEquals(
			"package p\n" +
				"class C {\n" +
				"\tfun demo(a: Int, b: Int): Int {\n" +
				"\t\tval sum = total(a, b)\n" +
				"\t\treturn sum\n" +
				"\t}\n" +
				"\n" +
				"\tprivate fun total(a: Int, b: Int): Int {\n" +
				"\t\tval sum = a + b\n" +
				"\t\treturn sum\n" +
				"\t}\n" +
				"}\n",
			apply(file, rewrites),
		)
	}

	@Test
	fun `a tail return region returns the call`() {
		val span = TextSpan(file.indexOf("return sum"), file.indexOf("return sum") + "return sum".length)
		val rewrites =
			buildExtractMethodRewrites(
				file,
				candidate(
					span,
					ExtractedBody.StatementBody(trailingReturn = null),
					CallSiteForm.Return,
					parameters = listOf(MethodParameter("sum", "Int")),
					returnTypeText = "Int",
				),
				"finish",
			)!!

		assertEquals(
			"package p\n" +
				"class C {\n" +
				"\tfun demo(a: Int, b: Int): Int {\n" +
				"\t\tval sum = a + b\n" +
				"\t\treturn finish(sum)\n" +
				"\t}\n" +
				"\n" +
				"\tprivate fun finish(sum: Int): Int {\n" +
				"\t\treturn sum\n" +
				"\t}\n" +
				"}\n",
			apply(file, rewrites),
		)
	}

	@Test
	fun `a multi-line statement range is reindented under the new function`() {
		val text =
			"package p\n" +
				"fun demo(a: Int) {\n" +
				"\tif (a > 0) {\n" +
				"\t\tprintln(a)\n" +
				"\t}\n" +
				"}\n"
		val start = text.indexOf("if (a > 0)")
		val rewrites =
			buildExtractMethodRewrites(
				text,
				ExtractMethodCandidate(
					label = "region",
					span = TextSpan(start, text.indexOf("\t}\n}") + 2),
					suggestedName = "extracted",
					takenNames = emptySet(),
					annotations = emptyList(),
					modifiers = listOf("private"),
					receiverTypeText = null,
					parameters = listOf(MethodParameter("a", "Int")),
					returnTypeText = null,
					body = ExtractedBody.StatementBody(trailingReturn = null),
					callSite = CallSiteForm.Call,
					insertOffset = text.length - 1,
					insertIndent = "",
				),
				"report",
			)!!

		assertEquals(
			"package p\n" +
				"fun demo(a: Int) {\n" +
				"\treport(a)\n" +
				"}\n" +
				"\n" +
				"private fun report(a: Int) {\n" +
				"\tif (a > 0) {\n" +
				"\t\tprintln(a)\n" +
				"\t}\n" +
				"}\n",
			apply(text, rewrites),
		)
	}

	@Test
	fun `a CRLF file keeps CRLF`() {
		val text =
			"package p\r\n" +
				"fun demo(a: Int) {\r\n" +
				"\tprintln(a)\r\n" +
				"}\r\n"
		val start = text.indexOf("println(a)")
		val rewrites =
			buildExtractMethodRewrites(
				text,
				ExtractMethodCandidate(
					label = "region",
					span = TextSpan(start, start + "println(a)".length),
					suggestedName = "extracted",
					takenNames = emptySet(),
					annotations = emptyList(),
					modifiers = listOf("private"),
					receiverTypeText = null,
					parameters = listOf(MethodParameter("a", "Int")),
					returnTypeText = null,
					body = ExtractedBody.StatementBody(trailingReturn = null),
					callSite = CallSiteForm.Call,
					insertOffset = text.length - 2,
					insertIndent = "",
				),
				"report",
			)!!

		assertTrue(rewrites.all { !it.newText.contains("\n") || it.newText.contains("\r\n") })
		assertTrue(apply(text, rewrites).contains("\r\nprivate fun report(a: Int) {\r\n"))
	}

	@Test
	fun `the signature preview matches what is emitted`() {
		val span = TextSpan(file.indexOf("a + b"), file.indexOf("a + b") + "a + b".length)
		val subject =
			candidate(
				span,
				ExtractedBody.ExpressionBody(needsReturn = true),
				CallSiteForm.Call,
				parameters = listOf(MethodParameter("a", "Int")),
				returnTypeText = "Int",
				modifiers = listOf("private", "suspend"),
				annotations = listOf("@Composable"),
				receiverTypeText = "Foo",
			)

		assertEquals("@Composable private suspend fun Foo.total(a: Int): Int", subject.signatureText("total"))
		assertTrue(
			buildExtractMethodRewrites(file, subject, "total")!![0]
				.newText
				.contains("@Composable private suspend fun Foo.total(a: Int): Int {"),
		)
	}

	@Test
	fun `a span past the end of the text produces nothing`() {
		val subject =
			candidate(
				TextSpan(file.length - 1, file.length + 10),
				ExtractedBody.StatementBody(trailingReturn = null),
				CallSiteForm.Call,
			)

		assertNull(buildExtractMethodRewrites(file, subject, "total"))
	}
}
