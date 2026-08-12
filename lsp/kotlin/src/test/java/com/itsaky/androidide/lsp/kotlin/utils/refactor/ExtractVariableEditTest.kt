package com.itsaky.androidide.lsp.kotlin.utils.refactor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Rewrite construction, with no PSI and no analysis session involved.
 *
 * Every assertion is on the **resulting file text** rather than on offsets. Indentation is the thing
 * most likely to be wrong here -- code-action edits bypass the editor's auto-indent, so the emitted
 * text has to be final -- and a range assertion cannot see an indentation bug at all.
 */
class ExtractVariableEditTest {
	private fun apply(
		text: String,
		rewrite: RewriteSpan,
	): String = text.substring(0, rewrite.span.start) + rewrite.newText + text.substring(rewrite.span.end)

	private fun spanOf(
		text: String,
		snippet: String,
		fromIndex: Int = 0,
	): TextSpan {
		val start = text.indexOf(snippet, fromIndex)
		require(start >= 0) { "'$snippet' not found" }
		return TextSpan(start, start + snippet.length)
	}

	private fun allSpansOf(
		text: String,
		snippet: String,
	): List<TextSpan> {
		val spans = mutableListOf<TextSpan>()
		var from = 0
		while (true) {
			val start = text.indexOf(snippet, from)
			if (start < 0) break
			spans += TextSpan(start, start + snippet.length)
			from = start + snippet.length
		}
		return spans
	}

	private fun rewrite(
		text: String,
		candidate: TextSpan,
		anchorForm: AnchorForm,
		occurrences: List<TextSpan>,
		name: String,
		replaceAll: Boolean,
	) = buildExtractVariableRewrite(
		fileText = text,
		candidateSpan = candidate,
		scope = ScopeOption("scope", anchorForm, occurrences),
		name = name,
		replaceAll = replaceAll,
	)

	@Test
	fun `inserts the declaration above the statement and replaces the selected occurrence`() {
		val text = "fun f(items: List<Int>) {\n\tprintln(items.size * 2)\n}"
		val candidate = spanOf(text, "items.size * 2")

		val result = rewrite(text, candidate, AnchorForm.ExistingBlock, listOf(candidate), "size", replaceAll = false)!!

		assertEquals(
			"fun f(items: List<Int>) {\n" +
				"\tval size = items.size * 2\n" +
				"\tprintln(size)\n" +
				"}",
			apply(text, result),
		)
	}

	@Test
	fun `replace-all rewrites every occurrence and anchors above the first`() {
		val text =
			"fun f(items: List<Int>) {\n" +
				"\tprintln(items.size * 2)\n" +
				"\tlog(items.size * 2)\n" +
				"\tuse(items.size * 2)\n" +
				"}"
		val occurrences = allSpansOf(text, "items.size * 2")
		// The user selected the middle one; the declaration must still hoist above the first.
		val candidate = occurrences[1]

		val result = rewrite(text, candidate, AnchorForm.ExistingBlock, occurrences, "size", replaceAll = true)!!

		assertEquals(
			"fun f(items: List<Int>) {\n" +
				"\tval size = items.size * 2\n" +
				"\tprintln(size)\n" +
				"\tlog(size)\n" +
				"\tuse(size)\n" +
				"}",
			apply(text, result),
		)
	}

	@Test
	fun `replace-all off leaves the other occurrences alone`() {
		val text =
			"fun f(items: List<Int>) {\n" +
				"\tprintln(items.size * 2)\n" +
				"\tlog(items.size * 2)\n" +
				"}"
		val occurrences = allSpansOf(text, "items.size * 2")

		val result = rewrite(text, occurrences[0], AnchorForm.ExistingBlock, occurrences, "size", replaceAll = false)!!

		assertEquals(
			"fun f(items: List<Int>) {\n" +
				"\tval size = items.size * 2\n" +
				"\tprintln(size)\n" +
				"\tlog(items.size * 2)\n" +
				"}",
			apply(text, result),
		)
	}

	@Test
	fun `matches the file's space indentation rather than assuming tabs`() {
		val text = "fun f(items: List<Int>) {\n    println(items.size * 2)\n}"
		val candidate = spanOf(text, "items.size * 2")

		val result = rewrite(text, candidate, AnchorForm.ExistingBlock, listOf(candidate), "size", replaceAll = false)!!

		assertEquals(
			"fun f(items: List<Int>) {\n" +
				"    val size = items.size * 2\n" +
				"    println(size)\n" +
				"}",
			apply(text, result),
		)
	}

	@Test
	fun `keeps CRLF line endings when the file uses them`() {
		val text = "fun f(items: List<Int>) {\r\n\tprintln(items.size * 2)\r\n}"
		val candidate = spanOf(text, "items.size * 2")

		val result = rewrite(text, candidate, AnchorForm.ExistingBlock, listOf(candidate), "size", replaceAll = false)!!

		assertEquals(
			"fun f(items: List<Int>) {\r\n" +
				"\tval size = items.size * 2\r\n" +
				"\tprintln(size)\r\n" +
				"}",
			apply(text, result),
		)
	}

