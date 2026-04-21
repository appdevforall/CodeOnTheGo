package org.appdevforall.codeonthego.indexing.service

import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

/**
 * A typed key for retrieving an index from the [IndexRegistry].
 *
 * @param T The index type. Not restricted to [org.appdevforall.codeonthego.indexing.api.Index], can be a
 *          domain-specific facade.
 */
data class IndexKey<T : Any>(
	val name: String,
)

/**
 * Central registry where [IndexingService]s publish their indexes
 * and consumers (LSPs, etc.) retrieve them.
 */
class IndexRegistry : Closeable {

	private val indexes = ConcurrentHashMap<String, Any>()
	private val listeners = ConcurrentHashMap<String, MutableList<(Any) -> Unit>>()

	/**
	 * Register an index. Replaces any previously registered index
	 * with the same key.
	 *
	 * If there are listeners waiting for this key, they are notified
	 * immediately.
	 */
	fun <T : Any> register(key: IndexKey<T>, index: T) {
		val old = indexes.put(key.name, index)

		// Close the old index if it's Closeable
		if (old is Closeable && old !== index) {
			old.close()
		}

		// Notify listeners
		listeners[key.name]?.forEach { listener ->
			@Suppress("UNCHECKED_CAST")
			(listener as (T) -> Unit).invoke(index)
		}
	}

	/**
	 * Retrieve an index by key. Returns null if not yet registered.
	 */
	@Suppress("UNCHECKED_CAST")
	fun <T : Any> get(key: IndexKey<T>): T? =
		indexes[key.name] as? T

	/**
	 * Retrieve an index, throwing if not available.
	 */
	fun <T : Any> require(key: IndexKey<T>): T =
		get(key) ?: throw IllegalStateException(
			"Index '${key.name}' is not registered. " +
					"Has the corresponding IndexingService been initialized?"
		)

	/**
	 * Execute a block if the index is available.
	 */
	inline fun <T : Any, R> ifAvailable(
		key: IndexKey<T>,
		block: (T) -> R,
	): R? {
		val index = get(key) ?: return null
		return block(index)
	}

	/**
	 * Register a listener that will be called when an index
	 * is registered (or re-registered) with the given key.
	 *
	 * If the index is already registered, the listener is
	 * called immediately.
	 */
	fun <T : Any> onAvailable(key: IndexKey<T>, listener: (T) -> Unit) {
		@Suppress("UNCHECKED_CAST")
		listeners.getOrPut(key.name) { mutableListOf() }
			.add(listener as (Any) -> Unit)

		// If already registered, notify immediately
		get(key)?.let { listener(it) }
	}

	/**
	 * Unregister an index. The caller is responsible for closing it.
	 */
	fun <T : Any> unregister(key: IndexKey<T>): T? {
		@Suppress("UNCHECKED_CAST")
		return indexes.remove(key.name) as? T
	}

	/**
	 * Returns true if an index is registered for this key.
	 */
	fun <T : Any> isRegistered(key: IndexKey<T>): Boolean =
		indexes.containsKey(key.name)

	/**
	 * Returns all registered keys.
	 */
	fun registeredKeys(): Set<String> = indexes.keys.toSet()

	/**
	 * Close and remove all registered indexes.
	 */
	override fun close() {
		indexes.values.forEach { index ->
			if (index is Closeable) index.close()
		}
		indexes.clear()
		listeners.clear()
	}
}