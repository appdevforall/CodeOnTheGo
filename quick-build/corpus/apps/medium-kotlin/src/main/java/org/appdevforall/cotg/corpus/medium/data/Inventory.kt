package org.appdevforall.cotg.corpus.medium.data

class Inventory {
	private val stock = mutableMapOf<String, Int>()

	fun add(
		product: Product,
		quantity: Int,
	) {
		stock[product.sku] = (stock[product.sku] ?: 0) + quantity
	}

	fun quantityOf(sku: String): Int = stock[sku] ?: 0
}