	@Test
	fun `deeper indentation is preserved`() {
		val text = "class C {\n\tfun f(items: List<Int>) {\n\t\tprintln(items.size * 2)\n\t}\n}"
		val candidate = spanOf(text, "items.size * 2")

		val result = rewrite(text, candidate, AnchorForm.ExistingBlock, listOf(candidate), "size", replaceAll = false)!!

		assertEquals(
			"class C {\n" +
				"\tfun f(items: List<Int>) {\n" +
				"\t\tval size = items.size * 2\n" +
				"\t\tprintln(size)\n" +
				"\t}\n" +
				"}",
			apply(text, result),
		)
	}

	@Test
	fun `wraps a braceless if branch in braces`() {
		val text = "fun f(c: Boolean, a: A) {\n\tif (c) log(a.b)\n}"
		val candidate = spanOf(text, "a.b")
		val body = spanOf(text, "log(a.b)")
		val form =
			AnchorForm.WrapInBraces(
				bodyStart = body.start,
				bodyEnd = body.end,
				indent = "\t",
				innerIndent = "\t\t",
			)

		val result = rewrite(text, candidate, form, listOf(candidate), "b", replaceAll = false)!!

		assertEquals(
			"fun f(c: Boolean, a: A) {\n" +
				"\tif (c) {\n" +
				"\t\tval b = a.b\n" +
				"\t\tlog(b)\n" +
				"\t}\n" +
				"}",
			apply(text, result),
		)
	}

	@Test
	fun `converts an expression body to a block body with return`() {
		val text = "fun area(r: Int) = r * r + r * r"
		val occurrences = allSpansOf(text, "r * r")
		val form =
			AnchorForm.ConvertExpressionBody(
				assignStart = text.indexOf('='),
				bodyStart = occurrences.first().start,
				bodyEnd = text.length,
				indent = "",
				innerIndent = "\t",
				needsReturn = true,
			)

		val result = rewrite(text, occurrences.first(), form, occurrences, "square", replaceAll = true)!!

		assertEquals(
			"fun area(r: Int) {\n" +
				"\tval square = r * r\n" +
				"\treturn square + square\n" +
				"}",
			apply(text, result),
		)
	}

	@Test
	fun `omits return when the expression body function returns Unit`() {
		val text = "fun show(a: A) = log(a.b)"
		val candidate = spanOf(text, "a.b")
		val form =
			AnchorForm.ConvertExpressionBody(
				assignStart = text.indexOf('='),
				bodyStart = text.indexOf("log(a.b)"),
				bodyEnd = text.length,
				indent = "",
				innerIndent = "\t",
				needsReturn = false,
			)

		val result = rewrite(text, candidate, form, listOf(candidate), "b", replaceAll = false)!!

		assertEquals(
			"fun show(a: A) {\n" +
				"\tval b = a.b\n" +
				"\tlog(b)\n" +
				"}",
			apply(text, result),
		)
	}

	@Test
	fun `writes the return type into the signature when the declaration has none`() {
		val text = "fun area(r: Int) = r * r"
		val candidate = spanOf(text, "r * r")
		val form =
			AnchorForm.ConvertExpressionBody(
				assignStart = text.indexOf('='),
				bodyStart = candidate.start,
				bodyEnd = text.length,
				indent = "",
				innerIndent = "\t",
				needsReturn = true,
				returnTypeText = "Int",
			)

		val result = rewrite(text, candidate, form, listOf(candidate), "squared", replaceAll = false)!!

		assertEquals(
			"fun area(r: Int): Int {\n" +
				"\tval squared = r * r\n" +
				"\treturn squared\n" +
				"}",
			apply(text, result),
		)
	}

	@Test
	fun `null when there is nothing to replace`() {
		val text = "fun f() {}"
		assertNull(
			buildExtractVariableRewrite(
				fileText = text,
				candidateSpan = TextSpan(0, 3),
				scope = ScopeOption("scope", AnchorForm.ExistingBlock, emptyList()),
				name = "value",
				replaceAll = true,
			),
		)
	}

	@Test
	fun `null when an occurrence lies outside the file`() {
		val text = "fun f() {}"
		assertNull(
			buildExtractVariableRewrite(
				fileText = text,
				candidateSpan = TextSpan(0, 3),
				scope = ScopeOption("scope", AnchorForm.ExistingBlock, listOf(TextSpan(0, text.length + 5))),
				name = "value",
				replaceAll = true,
			),
		)
	}

	@Test
	fun `position index line and column all agree`() {
		val text = "aa\nbbb\nc"
		val position = positionAt(text, text.indexOf('c'))
		assertEquals(2, position.line)
		assertEquals(0, position.column)
		assertEquals(7, position.index)
	}
}
