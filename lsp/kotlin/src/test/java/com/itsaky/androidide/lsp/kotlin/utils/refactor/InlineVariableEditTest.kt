package com.itsaky.androidide.lsp.kotlin.utils.refactor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The emitted text, with every plan built by hand -- no PSI, no analysis. Assertions are on the
 * resulting file text, the only kind that catches an indentation or off-by-one error.
 */
class InlineVariableEditTest {
	/** Applies the rewrites in the order they are returned, exactly as the language client does. */
	private fun apply(
		text: String,
		rewrites: List<RewriteSpan>,
	): String =
		rewrites.fold(text) { current, rewrite ->
			current.substring(0, rewrite.span.start) + rewrite.newText + current.substring(rewrite.span.end)
		}

	/** The span of [fragment], skipping [after] earlier occurrences of it. */
	private fun spanOf(
		text: String,
		fragment: String,
		after: Int = 0,
	): TextSpan {
		var start = -1
		repeat(after + 1) { start = text.indexOf(fragment, start + 1) }
		return TextSpan(start, start + fragment.length)
	}

	private fun plan(
		fileText: String,
		declaration: String,
		initializerText: String,
		references: List<InlineReference>,
		initializerNeedsParentheses: Boolean = false,
		canDeleteDeclaration: Boolean = true,
		cursorReferenceIndex: Int = 0,
		name: String = "x",
	) = InlineVariablePlan(
		fileText = fileText,
		documentVersion = 1,
		variableName = name,
		declarationSpan = spanOf(fileText, declaration),
		initializerText = initializerText,
		initializerNeedsParentheses = initializerNeedsParentheses,
		references = references,
		cursorPosition = InlineCursorPosition.Reference,
		cursorReferenceIndex = cursorReferenceIndex,
		canDeleteDeclaration = canDeleteDeclaration,
		modes = modesFor(InlineCursorPosition.Reference, references.count { it.isInlinable }),
		refusal = null,
	)

	private fun reference(
		fileText: String,
		fragment: String,
		after: Int = 0,
		isShortTemplateEntry: Boolean = false,
		exclusion: InlineExclusion? = null,
	) = InlineReference(spanOf(fileText, fragment, after), isShortTemplateEntry, exclusion)

	@Test
	fun `a binary initializer is parenthesised and the declaration line goes`() {
		val file =
			"package p\n" +
				"fun demo(a: Int, b: Int): Int {\n" +
				"\tval sum = a + b\n" +
				"\treturn sum * 2\n" +
				"}\n"
		val result =
			plan(
				fileText = file,
				declaration = "val sum = a + b",
				initializerText = "a + b",
				references = listOf(reference(file, "sum", after = 1)),
				initializerNeedsParentheses = true,
				name = "sum",
			)

		val rewrites = buildInlineVariableRewrites(result, InlineMode.AllReferences)

		assertNotNull(rewrites)
		assertEquals(
			"package p\n" +
				"fun demo(a: Int, b: Int): Int {\n" +
				"\treturn (a + b) * 2\n" +
				"}\n",
			apply(file, rewrites!!),
		)
	}

	@Test
	fun `an atomic initializer needs no parentheses`() {
		val file =
			"package p\n" +
				"fun demo(user: User): String {\n" +
				"\tval name = user.name\n" +
				"\treturn f(name)\n" +
				"}\n"
		val result =
			plan(
				fileText = file,
				declaration = "val name = user.name",
				initializerText = "user.name",
				references = listOf(reference(file, "name", after = 2)),
				name = "name",
			)

		val rewrites = buildInlineVariableRewrites(result, InlineMode.AllReferences)

		assertEquals(
			"package p\n" +
				"fun demo(user: User): String {\n" +
				"\treturn f(user.name)\n" +
				"}\n",
			apply(file, rewrites!!),
		)
	}

