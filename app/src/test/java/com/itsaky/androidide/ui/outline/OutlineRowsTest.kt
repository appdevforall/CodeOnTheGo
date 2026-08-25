package com.itsaky.androidide.ui.outline

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.editor.language.outline.OutlineSymbol
import com.itsaky.androidide.editor.language.outline.OutlineSymbolKind
import com.itsaky.androidide.models.Position
import com.itsaky.androidide.models.Range
import org.junit.Test

class OutlineRowsTest {
	private fun symbol(
		name: String,
		children: List<OutlineSymbol> = emptyList(),
	) = OutlineSymbol(
		name = name,
		detail = null,
		kind = OutlineSymbolKind.METHOD,
		range = Range(Position(0, 0, 0), Position(0, 0, 1)),
		selectionRange = Range(Position(0, 0, 0), Position(0, 0, 1)),
		children = children,
	)

	@Test
	fun `flattens depth-first with depths`() {
		val tree = listOf(symbol("a", listOf(symbol("b", listOf(symbol("c"))))), symbol("d"))
		val rows = flattenOutline(tree, emptySet())
		assertThat(rows.map { it.symbol.name }).containsExactly("a", "b", "c", "d").inOrder()
		assertThat(rows.map { it.depth }).containsExactly(0, 1, 2, 0).inOrder()
	}

	@Test
	fun `collapsed path hides descendants but keeps the row`() {
		val tree = listOf(symbol("a", listOf(symbol("b", listOf(symbol("c"))))))
		val rows = flattenOutline(tree, setOf("a/b"))
		assertThat(rows.map { it.symbol.name }).containsExactly("a", "b").inOrder()
		assertThat(rows[1].collapsed).isTrue()
		assertThat(rows[1].hasChildren).isTrue()
	}

	@Test
	fun `same-named siblings get ordinal-suffixed paths`() {
		val tree = listOf(symbol("cls", listOf(symbol("bind"), symbol("bind"), symbol("other"))))
		val rows = flattenOutline(tree, emptySet())
		assertThat(rows.map { it.path })
			.containsExactly("cls", "cls/bind", "cls/bind#2", "cls/other")
			.inOrder()
	}

	@Test
	fun `paths are unique even in deep duplicate trees`() {
		val tree =
			listOf(
				symbol("a", listOf(symbol("x"))),
				symbol("a", listOf(symbol("x"))),
			)
		val rows = flattenOutline(tree, emptySet())
		assertThat(rows.map { it.path }.toSet()).hasSize(4)
	}

	@Test
	fun `hasChildren reflects the tree not the visibility`() {
		val rows = flattenOutline(listOf(symbol("leaf")), emptySet())
		assertThat(rows[0].hasChildren).isFalse()
	}
}
