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
		rawStringSpans = emptyList(),
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
	fun `an insertion before the region puts the call site first`() {
		// A local-function target: the new function is declared ahead of the one that calls it, so the
		// descending-order invariant now puts the call site at the head of the list.
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
				).copy(insertOffset = enclosingStart, modifiers = emptyList()),
				"total",
			)

		assertNotNull(rewrites)
		assertEquals(2, rewrites!!.size)
		assertTrue(
			"the call site must come first when the insertion precedes the region",
			rewrites[0].span.start > rewrites[1].span.start,
		)
		assertEquals(span, rewrites[0].span)
		assertEquals(enclosingStart, rewrites[1].span.start)
	}

	@Test
	fun `an insertion before the region declares the function ahead of its anchor`() {
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
				).copy(insertOffset = enclosingStart, modifiers = emptyList()),
				"total",
			)

		assertEquals(
			"package p\n" +
				"class C {\n" +
				"\tfun total(a: Int, b: Int): Int {\n" +
				"\t\treturn a + b\n" +
				"\t}\n" +
				"\n" +
				"\tfun demo(a: Int, b: Int): Int {\n" +
				"\t\tval sum = total(a, b)\n" +
				"\t\treturn sum\n" +
				"\t}\n" +
				"}\n",
			apply(file, rewrites!!),
		)
	}

	@Test
	fun `an insertion inside the region is rejected`() {
		val span = TextSpan(file.indexOf("a + b"), file.indexOf("a + b") + "a + b".length)
		val rewrites =
			buildExtractMethodRewrites(
				file,
				candidate(
					span,
					ExtractedBody.ExpressionBody(needsReturn = true),
					CallSiteForm.Call,
				).copy(insertOffset = span.start + 1),
				"total",
			)

		assertNull(rewrites)
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
					rawStringSpans = emptyList(),
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
	fun `a multi-line CRLF region is reindented and keeps CRLF throughout`() {
		/*
		 * Mirrors "a multi-line statement range is reindented under the new function" with \r\n in
		 * place of every \n, so indentedBodyLines's split(newline) path -- the CRLF-sensitive code --
		 * actually runs, not just the declaration builder's own append(newline) calls.
		 */
		val text =
			"package p\r\n" +
				"fun demo(a: Int) {\r\n" +
				"\tif (a > 0) {\r\n" +
				"\t\tprintln(a)\r\n" +
				"\t}\r\n" +
				"}\r\n"
		val start = text.indexOf("if (a > 0)")
		val rewrites =
			buildExtractMethodRewrites(
				text,
				ExtractMethodCandidate(
					label = "region",
					span = TextSpan(start, text.indexOf("\t}\r\n}") + 2),
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
					rawStringSpans = emptyList(),
				),
				"report",
			)!!

		assertEquals(
			"package p\r\n" +
				"fun demo(a: Int) {\r\n" +
				"\treport(a)\r\n" +
				"}\r\n" +
				"\r\n" +
				"private fun report(a: Int) {\r\n" +
				"\tif (a > 0) {\r\n" +
				"\t\tprintln(a)\r\n" +
				"\t}\r\n" +
				"}\r\n",
			apply(text, rewrites),
		)
	}

	@Test
	fun `a Unit-valued expression omits the return type and the return keyword`() {
		val span = TextSpan(file.indexOf("a + b"), file.indexOf("a + b") + "a + b".length)
		val rewrites =
			buildExtractMethodRewrites(
				file,
				candidate(
					span,
					ExtractedBody.ExpressionBody(needsReturn = false),
					CallSiteForm.Call,
					returnTypeText = null,
				),
				"log",
			)!!

		assertEquals(
			"package p\n" +
				"class C {\n" +
				"\tfun demo(a: Int, b: Int): Int {\n" +
				"\t\tval sum = log()\n" +
				"\t\treturn sum\n" +
				"\t}\n" +
				"\n" +
				"\tprivate fun log() {\n" +
				"\t\ta + b\n" +
				"\t}\n" +
				"}\n",
			apply(file, rewrites),
		)
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

	@Test
	fun `a raw string keeps its interior lines when the body indent differs from the base`() {
		val quotes = "\"\"\""
		val nested =
			"package p\n" +
				"class C {\n" +
				"\tfun demo() {\n" +
				"\t\tif (true) {\n" +
				"\t\t\tsend($quotes\n" +
				"line one\n" +
				"\t\t\t\tline two\n" +
				"$quotes)\n" +
				"\t\t}\n" +
				"\t}\n" +
				"}\n"
		val span = TextSpan(nested.indexOf("send("), nested.indexOf("$quotes)") + "$quotes)".length)
		val rewrites =
			buildExtractMethodRewrites(
				nested,
				candidate(
					span,
					ExtractedBody.StatementBody(trailingReturn = null),
					CallSiteForm.Call,
				).copy(
					insertOffset = nested.indexOf("\t}\n}") + 2,
					insertIndent = "\t",
					rawStringSpans = listOf(TextSpan(nested.indexOf(quotes), nested.indexOf("$quotes)") + quotes.length)),
				),
				"emit",
			)

		val text = apply(nested, rewrites!!)

		assertTrue("the first line takes the body indent", text.contains("\n\t\tsend($quotes\n"))
		assertTrue("an unindented literal line stays unindented", text.contains("\nline one\n"))
		assertTrue("an indented literal line keeps its own indent", text.contains("\n\t\t\t\tline two\n"))
		assertTrue("the closing delimiter line is untouched", text.contains("\n$quotes)\n"))
	}

	@Test
	fun `a raw string is left alone when the body and base indents match`() {
		// The base indent is not a prefix of an unindented literal line, so stripping it is a no-op while
		// the body indent is still prefixed. Equal indents are not a safe case.
		val quotes = "\"\"\""
		val flat =
			"package p\n" +
				"class C {\n" +
				"\tfun demo() {\n" +
				"\t\tsend($quotes\n" +
				"line one\n" +
				"$quotes)\n" +
				"\t}\n" +
				"}\n"
		val span = TextSpan(flat.indexOf("send("), flat.indexOf("$quotes)") + "$quotes)".length)
		val rewrites =
			buildExtractMethodRewrites(
				flat,
				candidate(
					span,
					ExtractedBody.StatementBody(trailingReturn = null),
					CallSiteForm.Call,
				).copy(
					insertOffset = flat.indexOf("\t}\n}") + 2,
					insertIndent = "\t",
					rawStringSpans = listOf(TextSpan(flat.indexOf(quotes), flat.indexOf("$quotes)") + quotes.length)),
				),
				"emit",
			)

		val text = apply(flat, rewrites!!)

		assertTrue("an unindented literal line stays unindented", text.contains("\nline one\n"))
		assertTrue("the closing delimiter line is untouched", text.contains("\n$quotes)\n"))
	}
}