	@Test
	fun `a template entry is wrapped in braces when the value is not a plain name`() {
		val file =
			"package p\n" +
				"fun demo(a: Int, b: Int): String {\n" +
				"\tval sum = a + b\n" +
				"\treturn \"total: \$sum\"\n" +
				"}\n"
		val result =
			plan(
				fileText = file,
				declaration = "val sum = a + b",
				initializerText = "a + b",
				references = listOf(reference(file, "\$sum", isShortTemplateEntry = true)),
				initializerNeedsParentheses = true,
				name = "sum",
			)

		val rewrites = buildInlineVariableRewrites(result, InlineMode.AllReferences)

		// The braces already delimit the expression, so the parenthesisation is not applied on top.
		assertEquals(
			"package p\n" +
				"fun demo(a: Int, b: Int): String {\n" +
				"\treturn \"total: \${a + b}\"\n" +
				"}\n",
			apply(file, rewrites!!),
		)
	}

	@Test
	fun `a template entry stays in short form for a plain name`() {
		val file =
			"package p\n" +
				"fun demo(name: String): String {\n" +
				"\tval other = name\n" +
				"\treturn \"hi \$other\"\n" +
				"}\n"
		val result =
			plan(
				fileText = file,
				declaration = "val other = name",
				initializerText = "name",
				references = listOf(reference(file, "\$other", isShortTemplateEntry = true)),
				name = "other",
			)

		val rewrites = buildInlineVariableRewrites(result, InlineMode.AllReferences)

		assertEquals(
			"package p\n" +
				"fun demo(name: String): String {\n" +
				"\treturn \"hi \$name\"\n" +
				"}\n",
			apply(file, rewrites!!),
		)
	}

	@Test
	fun `a declaration sharing its line keeps the rest of the line`() {
		val file =
			"package p\n" +
				"fun demo(): Int {\n" +
				"\tval x = 1; return g(x)\n" +
				"}\n"
		val result =
			plan(
				fileText = file,
				declaration = "val x = 1",
				initializerText = "1",
				references = listOf(reference(file, "x", after = 1)),
			)

		val rewrites = buildInlineVariableRewrites(result, InlineMode.AllReferences)

		// Deleting "the line" here would take the `return` with it -- the same defect class the sibling
		// extract-variable refactoring already hit.
		assertEquals(
			"package p\n" +
				"fun demo(): Int {\n" +
				"\treturn g(1)\n" +
				"}\n",
			apply(file, rewrites!!),
		)
	}

	@Test
	fun `a trailing comment is preserved on its own line at the declaration's indentation`() {
		val file =
			"package p\n" +
				"fun demo(a: Int, b: Int): Int {\n" +
				"\tval total = a + b // running total\n" +
				"\treturn total\n" +
				"}\n"
		val result =
			plan(
				fileText = file,
				declaration = "val total = a + b",
				initializerText = "a + b",
				// after = 2: the comment text contains "total" too, so it is the third occurrence.
				references = listOf(reference(file, "total", after = 2)),
				initializerNeedsParentheses = true,
				name = "total",
			)

		val rewrites = buildInlineVariableRewrites(result, InlineMode.AllReferences)

		assertEquals(
			"package p\n" +
				"fun demo(a: Int, b: Int): Int {\n" +
				"\t// running total\n" +
				"\treturn (a + b)\n" +
				"}\n",
			apply(file, rewrites!!),
		)
	}

	@Test
	fun `edits are sorted descending with the declaration deletion last`() {
		val file =
			"package p\n" +
				"fun demo(a: Int): Int {\n" +
				"\tval x = a\n" +
				"\treturn x + x\n" +
				"}\n"
		val first = file.indexOf("x + x")
		val references =
			listOf(
				InlineReference(TextSpan(first, first + 1), false, null),
				InlineReference(TextSpan(first + 4, first + 5), false, null),
			)
		val result = plan(fileText = file, declaration = "val x = a", initializerText = "a", references = references)

		val rewrites = buildInlineVariableRewrites(result, InlineMode.AllReferences)!!

		assertEquals(3, rewrites.size)
		assertEquals(rewrites.map { it.span.start }.sortedDescending(), rewrites.map { it.span.start })
		assertEquals("", rewrites.last().newText)
		assertEquals(
			"package p\n" +
				"fun demo(a: Int): Int {\n" +
				"\treturn a + a\n" +
				"}\n",
			apply(file, rewrites),
		)
	}

