package org.appdevforall.cotg.corpus.medium.data

data class Order(
	val id: Int,
	val user: User,
	val items: List<Product>,
)
