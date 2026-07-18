package org.appdevforall.cotg.corpus.mixedlang.core

/** Kotlin class calling a same-module Java class - the direction quick build v1 didn't
 * support until the D2 corpus entry surfaced it. */
class OrderService {
	fun total(
		a: Int,
		b: Int,
	) = JavaCalculator().computeTotal(a, b)
}
