package org.appdevforall.cotg.corpus.medium.data

import org.appdevforall.cotg.corpus.medium.core.Calculator
import org.appdevforall.cotg.corpus.medium.formatters.MultiplicationCalculator

/** Builds a summary [Report] from a set of orders, using [Calculator] for aggregate math. */
class ReportGenerator(
	private val calculator: Calculator = MultiplicationCalculator(),
) {
	fun summarize(orders: List<Order>): Report {
		val lines = mutableListOf<String>()
		var factor = 1
		for (order in orders) {
			factor = calculator.compute(factor, order.items.size.coerceAtLeast(1))
			lines.add("Order #${order.id}: ${order.items.size} item(s)")
		}
		lines.add("Aggregate factor: $factor")
		return Report(title = "Order Summary", lines = lines)
	}
}
