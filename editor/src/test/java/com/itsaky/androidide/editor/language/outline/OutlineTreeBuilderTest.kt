package com.itsaky.androidide.editor.language.outline

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.models.Position
import com.itsaky.androidide.models.Range
import org.junit.Test

class OutlineTreeBuilderTest {
	private fun raw(
		name: String,
		start: Int,
		end: Int,
		kind: OutlineSymbolKind = OutlineSymbolKind.METHOD,
	) = RawOutlineSymbol(
		name = name,
		detail = null,
		kind = kind,
		range = Range(Position(0, 0, start), Position(0, 0, end)),
		selectionRange = Range(Position(0, 0, start), Position(0, 0, start)),
	)

	@Test
	fun `empty input produces empty tree`() {
		assertThat(OutlineTreeBuilder.build(emptyList())).isEmpty()
	}

	@Test
	fun `single symbol is a root`() {
		val result = OutlineTreeBuilder.build(listOf(raw("a", 0, 10)))
		assertThat(result).hasSize(1)
		assertThat(result[0].name).isEqualTo("a")
		assertThat(result[0].children).isEmpty()
	}

	@Test
	fun `contained symbol becomes a child`() {
		val result =
			OutlineTreeBuilder.build(
				listOf(raw("outer", 0, 100, OutlineSymbolKind.CLASS), raw("inner", 10, 50)),
			)
		assertThat(result).hasSize(1)
		assertThat(result[0].name).isEqualTo("outer")
		assertThat(result[0].children.map { it.name }).containsExactly("inner")
	}

	@Test
	fun `adjacent symbols do not nest`() {
		val result = OutlineTreeBuilder.build(listOf(raw("a", 0, 10), raw("b", 10, 20)))
		assertThat(result.map { it.name }).containsExactly("a", "b").inOrder()
		assertThat(result[0].children).isEmpty()
	}

	@Test
	fun `siblings keep document order`() {
		val result =
			OutlineTreeBuilder.build(
				listOf(
					raw("parent", 0, 100, OutlineSymbolKind.CLASS),
					raw("second", 40, 60),
					raw("first", 10, 30),
				),
			)
		assertThat(result[0].children.map { it.name }).containsExactly("first", "second").inOrder()
	}

	@Test
	fun `same start offset nests the shorter inside the longer`() {
		val result =
			OutlineTreeBuilder.build(
				listOf(raw("short", 0, 20), raw("long", 0, 100, OutlineSymbolKind.CLASS)),
			)
		assertThat(result).hasSize(1)
		assertThat(result[0].name).isEqualTo("long")
		assertThat(result[0].children.map { it.name }).containsExactly("short")
	}

	@Test
	fun `deep nesting chains through the stack`() {
		val result =
			OutlineTreeBuilder.build(
				listOf(
					raw("l1", 0, 100, OutlineSymbolKind.CLASS),
					raw("l2", 10, 90, OutlineSymbolKind.CLASS),
					raw("l3", 20, 80),
					raw("uncle", 91, 99),
				),
			)
		assertThat(result).hasSize(1)
		val l1 = result[0]
		assertThat(l1.children.map { it.name }).containsExactly("l2", "uncle").inOrder()
		val l2 = l1.children[0]
		assertThat(l2.children.map { it.name }).containsExactly("l3")
	}
}
