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
	;

	companion object {
		private val CAMEL_BOUNDARY = Regex("([a-z])([A-Z])")

		fun fromCaptureSuffix(suffix: String): OutlineSymbolKind? {
			val constantName = suffix.replace(CAMEL_BOUNDARY, "$1_$2").uppercase()
			return entries.find { it.name == constantName }
		}
	}
}

data class OutlineSymbol(
	val name: String,
	val detail: String?,
	val kind: OutlineSymbolKind,
	val range: Range,
	val selectionRange: Range,
	val children: List<OutlineSymbol>,
)
