package org.appdevforall.cotg.corpus.medium.core

import org.appdevforall.cotg.corpus.medium.formatters.AdditionCalculator
import org.appdevforall.cotg.corpus.medium.formatters.CasualGreeter
import org.appdevforall.cotg.corpus.medium.formatters.EmailValidator
import org.appdevforall.cotg.corpus.medium.formatters.FormalGreeter
import org.appdevforall.cotg.corpus.medium.formatters.LengthValidator
import org.appdevforall.cotg.corpus.medium.formatters.MultiplicationCalculator
import org.appdevforall.cotg.corpus.medium.formatters.SimpleGreeter
import org.appdevforall.cotg.corpus.medium.formatters.SubtractionCalculator

/** Central lookup for the app's swappable [Greeter], [Calculator], and [Validator] impls. */
object ComponentRegistry {
	val greeters: Map<String, Greeter> =
		mapOf(
			"simple" to SimpleGreeter(),
			"formal" to FormalGreeter(),
			"casual" to CasualGreeter(),
		)

	val calculators: Map<String, Calculator> =
		mapOf(
			"add" to AdditionCalculator(),
			"subtract" to SubtractionCalculator(),
			"multiply" to MultiplicationCalculator(),
		)

	val validators: Map<String, Validator> =
		mapOf(
			"length" to LengthValidator(),
			"email" to EmailValidator(),
		)

	fun greeterFor(key: String): Greeter = greeters[key] ?: SimpleGreeter()

	fun calculatorFor(key: String): Calculator = calculators[key] ?: AdditionCalculator()

	fun validatorFor(key: String): Validator = validators[key] ?: LengthValidator()
}
