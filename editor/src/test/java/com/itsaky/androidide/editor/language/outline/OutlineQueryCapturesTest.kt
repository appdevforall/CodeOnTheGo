package com.itsaky.androidide.editor.language.outline

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.utils.FileProvider
import org.junit.Test

class OutlineQueryCapturesTest {
	private val symbolCapture = Regex("@symbol\\.(\\w+)")

	private val queryDir =
		FileProvider
			.projectRoot()
			.resolve("editor/src/main/assets/editor/treesitter")

	@Test
	fun `every shipped language has an outline query`() {
		val found = listOf("java", "kt", "xml").filter { queryDir.resolve("$it/outline.scm").toFile().isFile }

		assertThat(found).containsExactly("java", "kt", "xml")
	}

	@Test
	fun `every symbol capture in every query maps to an OutlineSymbolKind`() {
		val unknown = mutableListOf<String>()
		var captures = 0
		for (language in listOf("java", "kt", "xml")) {
			val text = queryDir.resolve("$language/outline.scm").toFile().readText()
			for (match in symbolCapture.findAll(text)) {
				val suffix = match.groupValues[1]
				captures++
				if (OutlineSymbolKind.fromCaptureSuffix(suffix) == null) {
					unknown += "$language/outline.scm: @symbol.$suffix"
				}
			}
		}

		assertThat(unknown).isEmpty()
		assertThat(captures).isAtLeast(20)
	}

	@Test
	fun `capture suffixes are matched case-insensitively across the camel boundary`() {
		assertThat(OutlineSymbolKind.fromCaptureSuffix("enumMember")).isEqualTo(OutlineSymbolKind.ENUM_MEMBER)
		assertThat(OutlineSymbolKind.fromCaptureSuffix("typeAlias")).isEqualTo(OutlineSymbolKind.TYPE_ALIAS)
		assertThat(OutlineSymbolKind.fromCaptureSuffix("class")).isEqualTo(OutlineSymbolKind.CLASS)
		assertThat(OutlineSymbolKind.fromCaptureSuffix("nonsense")).isNull()
	}
}
