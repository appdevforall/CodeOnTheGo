package com.itsaky.androidide.plugins.ai.prompt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import java.util.concurrent.atomic.AtomicReference

/** Supplies the loaded prompt config, suspending until it is available. */
fun interface PromptConfigProvider<out T> {
	/**
	 * Returns the config, waiting for an in-flight load if there is one.
	 *
	 * @return the loaded config.
	 */
	suspend fun config(): T
}

/**
 * Caches a plugin's prompt config in memory, loaded once per activation so a turn never reads
 * disk. A turn arriving mid-load suspends until it finishes. Thread-safe: the slot is swapped
 * atomically, and what it holds is immutable once complete.
 *
 * A plugin keeps one store for its process, e.g. `val shared = PromptConfigStore(MyParser)`.
 *
 * @param parser maps the merged config onto the plugin's config type.
 */
class PromptConfigStore<T>(
	private val parser: PromptConfigParser<T>,
) : PromptConfigProvider<T> {
	private val load = AtomicReference<Deferred<T>?>(null)

	/**
	 * Starts loading from [source] unless a load is already cached; a failed load is retried.
	 *
	 * @param scope the scope the load runs in; give it a SupervisorJob so a failure stays here.
	 * @param source where the config file lives.
	 * @return the load now cached, which callers may observe to log a failure.
	 */
	fun preload(
		scope: CoroutineScope,
		source: PromptConfigSource,
	): Deferred<T> {
		while (true) {
			val current = load.get()
			if (current != null && !current.hasFailed()) return current

			val next =
				scope.async(start = CoroutineStart.LAZY) {
					PromptConfigLoader.load(source, parser)
				}
			if (load.compareAndSet(current, next)) {
				next.start()
				return next
			}
			// Another thread won the swap; drop ours and use theirs.
			next.cancel()
		}
	}

	/**
	 * Returns the cached config, waiting for an in-flight load; throws when none was started.
	 *
	 * @return the loaded config.
	 */
	override suspend fun config(): T {
		val current = checkNotNull(load.get()) { "prompt config was never loaded" }
		return current.await()
	}

	/**
	 * Returns the cached config without waiting, for a caller that cannot suspend.
	 *
	 * @return the config, or null while a load runs, after one failed, or before any started.
	 */
	@OptIn(ExperimentalCoroutinesApi::class)
	fun configIfLoaded(): T? {
		val current = load.get() ?: return null
		// getCompleted() throws for a load still running or one that failed; neither has a value.
		return if (current.isCompleted && !current.hasFailed()) current.getCompleted() else null
	}

	/** Drops the cache, cancelling a load still in flight; the next [preload] reads afresh. */
	fun clear() {
		load.getAndSet(null)?.cancel()
	}

	/**
	 * Whether this load was cancelled or threw. Checks the completion exception as well as
	 * [Deferred.isCancelled] rather than relying on the latter alone to report a failure.
	 */
	@OptIn(ExperimentalCoroutinesApi::class)
	private fun Deferred<*>.hasFailed(): Boolean = isCancelled || (isCompleted && getCompletionExceptionOrNull() != null)
}
