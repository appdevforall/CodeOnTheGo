package org.appdevforall.cotg.corpus.medium.formatters

import org.appdevforall.cotg.corpus.medium.core.Calculator

class MultiplicationCalculator : Calculator {
	override fun compute(
		a: Int,
		b: Int,
	): Int = a * b
}
