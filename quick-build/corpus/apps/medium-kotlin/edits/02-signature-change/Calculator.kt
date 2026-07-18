package org.appdevforall.cotg.corpus.medium.core

/** Performs a two-operand arithmetic computation and describes the result. */
interface Calculator {
	fun compute(a: Int, b: Int): String
}
