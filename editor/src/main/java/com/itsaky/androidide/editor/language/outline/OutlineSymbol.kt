package com.itsaky.androidide.editor.language.outline

import com.itsaky.androidide.models.Range

enum class OutlineSymbolKind(
	val badge: String,
) {
	CLASS("Cl"),
	INTERFACE("In"),
	ENUM("En"),
	ENUM_MEMBER("Em"),
	RECORD("Rc"),
	ANNOTATION("An"),
	OBJECT("Ob"),
	COMPANION("Ob"),
	TYPE_ALIAS("Ta"),
	CONSTRUCTOR("Ct"),
	METHOD("Fn"),
	FIELD("Fd"),
	PROPERTY("Pr"),
	ELEMENT("El"),
}

data class OutlineSymbol(
	val name: String,
	val detail: String?,
	val kind: OutlineSymbolKind,
	val range: Range,
	val selectionRange: Range,
	val children: List<OutlineSymbol>,
)
