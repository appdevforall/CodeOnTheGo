package com.itsaky.androidide.lsp.refactor

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * The language-agnostic half of extract-variable, tested once instead of once per language server.
 *
 * The rewrite functions produce the text written into the user's file, so they are asserted on their
 * emitted source rather than on a [RewriteSpan], which is where an off-by-one hides.
 */
@RunWith(JUnit4::class)
class RefactorCoreTest {
	@Test
	fun `spans overlap only when they share a character`() {
		assertThat(TextSpan(0, 5).overlaps(TextSpan(4, 8))).isTrue()
		assertThat(TextSpan(0, 5).overlaps(TextSpan(5, 8))).isFalse()
		assertThat(TextSpan(3, 3).overlaps(TextSpan(3, 3))).isFalse()
		assertThat(TextSpan(2, 9).overlaps(TextSpan(4, 5))).isTrue()
	}

	@Test
	fun `a span cannot end before it starts`() {
		runCatching { TextSpan(5, 2) }.let { assertThat(it.isFailure).isTrue() }
	}

	@Test
	fun `an indented anchor on its own line takes the line above`() {
		val text = "{\n\tfoo(a + b);\n}"
		val block = BlockAnchor(contentSpan = TextSpan(1, 15), statementSpans = listOf(TextSpan(3, 14)))
		assertThat(blockPlacementFor(text, block, TextSpan(7, 12)))
			.isInstanceOf(BlockPlacement.LineAbove::class.java)
	}

	@Test
	fun `a one-line block is expanded rather than refused`() {
		val text = "{ foo(a + b); }"
		val block = BlockAnchor(contentSpan = TextSpan(1, 14), statementSpans = listOf(TextSpan(2, 13)))
		assertThat(blockPlacementFor(text, block, TextSpan(6, 11))).isEqualTo(BlockPlacement.ExpandOneLine)
	}

	@Test
	fun `an anchor sharing a line inside a multi-line block is refused`() {
		// Threading a declaration into a line that also holds unrelated statements would reorder them, and
		// hoisting it above the line can land outside the block. Both languages used to reorder here.
		val text = "{\n\tbar(); foo(a + b);\n\ttail();\n}"
		val block =
			BlockAnchor(
				contentSpan = TextSpan(1, 30),
				statementSpans = listOf(TextSpan(3, 9), TextSpan(10, 21)),
			)
		assertThat(blockPlacementFor(text, block, TextSpan(14, 19))).isEqualTo(BlockPlacement.Refused)
	}

	@Test
	fun `a target no statement contains is refused`() {
		val block = BlockAnchor(contentSpan = TextSpan(1, 10), statementSpans = emptyList())
		assertThat(blockPlacementFor("{ foo(); }", block, TextSpan(2, 8))).isEqualTo(BlockPlacement.Refused)
	}

	@Test
	fun `occurrences stop at the first write in each direction`() {
		val occurrences = listOf(TextSpan(0, 5), TextSpan(20, 25), TextSpan(40, 45), TextSpan(60, 65))
		val sound = excludeUnsoundOccurrences(occurrences, candidateSpan = TextSpan(20, 25), writeOffsets = listOf(50))
		assertThat(sound).containsExactly(TextSpan(0, 5), TextSpan(20, 25), TextSpan(40, 45)).inOrder()
	}

	@Test
	fun `a write before the candidate drops the earlier occurrences`() {
		val occurrences = listOf(TextSpan(0, 5), TextSpan(20, 25), TextSpan(40, 45))
		val sound = excludeUnsoundOccurrences(occurrences, candidateSpan = TextSpan(20, 25), writeOffsets = listOf(10))
		assertThat(sound).containsExactly(TextSpan(20, 25), TextSpan(40, 45)).inOrder()
	}

	@Test
	fun `the candidate is never dropped even when it is not in the list`() {
		val sound = excludeUnsoundOccurrences(listOf(TextSpan(0, 5)), TextSpan(90, 95), writeOffsets = emptyList())
		assertThat(sound).containsExactly(TextSpan(90, 95))
	}

