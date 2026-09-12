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
		siblings.forEachIndexed { index, symbol ->
			val segment = "${symbol.name}#$index"
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
