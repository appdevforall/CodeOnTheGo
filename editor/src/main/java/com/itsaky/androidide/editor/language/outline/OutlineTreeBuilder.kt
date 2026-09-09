package com.itsaky.androidide.editor.language.outline

import com.itsaky.androidide.models.Range

internal data class RawOutlineSymbol(
	val name: String,
	val detail: String?,
	val kind: OutlineSymbolKind,
	val range: Range,
	val selectionRange: Range,
)

internal object OutlineTreeBuilder {
	fun build(raw: List<RawOutlineSymbol>): List<OutlineSymbol> {
		val sorted =
			raw.sortedWith(
				compareBy({ it.range.start.index }, { -it.range.end.index }),
			)
		val roots = mutableListOf<MutableNode>()
		val stack = ArrayDeque<MutableNode>()
		for (symbol in sorted) {
			while (stack.isNotEmpty() && !stack.last().raw.strictlyContains(symbol)) {
				stack.removeLast()
			}
			val node = MutableNode(symbol)
			if (stack.isEmpty()) {
				roots.add(node)
			} else {
				stack.last().children.add(node)
			}
			stack.addLast(node)
		}
		return roots.map { it.freeze() }
	}

	private fun RawOutlineSymbol.strictlyContains(other: RawOutlineSymbol): Boolean {
		val encloses =
			range.start.index <= other.range.start.index &&
				other.range.end.index <= range.end.index
		val sameRange =
			range.start.index == other.range.start.index &&
				range.end.index == other.range.end.index
		return encloses && !sameRange
	}

	private class MutableNode(
		val raw: RawOutlineSymbol,
	) {
		val children = mutableListOf<MutableNode>()

		fun freeze(): OutlineSymbol =
			OutlineSymbol(
				name = raw.name,
				detail = raw.detail,
				kind = raw.kind,
				range = raw.range,
				selectionRange = raw.selectionRange,
				children = children.map { it.freeze() },
			)
	}
}
