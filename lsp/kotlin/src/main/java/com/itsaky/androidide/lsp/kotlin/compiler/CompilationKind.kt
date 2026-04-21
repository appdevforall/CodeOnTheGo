package com.itsaky.androidide.lsp.kotlin.compiler

/**
 * The kind of compilation being performed in a [Compiler].
 */
enum class CompilationKind {
	/**
	 * The default compilation kind. Mostly used for normal Kotlin source files.
 	 */
	Default,

	/**
	 * Compilation kind for compiling Kotlin scripts.
	 */
	Script,
}