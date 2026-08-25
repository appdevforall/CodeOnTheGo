package com.itsaky.androidide.lsp.java.refactor

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.resources.R
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * The half of the analysis that needs no compiler: spans, placement, occurrence filtering, the three
 * rewrite shapes, and the name/type text helpers.
 *
 * The rewrite functions produce the text written into the user's file, so they are asserted on their
 * emitted source rather than on a [RewriteSpan], which is where an off-by-one hides.
 */
@RunWith(JUnit4::class)
class ExtractVariablePrimitivesTest {
	@Test
	fun `spans overlap only when they share a character`() {
		assertThat(TextSpan(0, 5).overlaps(TextSpan(4, 8))).isTrue()
		assertThat(TextSpan(0, 5).overlaps(TextSpan(5, 8))).isFalse()
		assertThat(TextSpan(3, 3).overlaps(TextSpan(3, 3))).isFalse()
		assertThat(TextSpan(2, 9).overlaps(TextSpan(4, 5))).isTrue()
	}

	@Test
	fun `a label collapses whitespace and closes up before a dot`() {
		assertThat(collapseForLabel("items\n\t.stream()\n\t.count()")).isEqualTo("items.stream().count()")
	}

	@Test
	fun `a label longer than the limit is elided`() {
		val label = collapseForLabel("a".repeat(200), maxLength = 20)
		assertThat(label).hasLength(20)
		assertThat(label).endsWith("...")
	}

	@Test
	fun `a whitespace-only selection collapses to a cursor at its start`() {
		assertThat(trimToCode("a  +  b", 1, 3)).isEqualTo(1 to 1)
	}

	@Test
	fun `a selection is trimmed to the code inside it`() {
		assertThat(trimToCode("  a + b  ", 0, 9)).isEqualTo(2 to 7)
	}

	@Test
	fun `an out-of-bounds selection is not a selection`() {
		assertThat(trimToCode("abc", 2, 1)).isNull()
		assertThat(trimToCode("abc", -1, 2)).isNull()
		assertThat(trimToCode("abc", 0, 4)).isNull()
	}

	@Test
	fun `an indented anchor on its own line takes the line above`() {
		val text = "{\n\tfoo(a + b);\n}"
		val form = AnchorForm.ExistingBlock(contentSpan = TextSpan(1, 15), statementSpans = listOf(TextSpan(3, 14)))
		val placement = blockPlacementFor(text, form, TextSpan(7, 12))
		assertThat(placement).isInstanceOf(BlockPlacement.LineAbove::class.java)
	}

	@Test
	fun `a one-line block is expanded rather than refused`() {
		val text = "{ foo(a + b); }"
		val form = AnchorForm.ExistingBlock(contentSpan = TextSpan(1, 14), statementSpans = listOf(TextSpan(2, 13)))
		assertThat(blockPlacementFor(text, form, TextSpan(6, 11))).isEqualTo(BlockPlacement.ExpandOneLine)
	}

	@Test
	fun `an anchor sharing a line inside a multi-line block is refused`() {
		val text = "{\n\tbar(); foo(a + b);\n\ttail();\n}"
		val form =
			AnchorForm.ExistingBlock(
				contentSpan = TextSpan(1, 30),
				statementSpans = listOf(TextSpan(3, 9), TextSpan(10, 21)),
			)
		assertThat(blockPlacementFor(text, form, TextSpan(14, 19))).isEqualTo(BlockPlacement.Refused)
	}

	@Test
	fun `a target no statement contains is refused`() {
		val form = AnchorForm.ExistingBlock(contentSpan = TextSpan(1, 10), statementSpans = emptyList())
		assertThat(blockPlacementFor("{ foo(); }", form, TextSpan(2, 8))).isEqualTo(BlockPlacement.Refused)
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
		val form =
			AnchorForm.ExistingBlock(
				contentSpan = TextSpan(1, 26),
				statementSpans = listOf(TextSpan(2, 13), TextSpan(15, 26)),
			)
		val candidate = TextSpan(20, 25)
		val served = servableOccurrences(text, form, listOf(TextSpan(6, 11), candidate), candidate)
		assertThat(served).containsExactly(candidate)
	}

