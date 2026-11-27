package com.itsaky.androidide.lsp.kotlin.compiler.services

import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule

/**
 * A bounded memo of source id to declaring module, misses included, evicting the least recently used
 * entry once it holds [capacity] of them.
 *
 * Modules are held strongly. Every module it can hold comes from its provider's module graph, which
 * the provider itself holds for as long as the memo is in use, so a weak reference would never be
 * cleared; the graph and the memo are dropped together.
 *
 * The lock guards only the map. [lookup] computes a missing entry outside it, so two threads may
 * compute the same entry; both arrive at the same module, and the later write is harmless.
 */
internal class SourceModuleLookupMemo(
	private val capacity: Int,
) {
	private val lock = Any()

	private val entries =
		object : LinkedHashMap<String, KaModule?>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
			override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, KaModule?>): Boolean = size > capacity
		}

	val size: Int
		get() = synchronized(lock) { entries.size }

	/** The module memoized for [sourceId], computing and memoizing it with [compute] when absent. */
	fun lookup(
		sourceId: String,
		compute: (String) -> KaModule?,
	): KaModule? {
		synchronized(lock) {
			if (entries.containsKey(sourceId)) return entries[sourceId]
		}
		val module = compute(sourceId)
		synchronized(lock) { entries[sourceId] = module }
		return module
	}

	private companion object {
		const val INITIAL_CAPACITY = 256
		const val LOAD_FACTOR = 0.75f
	}
}
