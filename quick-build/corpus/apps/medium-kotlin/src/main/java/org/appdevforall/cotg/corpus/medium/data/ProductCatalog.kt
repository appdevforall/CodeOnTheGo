package org.appdevforall.cotg.corpus.medium.data

class ProductCatalog {
	private val products = mutableListOf<Product>()

	fun add(product: Product) {
		products.add(product)
	}

	fun bySku(sku: String): Product? = products.firstOrNull { it.sku == sku }

	fun all(): List<Product> = products.toList()
}
