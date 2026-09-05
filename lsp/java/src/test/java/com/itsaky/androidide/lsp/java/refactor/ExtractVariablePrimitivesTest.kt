package com.itsaky.androidide.lsp.java.refactor

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.refactor.BlockAnchor
import com.itsaky.androidide.lsp.refactor.BracelessBody
import com.itsaky.androidide.lsp.refactor.RewriteSpan
import com.itsaky.androidide.lsp.refactor.TextSpan
import com.itsaky.androidide.resources.R
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Java's own compiler-free half: what a candidate label reads as, what a selection trims to, how the
 * type text is rendered, and how [buildExtractVariableRewrite] composes Java's three [AnchorForm]s.
 *
 * The geometry and offset primitives these sit on belong to `:lsp:refactor-core` and are tested there,
 * once, rather than again per language.
 */
@RunWith(JUnit4::class)
class ExtractVariablePrimitivesTest {
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
		// javac's body span starts at the statement, not at its indentation. The rewrite widens it back to
		// the line start when the body owns its line, so the brace opens at the owner's indent and lines up
		// with the `}` that closes it rather than sitting a level deeper (itsaky, BlockRewrite.kt:249).
		val form =
			AnchorForm.WrapInBraces(
				BracelessBody(bodyStart = 8, bodyEnd = 19, indent = "", innerIndent = "\t"),
			)
		val rewrite = rewriteOf(text, candidate = TextSpan(12, 17), form = form)
		assertThat(applied(text, rewrite)).isEqualTo("if (c)\n{\n\tint v = a + b;\n\tfoo(v);\n}")
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
		return AnchorForm.ExistingBlock(
			BlockAnchor(contentSpan = TextSpan(open + 1, close), statementSpans = statements),
		)
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
