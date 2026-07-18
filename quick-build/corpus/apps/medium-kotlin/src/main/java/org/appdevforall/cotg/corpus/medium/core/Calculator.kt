package org.appdevforall.cotg.corpus.medium.core

/** Performs a two-operand arithmetic computation. */
interface Calculator {
	fun compute(
		a: Int,
		b: Int,
	): Int
}
