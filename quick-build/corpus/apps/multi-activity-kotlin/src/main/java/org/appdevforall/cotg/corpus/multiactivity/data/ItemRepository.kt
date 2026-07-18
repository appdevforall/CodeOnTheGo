package org.appdevforall.cotg.corpus.multiactivity.data

data class Item(
	val id: Int,
	val name: String,
)

class ItemRepository {
	private val items =
		listOf(
			Item(1, "Widget"),
			Item(2, "Gadget"),
			Item(3, "Doohickey"),
		)

	fun all(): List<Item> = items

	fun findById(id: Int): Item? = items.firstOrNull { it.id == id }
}
