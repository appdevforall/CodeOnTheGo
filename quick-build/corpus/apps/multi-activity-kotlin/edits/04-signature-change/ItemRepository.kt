package org.appdevforall.cotg.corpus.multiactivity.data

data class Item(val id: Int, val name: String)

class ItemRepository {

	private val items =
		listOf(
			Item(1, "Widget"),
			Item(2, "Gadget"),
			Item(3, "Doohickey"),
		)

	fun all(): List<Item> = items

	/** Now non-null: an unknown id resolves to a sentinel item instead of null, so every
	 * caller's null-check collapses - this forces both DetailActivity and DeepLinkActivity
	 * (the two callers) to recompile against the new signature. */
	fun findById(id: Int): Item = items.firstOrNull { it.id == id } ?: UNKNOWN_ITEM

	companion object {
		val UNKNOWN_ITEM = Item(id = -1, name = "QB_LOOKUP_FALLBACK_V2")
	}
}
