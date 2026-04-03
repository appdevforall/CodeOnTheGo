package com.itsaky.androidide.lsp.kotlin.completion

/**
 * Defines the possible declaration contexts of the element at cursor position.
 */
enum class DeclarationContext {
	TOP_LEVEL,
	CLASS_BODY,
	INTERFACE_BODY,
	OBJECT_BODY,        // includes companion object
	ENUM_BODY,
	FUNCTION_BODY,      // local declarations & statements
	SCRIPT_TOP_LEVEL,
	ANNOTATION_BODY,
}

/**
 * Defines declaration kinds for element at cursor.
 */
enum class DeclarationKind {
	CLASS, INTERFACE, OBJECT, ENUM_CLASS, ANNOTATION_CLASS,
	FUN, PROPERTY_VAL, PROPERTY_VAR,
	TYPEALIAS, CONSTRUCTOR,
	UNKNOWN          // e.g. modifier typed before any keyword yet
}
