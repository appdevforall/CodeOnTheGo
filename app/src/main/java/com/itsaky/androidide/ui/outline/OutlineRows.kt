package com.itsaky.androidide.ui.outline

import com.itsaky.androidide.editor.language.outline.OutlineSymbol

internal data class OutlineRowModel(
	val symbol: OutlineSymbol,
	val path: String,
	val depth: Int,
	val hasChildren: Boolean,
	val collapsed: Boolean,
)

internal fun flattenOutline(
	symbols: List<OutlineSymbol>,
	collapsedPaths: Set<String>,
): List<OutlineRowModel> {
	val rows = mutableListOf<OutlineRowModel>()

	fun walk(siblings: List<OutlineSymbol>, parentPath: String, depth: Int) {
		val occurrences = mutableMapOf<String, Int>()
		siblings.forEach { symbol ->
			val n = occurrences.merge(symbol.name, 1, Int::plus)!!
			val segment = if (n == 1) symbol.name else "${symbol.name}#$n"
			val path = if (parentPath.isEmpty()) segment else "$parentPath/$segment"
			val collapsed = path in collapsedPaths
			rows.add(
				OutlineRowModel(
					symbol = symbol,
					path = path,
					depth = depth,
					hasChildren = symbol.children.isNotEmpty(),
					collapsed = collapsed,
				),
			)
			if (!collapsed) {
				walk(symbol.children, path, depth + 1)
			}
		}
	}

	walk(symbols, "", 0)
	return rows
}