	@Test
	fun `leading unplaceable occurrences are dropped and the candidate survives`() {
		// The leading site shares the opening-brace line, so anchoring a replace-all there would refuse
		// the whole rewrite; dropping it keeps the count achievable.
		val text = "{ foo(a + b);\n\tbar(a + b);\n}"
		val block =
			BlockAnchor(
				contentSpan = TextSpan(1, 26),
				statementSpans = listOf(TextSpan(2, 13), TextSpan(15, 26)),
			)
		val candidate = TextSpan(20, 25)
		assertThat(servableOccurrences(text, block, listOf(TextSpan(6, 11), candidate), candidate))
			.containsExactly(candidate)
	}

	@Test
	fun `a rung that is not a block serves every occurrence it was given`() {
		val occurrences = listOf(TextSpan(0, 2), TextSpan(4, 6))
		assertThat(servableOccurrences("foo(a + b)", null, occurrences, TextSpan(4, 6))).isEqualTo(occurrences)
	}

	@Test
	fun `a tab anywhere makes the indent unit a tab`() {
		assertThat(detectIndentUnit("class A {\n\tint a;\n}")).isEqualTo("\t")
	}

	@Test
	fun `the smallest real run of spaces is the indent unit`() {
		assertThat(detectIndentUnit("class A {\n    int a;\n        int b;\n}")).isEqualTo("    ")
	}

	@Test
	fun `a block comment continuation does not win the indent unit`() {
		// ` * text` is alignment, not indentation. The Kotlin copy of this had no such guard, so a single
		// leading space beat every real indent on virtually any documented file.
		assertThat(detectIndentUnit("/**\n * doc\n */\nclass A {\n  int a;\n}")).isEqualTo("  ")
	}

	@Test
	fun `a file with no indentation at all falls back to a tab`() {
		assertThat(detectIndentUnit("class A {\nint a;\n}")).isEqualTo("\t")
	}

	@Test
	fun `CRLF is only emitted for a file that already uses it`() {
		assertThat(detectNewline("a\r\nb")).isEqualTo("\r\n")
		assertThat(detectNewline("a\nb")).isEqualTo("\n")
		assertThat(detectNewline("a")).isEqualTo("\n")
	}

	@Test
	fun `a line start is found from anywhere on the line`() {
		val text = "one\ntwo\nthree"
		assertThat(lineStartOffset(text, 0)).isEqualTo(0)
		assertThat(lineStartOffset(text, 5)).isEqualTo(4)
		assertThat(lineStartOffset(text, 8)).isEqualTo(8)
	}

	@Test
	fun `leading indent stops at the first non-blank`() {
		assertThat(leadingIndentAt("a\n\t\t foo();", 8)).isEqualTo("\t\t ")
		assertThat(leadingIndentAt("foo();", 3)).isEmpty()
	}

	@Test
	fun `whitespace runs are widened over from either side`() {
		assertThat(startOfWhitespaceBefore("a  \tb", 4)).isEqualTo(1)
		assertThat(endOfWhitespaceAfter("a  \tb", 1)).isEqualTo(4)
	}

	@Test
	fun `a position carries line, column and index`() {
		val position = positionAt("one\ntwo\nthree", 9)
		assertThat(position.line).isEqualTo(2)
		assertThat(position.column).isEqualTo(1)
		assertThat(position.index).isEqualTo(9)
	}

	@Test
	fun `a position past the end clamps to the end`() {
		assertThat(positionAt("ab", 99).index).isEqualTo(2)
	}

	@Test
	fun `occurrences are replaced right-to-left and outside targets ignored`() {
		val text = "foo(a + b) + bar(a + b)"
		val replaced = replaceOccurrences(text, TextSpan(0, text.length), listOf(TextSpan(4, 9), TextSpan(17, 22)), "v")
		assertThat(replaced).isEqualTo("foo(v) + bar(v)")
	}

	@Test
	fun `an existing block gains the declaration on the line above the anchor`() {
		val text = "void m() {\n\tfoo(a + b);\n}"
		val block = BlockAnchor(contentSpan = TextSpan(10, 24), statementSpans = listOf(TextSpan(12, 23)))
		val rewrite = existingBlockRewrite(text, block, listOf(TextSpan(16, 21)), "int v = a + b;", "v")!!
		assertThat(applied(text, rewrite)).isEqualTo("void m() {\n\tint v = a + b;\n\tfoo(v);\n}")
	}

