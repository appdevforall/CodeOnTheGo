package org.appdevforall.codeonthego.indexing.service

import org.appdevforall.codeonthego.indexing.IndexSessionLifetime
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
 *
 * Once closed, it refuses new indexes and [require] throws [IndexRegistryClosedException], so work
 * for an ended project session stops instead of opening indexes nothing would close.
 */
class IndexRegistry(
	/** The project session these indexes belong to; ended when that session closes. */
	val lifetime: IndexSessionLifetime = IndexSessionLifetime(),
) : Closeable {
	private val indexes = ConcurrentHashMap<String, Any>()
	private val listeners = ConcurrentHashMap<String, MutableList<(Any) -> Unit>>()

	@Volatile
	private var closed = false

	/**
	 * Whether [close] has been called or this registry's session has ended.
	 *
	 * The lifetime ends as soon as the manager closes, while [close] runs later in the background, so
	 * an ended lifetime already counts as closed.
	 */
	val isClosed: Boolean
		get() = closed || lifetime.isEnded

	/**
	 * Register an index. Replaces any previously registered index
	 * with the same key.
	 *
	 * If there are listeners waiting for this key, they are notified
	 * immediately.
	 *
	 * @throws IndexRegistryClosedException if the registry is closed; [index] is closed first.
	 */
	fun <T : Any> register(
		key: IndexKey<T>,
		index: T,
	) {
		if (isClosed) {
			(index as? Closeable)?.close()
			throw closedException(key)
		}

		val old = indexes.put(key.name, index)
		if (isClosed) {
			// close() may have drained the map before this entry landed; whoever removes it closes it.
			if (indexes.remove(key.name, index)) (index as? Closeable)?.close()
			throw closedException(key)
		}

		// Close the old index if it's Closeable
		if (old is Closeable && old !== index) {
			old.close()
		}

		notifyListeners(key, index)
	}

	private fun <T : Any> notifyListeners(
		key: IndexKey<T>,
		index: T,
	) {
		listeners[key.name]?.forEach { listener ->
			@Suppress("UNCHECKED_CAST")
			(listener as (T) -> Unit).invoke(index)
		}
	}

	/**
	 * Returns the index registered for [key], registering the one [create] returns if there is none.
	 *
	 * Unlike [register], an existing index is kept open and in place, so callers holding it are not
	 * left with a closed index. [create] runs only when no index is registered, and listeners are
	 * notified only when it ran. [create] runs inside the registry's map update, so it must not call
	 * back into this registry.
	 *
	 * @throws IndexRegistryClosedException if the registry is closed; [create] is then not called.
	 */
	fun <T : Any> registerIfAbsent(
		key: IndexKey<T>,
		create: () -> T,
	): T {
		if (isClosed) throw closedException(key)

		var created: T? = null
		val index = indexes.computeIfAbsent(key.name) { create().also { created = it } }
		if (isClosed) {
			// close() may have drained the map before this entry landed; whoever removes it closes it.
			if (indexes.remove(key.name, index)) (index as? Closeable)?.close()
			throw closedException(key)
		}

		created?.let { notifyListeners(key, it) }

		@Suppress("UNCHECKED_CAST")
		return index as T
	}

	/**
	 * Retrieve an index by key. Returns null if not yet registered.
	 */
	@Suppress("UNCHECKED_CAST")
	fun <T : Any> get(key: IndexKey<T>): T? = indexes[key.name] as? T

	/**
	 * Retrieve an index, throwing if not available.
	 *
	 * @throws IndexRegistryClosedException if the registry is closed.
	 * @throws IllegalStateException if the registry is open but nothing is registered for [key].
	 */
	fun <T : Any> require(key: IndexKey<T>): T =
		get(key) ?: if (isClosed) {
			throw closedException(key)
		} else {
			throw IllegalStateException(
				"Index '${key.name}' is not registered. " +
					"Has the corresponding IndexingService been initialized?",
			)
		}

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
	fun <T : Any> onAvailable(
		key: IndexKey<T>,
		listener: (T) -> Unit,
	) {
		@Suppress("UNCHECKED_CAST")
		listeners
			.getOrPut(key.name) { mutableListOf() }
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
	fun <T : Any> isRegistered(key: IndexKey<T>): Boolean = indexes.containsKey(key.name)

	/**
	 * Returns all registered keys.
	 */
	fun registeredKeys(): Set<String> = indexes.keys.toSet()

	/**
	 * Close and remove all registered indexes, and refuse new ones from now on.
	 */
	override fun close() {
		closed = true
		// Removed one by one so an index a racing registerIfAbsent adds is closed exactly once.
		for (name in indexes.keys.toList()) {
			(indexes.remove(name) as? Closeable)?.close()
		}
		listeners.clear()
	}

	private fun closedException(key: IndexKey<*>) = IndexRegistryClosedException("Index registry is closed; cannot provide '${key.name}'")
}
