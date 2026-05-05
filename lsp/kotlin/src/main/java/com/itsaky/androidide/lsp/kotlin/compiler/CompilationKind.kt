package com.itsaky.androidide.lsp.kotlin.compiler

import java.nio.file.Path
import kotlin.io.path.extension

/**
 * The kind of compilation being performed in a [Compiler].
 */
sealed interface CompilationKind {

	/**
	 * The types of files this compilation kind accepts.
	 */
	val fileExtensions: List<String>

	/**
	 * Whether this compilation kind accepts the given file path. The default
	 * implementation simply checks the accepted [fileExtensions].
	 */
	fun acceptsFile(path: Path): Boolean {
		return path.extension in fileExtensions
	}

	/**
	 * The default compilation kind. Mostly used for normal Kotlin source files.
 	 */
	data object Default : CompilationKind {
		override val fileExtensions = listOf("kt")
	}

	/**
	 * Compilation kind for compiling Kotlin scripts.
	 */
	data object Script : CompilationKind {
		override val fileExtensions = listOf("kts")
	}
}