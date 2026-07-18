package org.appdevforall.cotg.corpus.medium.data

import org.appdevforall.cotg.corpus.medium.core.Calculator
import org.appdevforall.cotg.corpus.medium.formatters.AdditionCalculator

/** Totals an order's line items using an injected [Calculator]. */
class OrderProcessor(private val calculator: Calculator = AdditionCalculator()) {

	fun total(order: Order): Int {
		var running = 0
		for (item in order.items) {
			val described = calculator.compute(running, item.priceCents)
			running = described.substringAfter(":").toInt()
		}
		return running
	}
}