	@Test
	fun `a braceless form serves every occurrence it was given`() {
		val form = AnchorForm.WrapInBraces(bodyStart = 0, bodyEnd = 10, indent = "", innerIndent = "\t")
		val occurrences = listOf(TextSpan(0, 2), TextSpan(4, 6))
		assertThat(servableOccurrences("foo(a + b)", form, occurrences, TextSpan(4, 6))).isEqualTo(occurrences)
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
		// ` * text` is alignment, not indentation, and its single space used to beat every real indent.
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
	fun `an existing block gains the declaration on the line above the anchor`() {
		val text = "void m() {\n\tfoo(a + b);\n}"
		val rewrite = rewriteOf(text, candidate = TextSpan(16, 21), form = existingBlock(text))
		assertThat(applied(text, rewrite)).isEqualTo("void m() {\n\tint v = a + b;\n\tfoo(v);\n}")
	}

	@Test
	fun `a replace-all rewrites every served occurrence in one edit`() {
		val text = "void m() {\n\tfoo(a + b);\n\tbar(a + b);\n}"
		val occurrences = listOf(TextSpan(16, 21), TextSpan(29, 34))
		val rewrite =
			buildExtractVariableRewrite(
				fileText = text,
				candidateSpan = occurrences[0],
				declaredType = "int",
				scope = ScopeOption(BLOCK, existingBlock(text), occurrences),
				name = "v",
				replaceAll = true,
			)!!
		assertThat(applied(text, rewrite)).isEqualTo("void m() {\n\tint v = a + b;\n\tfoo(v);\n\tbar(v);\n}")
	}

	@Test
	fun `a braceless body is wrapped in braces around the declaration`() {
		val text = "if (c)\n\tfoo(a + b);"
		// The body span starts at the statement, not at its indentation -- javac's own span does the same,
		// and starting a character earlier would carry the source indent into the emitted line.
		val form = AnchorForm.WrapInBraces(bodyStart = 8, bodyEnd = 19, indent = "", innerIndent = "\t")
		val rewrite = rewriteOf(text, candidate = TextSpan(12, 17), form = form)
		assertThat(applied(text, rewrite)).isEqualTo("if (c)\n\t{\n\tint v = a + b;\n\tfoo(v);\n}")
	}

	@Test
	fun `an expression body becomes a block that returns the named value`() {
		val text = "x -> a + b"
		val form =
			AnchorForm.ConvertExpressionBody(
				bodyStart = 5,
				bodyEnd = 10,
				indent = "",
				innerIndent = "\t",
				needsReturn = true,
			)
		val rewrite = rewriteOf(text, candidate = TextSpan(5, 10), form = form)
		assertThat(applied(text, rewrite)).isEqualTo("x -> {\n\tint v = a + b;\n\treturn v;\n}")
	}

	@Test
	fun `a void expression body becomes a block with a bare statement`() {
		val text = "() -> sink(a + b)"
		val form =
			AnchorForm.ConvertExpressionBody(
				bodyStart = 6,
				bodyEnd = 17,
				indent = "",
				innerIndent = "\t",
				needsReturn = false,
			)
		val rewrite = rewriteOf(text, candidate = TextSpan(11, 16), form = form)
		assertThat(applied(text, rewrite)).isEqualTo("() -> {\n\tint v = a + b;\n\tsink(v);\n}")
	}

	@Test
	fun `a switch rule body does not gain a doubled semicolon`() {
		val text = "case A -> a + b;"
		val form =
			AnchorForm.ConvertExpressionBody(
				bodyStart = 10,
				bodyEnd = 16,
				indent = "",
				innerIndent = "\t",
				needsReturn = true,
				returnKeyword = "yield",
			)
		val rewrite = rewriteOf(text, candidate = TextSpan(10, 15), form = form)
		assertThat(applied(text, rewrite)).isEqualTo("case A -> {\n\tint v = a + b;\n\tyield v;\n}")
	}

	@Test
	fun `a rewrite whose targets fall outside the text is refused`() {
		val text = "void m() {\n\tfoo(a + b);\n}"
		val rewrite =
			buildExtractVariableRewrite(
				fileText = text,
				candidateSpan = TextSpan(16, 21),
				declaredType = "int",
				scope = ScopeOption(BLOCK, existingBlock(text), listOf(TextSpan(900, 905))),
				name = "v",
				replaceAll = true,
			)
		assertThat(rewrite).isNull()
	}

	@Test
	fun `an unrenderable type is recognised by its javac spelling`() {
		assertThat(isUnrenderableTypeText("capture#1 of ? extends Foo")).isTrue()
		assertThat(isUnrenderableTypeText("Foo & Bar")).isTrue()
		assertThat(isUnrenderableTypeText("<any>")).isTrue()
		assertThat(isUnrenderableTypeText("  ")).isTrue()
		assertThat(isUnrenderableTypeText("java.util.List<String>")).isFalse()
	}

	@Test
	fun `a type is shortened only where the short name resolves`() {
		val shortened =
			shortenTypeText(
				"java.util.Map<java.lang.String, java.time.Duration>",
				importedNames = setOf("java.util.Map"),
				starImportedPackages = emptySet(),
			)
		assertThat(shortened).isEqualTo("Map<String, java.time.Duration>")
	}

	@Test
	fun `a star import does not shorten a name an explicit import already claims`() {
		val shortened =
			shortenTypeText(
				"java.awt.List",
				importedNames = setOf("java.util.List"),
				starImportedPackages = setOf("java.awt"),
			)
		assertThat(shortened).isEqualTo("java.awt.List")
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
	fun `a name from a type drops the package, the arguments and the brackets`() {
		assertThat(nameFromType("java.util.List<Foo>")).isEqualTo("list")
		assertThat(nameFromType("java.time.Duration")).isEqualTo("duration")
		assertThat(nameFromType("String[]")).isEqualTo("string")
		assertThat(nameFromType("int")).isEqualTo("int")
		assertThat(nameFromType("  ")).isNull()
	}

	@Test
	fun `a taken name gains the first free suffix`() {
		assertThat(uniqueName("size", emptySet())).isEqualTo("size")
		assertThat(uniqueName("size", setOf("size"))).isEqualTo("size1")
		assertThat(uniqueName("size", setOf("size", "size1", "size2"))).isEqualTo("size3")
	}

	private fun existingBlock(text: String): AnchorForm.ExistingBlock {
		val open = text.indexOf('{')
		val close = text.lastIndexOf('}')
		val statements =
			text
				.substring(open + 1, close)
				.split('\n')
				.filter { it.isNotBlank() }
				.map { line ->
					val start = text.indexOf(line.trim(), open)
					TextSpan(start, start + line.trim().length)
				}
		return AnchorForm.ExistingBlock(contentSpan = TextSpan(open + 1, close), statementSpans = statements)
	}

	private fun rewriteOf(
		text: String,
		candidate: TextSpan,
		form: AnchorForm,
	): RewriteSpan =
		buildExtractVariableRewrite(
			fileText = text,
			candidateSpan = candidate,
			declaredType = "int",
			scope = ScopeOption(BLOCK, form, listOf(candidate)),
			name = "v",
			replaceAll = false,
		)!!

	private fun applied(
		text: String,
		rewrite: RewriteSpan,
	): String = text.substring(0, rewrite.span.start) + rewrite.newText + text.substring(rewrite.span.end)

	private companion object {
		val BLOCK = ScopeLabel(R.string.label_extract_scope_block)
	}
}
