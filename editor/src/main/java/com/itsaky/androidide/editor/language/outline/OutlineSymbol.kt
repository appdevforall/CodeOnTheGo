package com.itsaky.androidide.editor.language.outline

import com.itsaky.androidide.models.Range

enum class OutlineSymbolKind(
	val badge: Char,
) {
	CLASS('C'),
	INTERFACE('I'),
	ENUM('E'),
	ENUM_MEMBER('V'),
	RECORD('R'),
	ANNOTATION('A'),
	OBJECT('O'),
	COMPANION('O'),
	TYPE_ALIAS('T'),
	CONSTRUCTOR('N'),
	METHOD('M'),
	FIELD('F'),
	PROPERTY('P'),
	ELEMENT('X'),
}

data class OutlineSymbol(
	val name: String,
	val detail: String?,
	val kind: OutlineSymbolKind,
	val range: Range,
	val selectionRange: Range,
	val children: List<OutlineSymbol>,
)