	@Test
	fun `a replace-all rewrites every target in one edit`() {
		val text = "void m() {\n\tfoo(a + b);\n\tbar(a + b);\n}"
		val block =
			BlockAnchor(
				contentSpan = TextSpan(10, 37),
				statementSpans = listOf(TextSpan(12, 23), TextSpan(25, 36)),
			)
		val targets = listOf(TextSpan(16, 21), TextSpan(29, 34))
		val rewrite = existingBlockRewrite(text, block, targets, "int v = a + b;", "v")!!
		assertThat(applied(text, rewrite)).isEqualTo("void m() {\n\tint v = a + b;\n\tfoo(v);\n\tbar(v);\n}")
	}

	@Test
	fun `a refused anchor produces no rewrite`() {
		val block = BlockAnchor(contentSpan = TextSpan(1, 10), statementSpans = emptyList())
		assertThat(existingBlockRewrite("{ foo(); }", block, listOf(TextSpan(2, 8)), "int v = 1;", "v")).isNull()
	}

	@Test
	fun `expanding a one-line block keeps what precedes the anchor in front of it`() {
		// Prepending the declaration to the whole block would hoist it above `int a = 1;`, which the
		// expression depends on. Both languages did exactly that before this moved here.
		val text = "void m() { int a = 1; foo(a + 2); }"
		val block =
			BlockAnchor(
				contentSpan = TextSpan(11, 33),
				statementSpans = listOf(TextSpan(11, 21), TextSpan(22, 33)),
			)
		val rewrite = existingBlockRewrite(text, block, listOf(TextSpan(26, 31)), "int v = a + 2;", "v")!!
		val out = applied(text, rewrite)
		assertThat(out.indexOf("int a = 1")).isLessThan(out.indexOf("int v ="))
	}

	@Test
	fun `a braceless body is wrapped in braces around the declaration`() {
		val text = "if (c)\n\tfoo(a + b);"
		val body = BracelessBody(bodyStart = 8, bodyEnd = 19, indent = "", innerIndent = "\t")
		val rewrite = wrapInBracesRewrite(text, body, listOf(TextSpan(12, 17)), "int v = a + b;", "v")
		assertThat(applied(text, rewrite)).isEqualTo("if (c)\n\t{\n\tint v = a + b;\n\tfoo(v);\n}")
	}

	@Test
	fun `an accessor prefix is stripped only in front of a capital`() {
		assertThat(stripAccessorPrefix("getFoo")).isEqualTo("foo")
		assertThat(stripAccessorPrefix("isReady")).isEqualTo("ready")
		assertThat(stripAccessorPrefix("hasNext")).isEqualTo("next")
		assertThat(stripAccessorPrefix("getter")).isEqualTo("getter")
		assertThat(stripAccessorPrefix("is")).isEqualTo("is")
	}

	@Test
	fun `a name from a type handles both languages' spellings`() {
		// The two copies of this had drifted: Java's stripped `[]` and Kotlin's stripped `?`/`!`, so each
		// mishandled the other's. Neither language produces the other's spelling, so one pass covers both.
		assertThat(nameFromType("java.util.List<Foo>")).isEqualTo("list")
		assertThat(nameFromType("java.time.Duration")).isEqualTo("duration")
		assertThat(nameFromType("String[]")).isEqualTo("string")
		assertThat(nameFromType("kotlin.time.Duration?")).isEqualTo("duration")
		assertThat(nameFromType("Foo!")).isEqualTo("foo")
		assertThat(nameFromType("int")).isEqualTo("int")
		assertThat(nameFromType("  ")).isNull()
	}

	@Test
	fun `a taken name gains the first free suffix`() {
		assertThat(uniqueName("size", emptySet())).isEqualTo("size")
		assertThat(uniqueName("size", setOf("size"))).isEqualTo("size1")
		assertThat(uniqueName("size", setOf("size", "size1", "size2"))).isEqualTo("size3")
	}

	private fun applied(
		text: String,
		rewrite: RewriteSpan,
	): String = text.substring(0, rewrite.span.start) + rewrite.newText + text.substring(rewrite.span.end)
}
