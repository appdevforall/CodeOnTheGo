package com.itsaky.androidide.desugaring.dsl

import java.io.Serializable

/**
 * Describes a full class-reference replacement: every bytecode reference to
 * [fromClass] in any instrumented class will be rewritten to [toClass].
 *
 * Class names may be given in dot-notation (`com.example.Foo`) or
 * slash-notation (`com/example/Foo`); both are normalised internally.
 *
 * @author Akash Yadav
 */
data class ReplaceClassRef(
	/** The class whose references should be replaced (dot-notation). */
	val fromClass: String,
	/** The class that should replace all [fromClass] references (dot-notation). */
	val toClass: String,
) : Serializable {

	companion object {
		@JvmField
		val serialVersionUID = 1L
	}

	/** ASM internal name (slash-notation) for [fromClass]. */
	val fromInternal: String get() = fromClass.replace('.', '/')

	/** ASM internal name (slash-notation) for [toClass]. */
	val toInternal: String get() = toClass.replace('.', '/')
}