	@Test
	fun `this-reference-only rewrites one site and keeps the declaration`() {
		val file =
			"package p\n" +
				"fun demo(a: Int): Int {\n" +
				"\tval x = a\n" +
				"\treturn x + x\n" +
				"}\n"
		val first = file.indexOf("x + x")
		val references =
			listOf(
				InlineReference(TextSpan(first, first + 1), false, null),
				InlineReference(TextSpan(first + 4, first + 5), false, null),
			)
		val result =
			plan(
				fileText = file,
				declaration = "val x = a",
				initializerText = "a",
				references = references,
				cursorReferenceIndex = 1,
			)

		val rewrites = buildInlineVariableRewrites(result, InlineMode.ThisReferenceOnly)!!

		assertEquals(1, rewrites.size)
		assertEquals(
			"package p\n" +
				"fun demo(a: Int): Int {\n" +
				"\tval x = a\n" +
				"\treturn x + a\n" +
				"}\n",
			apply(file, rewrites),
		)
	}

	@Test
	fun `an excluded reference is left untouched and the declaration stays`() {
		val file =
			"package p\n" +
				"fun demo(a: Int): Int {\n" +
				"\tval x = a\n" +
				"\treturn x + x\n" +
				"}\n"
		val first = file.indexOf("x + x")
		val references =
			listOf(
				InlineReference(TextSpan(first, first + 1), false, null),
				InlineReference(TextSpan(first + 4, first + 5), false, InlineExclusion.PastCutoff),
			)
		val result =
			plan(
				fileText = file,
				declaration = "val x = a",
				initializerText = "a",
				references = references,
				canDeleteDeclaration = false,
			)

		val rewrites = buildInlineVariableRewrites(result, InlineMode.AllReferences)!!

		assertEquals(1, rewrites.size)
		assertEquals(
			"package p\n" +
				"fun demo(a: Int): Int {\n" +
				"\tval x = a\n" +
				"\treturn a + x\n" +
				"}\n",
			apply(file, rewrites),
		)
	}

	@Test
	fun `a CRLF file keeps CRLF when the comment line is re-emitted`() {
		val file =
			"package p\r\n" +
				"fun demo(a: Int): Int {\r\n" +
				"\tval x = a // keep me\r\n" +
				"\treturn x\r\n" +
				"}\r\n"
		val at = file.indexOf("return x") + "return ".length
		val result =
			plan(
				fileText = file,
				declaration = "val x = a",
				initializerText = "a",
				references = listOf(InlineReference(TextSpan(at, at + 1), false, null)),
			)

		val rewrites = buildInlineVariableRewrites(result, InlineMode.AllReferences)!!

		assertEquals(
			"package p\r\n" +
				"fun demo(a: Int): Int {\r\n" +
				"\t// keep me\r\n" +
				"\treturn a\r\n" +
				"}\r\n",
			apply(file, rewrites),
		)
	}

	@Test
	fun `nothing to rewrite returns null rather than an empty edit list`() {
		val file = "package p\nfun demo(a: Int) {\n\tval x = a\n}\n"
		val result =
			plan(
				fileText = file,
				declaration = "val x = a",
				initializerText = "a",
				references = emptyList(),
				canDeleteDeclaration = false,
			)

		assertNull(buildInlineVariableRewrites(result, InlineMode.AllReferences))
	}

	@Test
	fun `a span past the end of the text is refused rather than applied`() {
		val file = "package p\nfun demo(a: Int) {\n\tval x = a\n\tg(x)\n}\n"
		val result =
			plan(
				fileText = file,
				declaration = "val x = a",
				initializerText = "a",
				references = listOf(InlineReference(TextSpan(file.length - 1, file.length + 5), false, null)),
			)

		assertNull(buildInlineVariableRewrites(result, InlineMode.AllReferences))
	}

	@Test
	fun `a plain identifier is recognised, an expression is not`() {
		assertTrue(isPlainIdentifier("name"))
		assertTrue(isPlainIdentifier("_x2"))
		assertTrue(!isPlainIdentifier("user.name"))
		assertTrue(!isPlainIdentifier("a + b"))
		assertTrue(!isPlainIdentifier("2fast"))
		assertTrue(!isPlainIdentifier(""))
	}
}
